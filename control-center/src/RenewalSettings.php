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
    public function connection(string $tenant):?array {return $this->db->one('SELECT * FROM renewal_connections WHERE tenant_id=? AND enabled=1 ORDER BY created_at DESC,id DESC LIMIT 1',[$tenant]);}
    public function status(array $actor,array $input):array {
        Input::keys($input,['tenant_id']);$t=$this->workspace($actor,$input['tenant_id']??null);$c=$this->connection($t['id']);
        $plans=$this->db->all('SELECT id,name,currency,amount_minor,package_id,package_snapshot,plan_fingerprint,revision,enabled FROM renewal_plans WHERE tenant_id=? ORDER BY created_at,id',[$t['id']]);
        foreach($plans as &$p){$p['amount_minor']=(int)$p['amount_minor'];$p['package_id']=(int)$p['package_id'];$p['revision']=(int)$p['revision'];$p['enabled']=(bool)$p['enabled'];$p['package']=json_decode($p['package_snapshot'],true,16,JSON_THROW_ON_ERROR);unset($p['package_snapshot']);}unset($p);
        return ['tenant_id'=>$t['id'],'policy'=>$this->policy(),'catalogue_only'=>($this->config->get('renewal_worker',[])['mode']??null)==='catalogue_only','connection'=>$c?['id'=>$c['id'],'merchant_id'=>$c['merchant_id'],'business_name'=>$c['business_name'],'mode'=>$c['mode'],'api_key_saved'=>true,'webhook_secret_saved'=>true,'webhook_url'=>$this->config->origin().'/api/renewals/webhooks/'.$c['id']]:null,'prices'=>$plans,'simulation_allowed'=>$this->gateway->mockAllowed()];
    }
    public function saveConnection(array $actor,array $input):array {
        Input::keys($input,['tenant_id','api_key','webhook_secret','mode']);$t=$this->workspace($actor,$input['tenant_id']??null);
        $key=Input::text($input['api_key']??null,'merchant API key',512);$secret=Input::text($input['webhook_secret']??null,'webhook secret',512);
        if(strlen($secret)<16||preg_match('/[\x00-\x1f\x7f]/',$secret))throw new Problem(422,'Invalid webhook secret.');
        $mode=$input['mode']??null;if(!in_array($mode,['mock','live'],true))throw new Problem(422,'Invalid payment mode.');
        $merchant=$this->gateway->validate($key,$mode);
        $this->db->transaction(function()use($actor,$t,$key,$secret,$mode,$merchant):void{
            $tenant=$this->db->one('SELECT status FROM tenants WHERE id=? FOR UPDATE',[$t['id']]);
            if(!$tenant||$tenant['status']!=='active')throw new Problem(409,'Reseller workspace is unavailable.');
            $this->db->query('INSERT INTO renewal_merchants (merchant_id,tenant_id) VALUES (?,?) ON DUPLICATE KEY UPDATE merchant_id=merchant_id',[$merchant['merchant_id'],$t['id']]);
            $scope=$this->db->one('SELECT tenant_id FROM renewal_merchants WHERE merchant_id=? FOR UPDATE',[$merchant['merchant_id']]);
            if($scope['tenant_id']!==$t['id'])throw new Problem(409,'This merchant already belongs to another reseller workspace.');
            $this->db->query('UPDATE renewal_connections SET enabled=0 WHERE tenant_id=?',[$t['id']]);
            $id=Database::id();$this->db->query('INSERT INTO renewal_connections (id,tenant_id,merchant_id,business_name,api_cipher,webhook_cipher,mode) VALUES (?,?,?,?,?,?,?)',[$id,$t['id'],$merchant['merchant_id'],$merchant['business_name'],$this->secrets->encrypt($key),$this->secrets->encrypt($secret),$mode]);
            $this->auth->audit($actor,'renewals.connection_saved',$id,$t['id'],['mode'=>$mode]);
        });
        return $this->status($actor,['tenant_id'=>$t['id']]);
    }
    public function catalog(array $actor,array $input):array {
        Input::keys($input,['tenant_id']);$t=$this->workspace($actor,$input['tenant_id']??null);$r=$this->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$t['id']]);return ['packages'=>$this->worker->catalog($r['panel_id'])];
    }
    public function savePrice(array $actor,array $input):array {
        // Legacy renewal_plans storage is retained for existing order snapshots;
        // its rows now represent money prices for fixed panel packages only.
        Input::keys($input,['tenant_id','id','expected_revision','package_id','amount','currency','enabled']);$t=$this->workspace($actor,$input['tenant_id']??null);
        $currency=Input::text($input['currency']??null,'currency',3);$minor=RampexPaymentVerifier::minor($input['amount']??null,$currency);
        $expected=Input::integer($input['expected_revision']??0,0,2147483647);
        $package=Input::integer($input['package_id']??null,1,2147483647);if(!is_bool($input['enabled']??null))throw new Problem(422,'Invalid price availability.');
        $catalog=$this->catalog($actor,['tenant_id'=>$t['id']])['packages'];$matched=null;foreach($catalog as $p)if($p['id']===$package)$matched=$p;
        if(!$matched)throw new Problem(409,'Only an offered panel package can have a customer price.');
        return $this->db->transaction(function()use($actor,$input,$t,$currency,$minor,$package,$matched,$expected):array{
            $tenant=$this->db->one('SELECT status FROM tenants WHERE id=? FOR UPDATE',[$t['id']]);
            if(!$tenant||$tenant['status']!=='active')throw new Problem(409,'Workspace is unavailable.');
            $provided=isset($input['id'])&&$input['id']!==''?Input::id($input['id']):null;
            $old=$provided?$this->db->one('SELECT * FROM renewal_plans WHERE id=? FOR UPDATE',[$provided]):null;
            if($old&&$old['tenant_id']!==$t['id'])throw new Problem(404,'Package price not found.');
            if($provided&&!$old)throw new Problem(404,'Package price not found.');
            if($old&&(int)$old['package_id']!==$package)throw new Problem(422,'A price cannot be moved to another package.');
            $rows=$this->db->all('SELECT * FROM renewal_plans WHERE tenant_id=? AND package_id=? FOR UPDATE',[$t['id'],$package]);
            if(count($rows)>1)throw new Problem(409,'Duplicate historical package prices need owner review.');
            $old??=$rows[0]??null;$id=$old['id']??Database::id();
            if($expected!==(int)($old['revision']??0))throw new Problem(409,'Package price changed; refresh before saving.');
            if($old)$this->db->query('UPDATE renewal_plans SET name=?,currency=?,amount_minor=?,plan_fingerprint=?,package_snapshot=?,enabled=?,revision=revision+1 WHERE id=?',[$matched['name'],$currency,$minor,$matched['fingerprint'],json_encode($matched,JSON_THROW_ON_ERROR),(int)$input['enabled'],$id]);
            else $this->db->query('INSERT INTO renewal_plans (id,tenant_id,package_id,plan_fingerprint,package_snapshot,name,currency,amount_minor,enabled) VALUES (?,?,?,?,?,?,?,?,?)',[$id,$t['id'],$package,$matched['fingerprint'],json_encode($matched,JSON_THROW_ON_ERROR),$matched['name'],$currency,$minor,(int)$input['enabled']]);
            $this->auth->audit($actor,'renewals.package_price_saved',$id,$t['id']);return $this->status($actor,['tenant_id'=>$t['id']]);
        });
    }
}
