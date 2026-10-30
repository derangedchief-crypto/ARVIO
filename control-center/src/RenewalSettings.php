<?php
declare(strict_types=1);
namespace ControlCenter;

final class RenewalSettings {
    public function __construct(private readonly Database $db,private readonly Config $config,private readonly Management $management,private readonly Auth $auth,private readonly Secrets $secrets,private readonly RampexGateway $gateway,private readonly RenewalWorker $worker) {}
    public function installed():bool {return (bool)$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['026_renewal_payments.sql']);}
    private function ready():void {if(!$this->installed())throw new Problem(503,'Apply the renewal payments database upgrade first.');}
    public function policy():array {
        if(!$this->installed())return ['enabled'=>false,'testing_only'=>true];
        $r=$this->db->one('SELECT * FROM renewal_policy WHERE id=1');return ['enabled'=>(bool)$r['enabled'],'testing_only'=>(bool)$r['testing_only']];
    }
    public function setPolicy(array $actor,array $input):array {
        Auth::owner($actor);$this->ready();Input::keys($input,['enabled','testing_only']);
        if(!is_bool($input['enabled']??null)||!is_bool($input['testing_only']??null))throw new Problem(422,'Invalid renewal rollout policy.');
        if($input['enabled']&&($this->config->get('renewal_worker',[])['mode']??null)==='catalogue_only')throw new Problem(409,'Catalogue-only connection cannot enable payments or renewal writes.');
        $this->db->query('UPDATE renewal_policy SET enabled=?,testing_only=? WHERE id=1',[(int)$input['enabled'],(int)$input['testing_only']]);
        $this->auth->audit($actor,'renewals.policy_changed',null,null,$input);return $this->policy();
    }
    public function workspace(array $actor,mixed $id=null):array {
        $this->ready();$t=$this->management->workspace($actor,$id);
        // Owner logins deliberately have tenant_id=NULL; Management resolves the
        // existing owner workspace and enforces role/tenant access separately.
        $ownerWorkspace=$t['kind']==='owner'&&$actor['role']==='owner';
        if(($t['kind']!=='reseller'&&!$ownerWorkspace)||$t['status']!=='active'||!$this->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$t['id']]))throw new Problem(409,'Renewals require an active workspace with a verified panel reseller link.');return $t;
    }
    public function connection(string $tenant,string $gateway='rampex'):?array {return $this->db->one('SELECT * FROM renewal_connections WHERE tenant_id=? AND gateway=? AND enabled=1 ORDER BY created_at DESC,id DESC LIMIT 1',[$tenant,$gateway]);}
    public function connections(string $tenant):array {return $this->db->all('SELECT * FROM renewal_connections WHERE tenant_id=? AND enabled=1 ORDER BY created_at DESC,id DESC',[$tenant]);}
    public function availability(string $tenant):array {
        $out=array_fill_keys(['stripe','square','rampex'],['enabled'=>true,'revision'=>0]);
        foreach($this->db->all('SELECT gateway,enabled,revision FROM renewal_gateway_availability WHERE tenant_id=?',[$tenant]) as $r)$out[$r['gateway']]=['enabled'=>(bool)$r['enabled'],'revision'=>(int)$r['revision']];
        return $out;
    }
    public function checkoutEnabled(string $tenant,string $gateway):bool {return $this->availability($tenant)[$gateway]['enabled']??false;}
    public function setAvailability(array $actor,array $input):array {
        Input::keys($input,['tenant_id','gateway','connection_id','enabled','expected_revision']);$t=$this->workspace($actor,$input['tenant_id']??null);$gateway=$input['gateway']??null;
        if(!in_array($gateway,['stripe','square','rampex'],true)||!is_bool($input['enabled']??null))throw new Problem(422,'Choose a supported gateway and checkout availability.');
        $id=Input::id($input['connection_id']??null);$expected=Input::integer($input['expected_revision']??null,0,2147483647);$enabled=$input['enabled'];
        $this->db->transaction(function()use($actor,$t,$gateway,$id,$expected,$enabled):void{
            $this->db->one('SELECT id FROM tenants WHERE id=? FOR UPDATE',[$t['id']]);
            $c=$this->connection($t['id'],$gateway);
            if(!$c||$c['id']!==$id)throw new Problem(409,'Merchant connection changed; refresh before changing checkout availability.');
            $old=$this->availability($t['id'])[$gateway];
            if($old['revision']!==$expected)throw new Problem(409,'Gateway availability changed; refresh before saving.');
            $this->db->query('INSERT INTO renewal_gateway_availability (tenant_id,gateway,enabled,revision) VALUES (?,?,?,1) ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),revision=revision+1',[$t['id'],$gateway,(int)$enabled]);
            if(!$enabled){
                // Disabling is an explicit local handover, NOT a claim that the
                // external payment link was cancelled. Keep every old record.
                $this->db->query("UPDATE renewal_orders o JOIN renewal_connections c ON c.id=o.connection_id SET o.checkout_superseded=1 WHERE o.tenant_id=? AND c.gateway=? AND o.payment_state IN ('creating','awaiting','link_uncertain','verification_pending','review') AND o.fulfilment_state!='fulfilled'",[$t['id'],$gateway]);
                $this->db->query('UPDATE renewal_sessions s JOIN renewal_orders o ON o.id=s.order_id SET s.order_id=NULL WHERE s.tenant_id=? AND o.checkout_superseded=1',[$t['id']]);
                $this->db->query("UPDATE renewal_route_switches r SET r.state='completed',r.token=REPLACE(UUID(),'-',''),r.updated_at=UTC_TIMESTAMP() WHERE r.tenant_id=? AND r.state IN ('ready','switching','blocked') AND EXISTS (SELECT 1 FROM renewal_orders o JOIN renewal_connections c ON c.id=o.connection_id WHERE o.tenant_id=r.tenant_id AND o.line_id=r.line_id AND o.primary_fingerprint=r.primary_fingerprint AND o.checkout_superseded=1 AND c.gateway=?) AND NOT EXISTS (SELECT 1 FROM renewal_orders p WHERE p.tenant_id=r.tenant_id AND p.line_id=r.line_id AND p.primary_fingerprint=r.primary_fingerprint AND p.checkout_superseded=0 AND p.fulfilment_state!='fulfilled' AND p.payment_state IN ('creating','awaiting','link_uncertain','verification_pending','paid','review'))",[$t['id'],$gateway]);
            }
            $this->auth->audit($actor,'renewals.gateway_availability_changed',$id,$t['id'],['gateway'=>$gateway,'enabled'=>$enabled]);
        });return $this->status($actor,['tenant_id'=>$t['id']]);
    }
    public function routing(string $tenant):array {
        $out=[];foreach(['US','CA'] as $country){$r=$this->db->one('SELECT gateways,revision FROM renewal_gateway_routes WHERE tenant_id=? AND country=?',[$tenant,$country]);$out[$country]=['gateways'=>$r?json_decode($r['gateways'],true,8,JSON_THROW_ON_ERROR):($country==='CA'?['stripe','square']:['stripe','square','rampex']),'revision'=>(int)($r['revision']??0)];}return $out;
    }
    public function candidates(string $tenant,string $country,string $currency,bool $testing):array {
        if(!in_array($country,['US','CA'],true))throw new Problem(422,'Confirm USA or Canada before requesting renewal checkout.');
        $connections=$this->connections($tenant);$availability=$this->availability($tenant);$result=[];
        foreach($this->routing($tenant)[$country]['gateways'] as $gateway)foreach($connections as $c){
            if(!$availability[$gateway]['enabled'])continue;
            if($c['gateway']!==$gateway||(!$testing&&$c['mode']!=='live')||!$this->gateway->supports($c,$country,$currency))continue;
            $result[]=$c;break;
        }return $result;
    }
    public function saveRoutes(array $actor,array $input):array {
        Input::keys($input,['tenant_id','country','gateways','expected_revision']);$t=$this->workspace($actor,$input['tenant_id']??null);$country=$input['country']??null;$gateways=$input['gateways']??null;
        if(!in_array($country,['US','CA'],true)||!is_array($gateways)||!array_is_list($gateways)||count($gateways)>3||count(array_filter($gateways,'is_string'))!==count($gateways)||count(array_unique($gateways))!==count($gateways)||array_diff($gateways,['stripe','square','rampex']))throw new Problem(422,'Choose an ordered list of supported gateways for USA or Canada.');
        if($country==='CA'&&in_array('rampex',$gateways,true))throw new Problem(422,'Rampex crypto/card routing is not approved for Canada; select direct Stripe or Square.');
        $expected=Input::integer($input['expected_revision']??null,0,2147483647);
        $this->db->transaction(function()use($actor,$t,$country,$gateways,$expected):void{
            $this->db->one('SELECT id FROM tenants WHERE id=? FOR UPDATE',[$t['id']]);$r=$this->db->one('SELECT revision FROM renewal_gateway_routes WHERE tenant_id=? AND country=? FOR UPDATE',[$t['id'],$country]);
            if((int)($r['revision']??0)!==$expected)throw new Problem(409,'Gateway preferences changed; refresh before saving.');
            $this->db->query('INSERT INTO renewal_gateway_routes (tenant_id,country,gateways,revision) VALUES (?,?,?,1) ON DUPLICATE KEY UPDATE gateways=VALUES(gateways),revision=revision+1',[$t['id'],$country,json_encode($gateways,JSON_THROW_ON_ERROR)]);
            $this->auth->audit($actor,'renewals.routing_saved',null,$t['id'],['country'=>$country,'gateways'=>$gateways]);
        });return $this->status($actor,['tenant_id'=>$t['id']]);
    }
    public function status(array $actor,array $input):array {
        Input::keys($input,['tenant_id']);$t=$this->workspace($actor,$input['tenant_id']??null);$c=$this->connection($t['id']);
        $plans=$this->db->all('SELECT id,name,country,currency,amount_minor,package_id,package_snapshot,plan_fingerprint,revision,enabled FROM renewal_plans WHERE tenant_id=? ORDER BY created_at,id',[$t['id']]);
        foreach($plans as &$p){$p['amount_minor']=(int)$p['amount_minor'];$p['currency_exponent']=RampexPaymentVerifier::exponent($p['currency']);$p['package_id']=(int)$p['package_id'];$p['revision']=(int)$p['revision'];$p['enabled']=(bool)$p['enabled'];$p['package']=json_decode($p['package_snapshot'],true,16,JSON_THROW_ON_ERROR);unset($p['package_snapshot']);}unset($p);
        $availability=$this->availability($t['id']);
        $public=fn($v)=>['id'=>$v['id'],'gateway'=>$v['gateway'],'merchant_id'=>$v['merchant_id'],'business_name'=>$v['business_name'],'mode'=>$v['mode'],'checkout_enabled'=>$availability[$v['gateway']]['enabled'],'availability_revision'=>$availability[$v['gateway']]['revision'],'api_key_saved'=>true,'webhook_secret_saved'=>true,'webhook_verified_at'=>$v['webhook_verified_at']??null,'webhook_url'=>$this->config->origin().'/api/renewals/webhooks/'.$v['id']];
        return ['tenant_id'=>$t['id'],'policy'=>$this->policy(),'currency_exponents'=>RampexPaymentVerifier::currencyExponents(),'catalogue_only'=>($this->config->get('renewal_worker',[])['mode']??null)==='catalogue_only','connection'=>$c?$public($c):null,'connections'=>array_map($public,$this->connections($t['id'])),'routing'=>$this->routing($t['id']),'prices'=>$plans,'simulation_allowed'=>$this->gateway->mockAllowed()];
    }
    public function saveConnection(array $actor,array $input):array {
        Input::keys($input,['tenant_id','api_key','webhook_secret','mode','gateway','location_id','setup_id']);$t=$this->workspace($actor,$input['tenant_id']??null);$gateway=$input['gateway']??'rampex';
        if(!in_array($gateway,['rampex','stripe','square'],true))throw new Problem(422,'Invalid payment gateway.');
        $setup=isset($input['setup_id'])?Input::id($input['setup_id']):null;
        if($setup!==null){$draft=$this->db->one('SELECT tenant_id,gateway FROM renewal_connection_setups WHERE id=?',[$setup]);if(!$draft||$draft['tenant_id']!==$t['id']||$draft['gateway']!==$gateway)throw new Problem(404,'Merchant setup not found.');}
        $key=Input::text($input['api_key']??null,'merchant API key',512);$secret=Input::text($input['webhook_secret']??null,'webhook secret',512);
        if(strlen($secret)<16||preg_match('/[\x00-\x1f\x7f]/',$secret))throw new Problem(422,'Invalid webhook secret.');
        $mode=$input['mode']??null;if(!in_array($mode,$gateway==='rampex'?['mock','live']:['sandbox','live'],true))throw new Problem(422,'Invalid payment mode.');
        $merchant=$this->gateway->validate($key,$mode,$gateway,['location_id'=>$input['location_id']??null]);
        $this->db->transaction(function()use($actor,$t,$key,$secret,$mode,$merchant,$gateway,$setup):void{
            $tenant=$this->db->one('SELECT status FROM tenants WHERE id=? FOR UPDATE',[$t['id']]);
            if(!$tenant||$tenant['status']!=='active')throw new Problem(409,'Reseller workspace is unavailable.');
            if($setup!==null){
                $draft=$this->db->one('SELECT * FROM renewal_connection_setups WHERE id=? FOR UPDATE',[$setup]);
                if(!$draft||$draft['tenant_id']!==$t['id']||$draft['gateway']!==$gateway)throw new Problem(404,'Merchant setup not found.');
                if($draft['used_at']!==null){
                    $old=$this->db->one('SELECT * FROM renewal_connections WHERE id=?',[$setup]);
                    $options=$old&&$old['provider_options_cipher']!==null?json_decode($this->secrets->decrypt($old['provider_options_cipher']),true,8,JSON_THROW_ON_ERROR):null;
                    if($old&&$old['mode']===$mode&&hash_equals($this->secrets->decrypt($old['api_cipher']),$key)&&hash_equals($this->secrets->decrypt($old['webhook_cipher']),$secret)&&$options===($merchant['options']??null))return;
                    throw new Problem(409,'This setup is already complete. Start a replacement connection instead.');
                }
            }
            $this->db->query('INSERT INTO renewal_merchants (merchant_id,tenant_id) VALUES (?,?) ON DUPLICATE KEY UPDATE merchant_id=merchant_id',[$merchant['merchant_id'],$t['id']]);
            $scope=$this->db->one('SELECT tenant_id FROM renewal_merchants WHERE merchant_id=? FOR UPDATE',[$merchant['merchant_id']]);
            if($scope['tenant_id']!==$t['id'])throw new Problem(409,'This merchant already belongs to another reseller workspace.');
            $this->db->query('UPDATE renewal_connections SET enabled=0 WHERE tenant_id=? AND gateway=?',[$t['id'],$gateway]);
            $id=$setup??Database::id();$this->db->query('INSERT INTO renewal_connections (id,tenant_id,merchant_id,business_name,api_cipher,webhook_cipher,mode,gateway,provider_options_cipher) VALUES (?,?,?,?,?,?,?,?,?)',[$id,$t['id'],$merchant['merchant_id'],$merchant['business_name'],$this->secrets->encrypt($key),$this->secrets->encrypt($secret),$mode,$gateway,isset($merchant['options'])?$this->secrets->encrypt(json_encode($merchant['options'],JSON_THROW_ON_ERROR)):null]);
            if($setup!==null)$this->db->query('UPDATE renewal_connection_setups SET used_at=UTC_TIMESTAMP() WHERE id=?',[$setup]);
            $this->auth->audit($actor,'renewals.connection_saved',$id,$t['id'],['mode'=>$mode,'gateway'=>$gateway]);
        });
        return $this->status($actor,['tenant_id'=>$t['id']]);
    }
    public function prepareConnection(array $actor,array $input):array {
        Input::keys($input,['tenant_id','gateway']);$t=$this->workspace($actor,$input['tenant_id']??null);$gateway=$input['gateway']??null;
        if(!in_array($gateway,['stripe','square','rampex'],true))throw new Problem(422,'Invalid payment gateway.');
        $id=$this->db->transaction(function()use($actor,$t,$gateway):string{
            $this->db->one('SELECT id FROM tenants WHERE id=? FOR UPDATE',[$t['id']]);
            $old=$this->db->one('SELECT id FROM renewal_connection_setups WHERE tenant_id=? AND gateway=? AND used_at IS NULL ORDER BY created_at DESC LIMIT 1 FOR UPDATE',[$t['id'],$gateway]);
            if($old)return $old['id'];
            $id=Database::id();$this->db->query('INSERT INTO renewal_connection_setups (id,tenant_id,gateway,created_by) VALUES (?,?,?,?)',[$id,$t['id'],$gateway,$actor['id']]);return $id;
        });return ['setup_id'=>$id,'webhook_url'=>$this->config->origin().'/api/renewals/webhooks/'.$id];
    }
    public function catalog(array $actor,array $input):array {
        Input::keys($input,['tenant_id']);$t=$this->workspace($actor,$input['tenant_id']??null);$r=$this->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$t['id']]);return ['packages'=>$this->worker->catalog($r['panel_id'])];
    }
    public function savePrice(array $actor,array $input):array {
        // Legacy renewal_plans storage is retained for existing order snapshots;
        // its rows now represent money prices for fixed panel packages only.
        Input::keys($input,['tenant_id','id','expected_revision','package_id','amount','currency','enabled','country']);$t=$this->workspace($actor,$input['tenant_id']??null);$country=$input['country']??'';
        if(!in_array($country,['','US','CA'],true))throw new Problem(422,'Invalid package price country.');
        $currency=Input::text($input['currency']??null,'currency',3);$minor=RampexPaymentVerifier::minor($input['amount']??null,$currency);
        $expected=Input::integer($input['expected_revision']??0,0,2147483647);
        $package=Input::integer($input['package_id']??null,1,2147483647);if(!is_bool($input['enabled']??null))throw new Problem(422,'Invalid price availability.');
        $catalog=$this->catalog($actor,['tenant_id'=>$t['id']])['packages'];$matched=null;foreach($catalog as $p)if($p['id']===$package)$matched=$p;
        if(!$matched)throw new Problem(409,'Only an offered panel package can have a customer price.');
        return $this->db->transaction(function()use($actor,$input,$t,$currency,$minor,$package,$matched,$expected,$country):array{
            $tenant=$this->db->one('SELECT status FROM tenants WHERE id=? FOR UPDATE',[$t['id']]);
            if(!$tenant||$tenant['status']!=='active')throw new Problem(409,'Workspace is unavailable.');
            $provided=isset($input['id'])&&$input['id']!==''?Input::id($input['id']):null;
            $old=$provided?$this->db->one('SELECT * FROM renewal_plans WHERE id=? FOR UPDATE',[$provided]):null;
            if($old&&$old['tenant_id']!==$t['id'])throw new Problem(404,'Package price not found.');
            if($provided&&!$old)throw new Problem(404,'Package price not found.');
            if($old&&((int)$old['package_id']!==$package||$old['country']!==$country))throw new Problem(422,'A price cannot be moved to another package or country.');
            $rows=$this->db->all('SELECT * FROM renewal_plans WHERE tenant_id=? AND package_id=? AND country=? FOR UPDATE',[$t['id'],$package,$country]);
            if(count($rows)>1)throw new Problem(409,'Duplicate historical package prices need owner review.');
            $old??=$rows[0]??null;$id=$old['id']??Database::id();
            if($expected!==(int)($old['revision']??0))throw new Problem(409,'Package price changed; refresh before saving.');
            if($old)$this->db->query('UPDATE renewal_plans SET name=?,currency=?,amount_minor=?,plan_fingerprint=?,package_snapshot=?,enabled=?,revision=revision+1 WHERE id=?',[$matched['name'],$currency,$minor,$matched['fingerprint'],json_encode($matched,JSON_THROW_ON_ERROR),(int)$input['enabled'],$id]);
            else $this->db->query('INSERT INTO renewal_plans (id,tenant_id,package_id,plan_fingerprint,package_snapshot,name,country,currency,amount_minor,enabled) VALUES (?,?,?,?,?,?,?,?,?,?)',[$id,$t['id'],$package,$matched['fingerprint'],json_encode($matched,JSON_THROW_ON_ERROR),$matched['name'],$country,$currency,$minor,(int)$input['enabled']]);
            $this->auth->audit($actor,'renewals.package_price_saved',$id,$t['id']);return $this->status($actor,['tenant_id'=>$t['id']]);
        });
    }
}
