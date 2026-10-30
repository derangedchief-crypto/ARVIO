<?php
declare(strict_types=1);
namespace ControlCenter;

/** Checkout capabilities do not grant app access. All target/merchant/price data
 * is server-owned; webhook acknowledgement and fulfilment are separate jobs. */
final class RenewalPayments {
    public function __construct(private readonly Database $db,private readonly Config $config,private readonly Auth $auth,private readonly Management $management,private readonly Secrets $secrets,private readonly BrandArtifacts $artifacts,private readonly RenewalSettings $settings,private readonly RampexGateway $gateway,private readonly RenewalWorker $worker) {}
    private function voids():RenewalCheckoutVoids {return new RenewalCheckoutVoids($this->db,$this->settings,$this->gateway,$this->auth);}
    public static function signingText(string $nonce,string $device,string $package,string $key,string $primary,?string $country=null):string {return $country===null?"ECRENEWCHECKOUT1\n$nonce\n$device\n$package\n$key\n$primary":"ECRENEWCHECKOUT2\n$nonce\n$device\n$package\n$key\n$primary\n$country";}
    private function country(mixed $value):string {if(!in_array($value,['US','CA'],true))throw new Problem(422,'Confirm USA or Canada before continuing.');return $value;}
    private function routes(array $s,array $price):array {return $this->settings->candidates($s['tenant_id'],$s['country'],$price['currency'],str_ends_with($s['app_package'],'.test'));}
    private function selectedConnection(array $s,array $price):?array {
        $routes=$this->routes($s,$price);
        foreach($routes as $c)if(($s['gateway']??null)===null||$c['gateway']===$s['gateway'])return $c;
        // A paused selection automatically returns to the reseller's ordered
        // country default, never an arbitrary gateway or a different currency.
        return isset($s['gateway'])&&!$this->settings->checkoutEnabled($s['tenant_id'],$s['gateway'])?($routes[0]??null):null;
    }
    private function enabled(string $package):bool {
        $p=$this->settings->policy();return $p['enabled']&&(!$p['testing_only']||str_ends_with($package,'.test'));
    }
    private function loginBrand(string $package,bool $requireRollout=true):array {
        if(!preg_match('/^com\.extreme\.brand\.b([a-f0-9]{32})(\.test)?$/D',$package,$m)||($requireRollout&&!$this->enabled($package)))throw new Problem(403,'Renewal checkout is unavailable.');
        $artifact=$this->artifacts->approved($m[1],isset($m[2])?'testing':'production');
        if(!$artifact||$artifact['package_name']!==$package)throw new Problem(403,'Renewal checkout is unavailable.');
        $brand=$this->db->one("SELECT b.id AS brand_id,b.tenant_id,pr.panel_id AS reseller_id FROM app_brands b JOIN tenants t ON t.id=b.tenant_id JOIN panel_resellers pr ON pr.tenant_id=b.tenant_id WHERE b.id=? AND b.enabled=1 AND t.status='active' AND t.kind IN ('reseller','owner') AND (t.kind!='owner' OR EXISTS(SELECT 1 FROM users u WHERE u.role='owner' AND u.active=1))",[$m[1]]);
        if(!$brand)throw new Problem(403,'Renewal checkout is unavailable.');
        return $brand;
    }
    public static function loginSigningText(string $nonce,string $install,string $package,string $key,array $identity,?string $country=null):string {
        $text=isset($identity['activation_code'])?"code\n".$identity['activation_code']:"account\n".hash('sha256',$identity['username'])."\n".hash('sha256',$identity['password']);
        return $country===null?"ECRENEWLOGIN1\n$nonce\n$install\n$package\n$key\n".hash('sha256',$text):"ECRENEWLOGIN2\n$nonce\n$install\n$package\n$key\n".hash('sha256',$text)."\n$country";
    }
    public function loginChallenge(array $input,string $ip):array {
        Input::keys($input,['installation_id','app_package','key_fingerprint']);
        $package=Input::text($input['app_package']??null,'package',80);
        $this->loginBrand($package);
        $install=EnrollmentProof::hex($input['installation_id']??null,32);$key=EnrollmentProof::hex($input['key_fingerprint']??null,64);
        $this->auth->limit('renewal-login-challenge',$this->secrets->mac($ip),30,600);
        $nonce=bin2hex(random_bytes(32));
        $this->db->query('INSERT INTO renewal_login_challenges (nonce_hash,installation_id,key_fingerprint,app_package,expires_at) VALUES (?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 2 MINUTE))',[hash('sha256',$nonce),$install,$key,$package]);
        return ['schema_version'=>1,'available'=>true,'challenge'=>$nonce,'expires_in'=>120];
    }
    public function loginOpen(array $input,string $ip):array {
        Input::keys($input,['challenge','installation_id','app_package','public_key','identity','signature','country']);
        $nonce=EnrollmentProof::hex($input['challenge']??null,64);$install=EnrollmentProof::hex($input['installation_id']??null,32);$package=Input::text($input['app_package']??null,'package',80);
        $brand=$this->loginBrand($package);$encoded=Input::text($input['public_key']??null,'public key',256);$public=EnrollmentProof::publicKey($encoded);$key=$public['fingerprint'];
        if(!isset($input['country']))return ['schema_version'=>1,'available'=>false,'reason'=>'country_required'];
        $country=$this->country($input['country']);
        $identity=$input['identity']??null;
        if(!is_array($identity))throw new Problem(422,'Invalid renewal login.');
        if(isset($identity['activation_code'])){
            Input::keys($identity,['activation_code']);if(!is_string($identity['activation_code'])||!preg_match('/^[0-9]{6}$/D',$identity['activation_code']))throw new Problem(422,'Invalid renewal login.');
        }else{
            Input::keys($identity,['username','password']);foreach(['username'=>256,'password'=>512] as $field=>$max)if(!is_string($identity[$field]??null)||strlen($identity[$field])<1||strlen($identity[$field])>$max)throw new Problem(422,'Invalid renewal login.');
        }
        EnrollmentProof::verifyDeviceSignature($public,Input::text($input['signature']??null,'signature',128),self::loginSigningText($nonce,$install,$package,$key,$identity,$country));
        $this->auth->limit('renewal-login-ip',$this->secrets->mac($ip),6,600);
        $this->auth->limit('renewal-login-key',$key,6,600);
        $this->db->transaction(function()use($nonce,$install,$package,$key):void{
            $row=$this->db->one('SELECT * FROM renewal_login_challenges WHERE nonce_hash=? AND expires_at>UTC_TIMESTAMP() FOR UPDATE',[hash('sha256',$nonce)]);
            if(!$row||$row['used_at']!==null||$row['installation_id']!==$install||$row['app_package']!==$package||$row['key_fingerprint']!==$key)throw new Problem(403,'Renewal login challenge expired or already used.');
            $this->db->query('UPDATE renewal_login_challenges SET used_at=UTC_TIMESTAMP() WHERE nonce_hash=?',[$row['nonce_hash']]);
        });
        $target=$this->worker->identify($brand['reseller_id'],$identity);
        $scope=$brand+$target+['app_package'=>$package,'key_fingerprint'=>$key,'login_installation_id'=>$install,'login_public_key'=>$encoded,'country'=>$country,'gateway'=>null];
        $this->loginBinding($scope);
        $eligible=$this->eligibility($scope);$price=$this->eligiblePrice($scope,$eligible);$connection=$price?$this->selectedConnection($scope,$price):null;
        if(!$price||!$connection)return ['schema_version'=>1,'available'=>false,'reason'=>!$price?'package_price_unavailable':'no_gateway_for_country'];
        $token=bin2hex(random_bytes(32));$id=Database::id();
        // No managed device, enrollment credential, playback lease or account
        // credentials are created/stored by expired-code checkout.
        $account=$this->secrets->mac("renewal-login-account-v1\n".$target['line_id']."\n".$target['primary_fingerprint']);
        $this->db->query('INSERT INTO renewal_sessions (id,token_hash,token_cipher,device_id,tenant_id,brand_id,app_package,key_fingerprint,account_ref,reseller_id,line_id,primary_fingerprint,expires_at,login_installation_id,login_public_key,country,gateway) VALUES (?,?,?,NULL,?,?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 15 MINUTE),?,?,?,?)',[$id,$this->tokenHash($token),$this->secrets->encrypt($token),$brand['tenant_id'],$brand['brand_id'],$package,$key,$account,$target['reseller_id'],$target['line_id'],$target['primary_fingerprint'],$install,$encoded,$country,$connection['gateway']]);
        return ['schema_version'=>1,'available'=>true,'checkout_url'=>$this->config->origin().'/renew/'.$token,'session_token'=>$token,'expires_in'=>900,'simulated'=>$connection['mode']!=='live','country'=>$country,'gateway'=>$connection['gateway']];
    }
    private function loginBinding(array $s,bool $requireRollout=true):array {
        $brand=$this->loginBrand($s['app_package'],$requireRollout);
        if($brand['tenant_id']!==$s['tenant_id']||$brand['brand_id']!==$s['brand_id']||$brand['reseller_id']!==$s['reseller_id']||!is_string($s['login_installation_id']??null)||!preg_match('/^[a-f0-9]{32}$/D',$s['login_installation_id'])||!is_string($s['login_public_key']??null)||!hash_equals(EnrollmentProof::publicKey($s['login_public_key'])['fingerprint'],$s['key_fingerprint']))throw new Problem(403,'Renewal checkout ownership changed.');
        $restricted=$this->db->one("SELECT d.id FROM devices d JOIN tenants t ON t.id=d.tenant_id WHERE d.app_package=? AND (d.installation_id=? OR d.key_fingerprint=?) AND (d.policy!='active' OR t.status!='active') LIMIT 1",[$s['app_package'],$s['login_installation_id'],$s['key_fingerprint']]);
        if($restricted)throw new Problem(403,'Renewal checkout is unavailable for this device.');
        return $s;
    }
    private function sessionBinding(array $s,bool $requireRollout=true):array {
        return $s['device_id']===null?$this->loginBinding($s,$requireRollout):$this->binding($s['device_id'],$s['app_package'],$s['key_fingerprint'],$requireRollout);
    }
    private function binding(string $device,string $package,?string $key=null,bool $requireRollout=true):array {
        if(!preg_match('/^com\.extreme\.brand\.b([a-f0-9]{32})(\.test)?$/D',$package,$m)||($requireRollout&&!$this->enabled($package)))throw new Problem(403,'Renewal checkout is unavailable.');
        $artifact=$this->artifacts->approved($m[1],isset($m[2])?'testing':'production');
        if(!$artifact||$artifact['package_name']!==$package)throw new Problem(403,'Renewal checkout is unavailable.');
        $r=$this->db->one("SELECT d.id,d.installation_id,d.key_fingerprint,d.app_package,d.policy,d.is_test,r.public_key,r.account_ref,p.panel_id AS line_id,p.reseller_id,b.id AS brand_id,b.tenant_id,s.name AS reseller_name FROM devices d JOIN tenants t ON t.id=d.tenant_id JOIN enrollment_requests r ON r.device_id=d.id AND r.key_fingerprint=d.key_fingerprint AND r.app_package=d.app_package AND r.status='approved' JOIN panel_customers p ON p.account_ref=r.account_ref AND p.panel_id=r.panel_customer_id AND p.reseller_id=r.panel_reseller_id JOIN app_brands b ON b.id=? JOIN tenants s ON s.id=b.tenant_id JOIN panel_resellers pr ON pr.tenant_id=b.tenant_id AND pr.panel_id=p.reseller_id WHERE d.id=? AND d.app_package=? AND d.policy='active' AND t.status='active' AND s.kind IN ('reseller','owner') AND (s.kind!='owner' OR EXISTS(SELECT 1 FROM users u WHERE u.role='owner' AND u.active=1)) AND s.status='active' AND b.enabled=1 AND p.tenant_id=b.tenant_id ORDER BY r.created_at DESC,r.id DESC LIMIT 1",[$m[1],Input::id($device),$package]);
        if(!$r||($key!==null&&!hash_equals($r['key_fingerprint'],$key))||($r['is_test']&&!$this->config->local()))throw new Problem(403,'Renewal checkout is unavailable.');
        $current=$this->db->one('SELECT tenant_id,customer_id FROM devices WHERE id=?',[$r['id']]);
        $customer=$this->db->one('SELECT customer_id FROM panel_customers WHERE panel_id=?',[$r['line_id']]);
        if(!$current||!$customer||$current['tenant_id']!==$r['tenant_id']||$current['customer_id']!==$customer['customer_id'])throw new Problem(403,'Renewal checkout ownership changed.');
        return $r;
    }
    private function eligibility(array $s):array {
        $r=$this->worker->eligibility($s['reseller_id'],$s['line_id'],$s['primary_fingerprint']);
        if($r['state']!=='expired'||($r['primary_match']??false)!==true)throw new Problem(409,'This account is not eligible for expired-line renewal.');return $r;
    }
    public function challenge(array $input,string $ip):array {
        Input::keys($input,['device_id','app_package','key_fingerprint','primary_fingerprint']);
        $package=Input::text($input['app_package']??null,'package',80);
        if(!$this->enabled($package))return ['schema_version'=>1,'available'=>false];
        $this->auth->limit('renewal-challenge',$this->secrets->mac($ip),30,60);
        $key=EnrollmentProof::hex($input['key_fingerprint']??null,64);$primary=EnrollmentProof::hex($input['primary_fingerprint']??null,64);
        $d=$this->binding(Input::id($input['device_id']??null),$package,$key);$nonce=bin2hex(random_bytes(32));
        $this->db->query('INSERT INTO renewal_challenges VALUES (?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 2 MINUTE),NULL)',[hash('sha256',$nonce),$d['id'],$key,$package,$primary]);
        return ['schema_version'=>1,'available'=>true,'challenge'=>$nonce,'expires_in'=>120];
    }
    public function open(array $input,string $ip):array {
        Input::keys($input,['challenge','device_id','app_package','key_fingerprint','primary_fingerprint','signature','country']);
        $nonce=EnrollmentProof::hex($input['challenge']??null,64);$device=Input::id($input['device_id']??null);$package=Input::text($input['app_package']??null,'package',80);
        $key=EnrollmentProof::hex($input['key_fingerprint']??null,64);$primary=EnrollmentProof::hex($input['primary_fingerprint']??null,64);
        if(!isset($input['country']))return ['schema_version'=>1,'available'=>false,'reason'=>'country_required'];$country=$this->country($input['country']);
        $d=$this->binding($device,$package,$key);$public=EnrollmentProof::publicKey($d['public_key']);
        if(!hash_equals($public['fingerprint'],$key))throw new Problem(403,'Checkout signing identity mismatch.');
        EnrollmentProof::verifyDeviceSignature($public,Input::text($input['signature']??null,'signature',128),self::signingText($nonce,$device,$package,$key,$primary,$country));
        $this->auth->limit('renewal-open',$device,30,3600);
        $this->db->transaction(function()use($nonce,$device,$package,$key,$primary):void{
            $c=$this->db->one('SELECT * FROM renewal_challenges WHERE nonce_hash=? AND expires_at>UTC_TIMESTAMP() FOR UPDATE',[hash('sha256',$nonce)]);
            if(!$c||$c['used_at']!==null||$c['device_id']!==$device||$c['app_package']!==$package||$c['key_fingerprint']!==$key||$c['primary_fingerprint']!==$primary)throw new Problem(403,'Checkout challenge expired or already used.');
            $this->db->query('UPDATE renewal_challenges SET used_at=UTC_TIMESTAMP() WHERE nonce_hash=?',[$c['nonce_hash']]);
        });
        $scope=$d+['primary_fingerprint'=>$primary,'country'=>$country,'gateway'=>null];$eligibility=$this->eligibility($scope);
        $price=$this->eligiblePrice($scope,$eligibility);$connection=$price?$this->selectedConnection($scope,$price):null;
        if(!$connection||!$price)return ['schema_version'=>1,'available'=>false];
        return $this->db->transaction(function()use($d,$package,$key,$primary,$country,$connection):array{
            $this->db->one('SELECT id FROM devices WHERE id=? FOR UPDATE',[$d['id']]);$this->binding($d['id'],$package,$key);
            $row=$this->db->one('SELECT * FROM renewal_sessions WHERE device_id=? AND brand_id=? AND primary_fingerprint=? AND key_fingerprint=? AND account_ref=? AND reseller_id=? AND line_id=? AND country=? AND gateway=? AND expires_at>UTC_TIMESTAMP() ORDER BY created_at DESC LIMIT 1',[$d['id'],$d['brand_id'],$primary,$key,$d['account_ref'],$d['reseller_id'],$d['line_id'],$country,$connection['gateway']]);
            if(!$row){
                $token=bin2hex(random_bytes(32));$id=Database::id();
                $this->db->query('INSERT INTO renewal_sessions (id,token_hash,token_cipher,device_id,tenant_id,brand_id,app_package,key_fingerprint,account_ref,reseller_id,line_id,primary_fingerprint,expires_at,country,gateway) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 15 MINUTE),?,?)',[$id,$this->tokenHash($token),$this->secrets->encrypt($token),$d['id'],$d['tenant_id'],$d['brand_id'],$package,$key,$d['account_ref'],$d['reseller_id'],$d['line_id'],$primary,$country,$connection['gateway']]);
                $row=$this->db->one('SELECT * FROM renewal_sessions WHERE id=?',[$id]);
            }else $token=$this->secrets->decrypt($row['token_cipher']);
            return ['schema_version'=>1,'available'=>true,'checkout_url'=>$this->config->origin().'/renew/'.$token,'session_token'=>$token,'expires_in'=>max(1,min(900,strtotime($row['expires_at'].' UTC')-time())),'simulated'=>$connection['mode']!=='live','country'=>$country,'gateway'=>$connection['gateway']];
        });
    }
    private function tokenHash(string $token):string {EnrollmentProof::hex($token,64);return $this->secrets->mac("renewal-checkout-token-v1\n$token");}
    private function session(string $token,bool $checkout=false):array {
        if(!$this->settings->installed())throw new Problem(404,'Checkout unavailable.');
        $s=$this->db->one('SELECT *,expires_at>UTC_TIMESTAMP() AS open FROM renewal_sessions WHERE token_hash=? AND created_at>DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY)',[$this->tokenHash($token)]);
        if(!$s)throw new Problem(404,'Checkout unavailable or expired.');
        if($checkout){
            if(!$s['open'])throw new Problem(409,'This checkout expired; return to the TV for a new QR.');
            $d=$this->sessionBinding($s);
            if($d['account_ref']!==$s['account_ref']||$d['reseller_id']!==$s['reseller_id']||$d['line_id']!==$s['line_id'])throw new Problem(409,'Account ownership changed; contact your provider.');
        }
        return $s;
    }
    private function eligiblePrice(array $s,array $eligibility):?array {
        $country=$s['country']??'';
        $rows=$this->db->all('SELECT * FROM renewal_plans WHERE tenant_id=? AND package_id=? AND country=?',[$s['tenant_id'],$eligibility['current_package_id']??0,$country]);
        if(!$rows&&$country!=='')$rows=$this->db->all("SELECT * FROM renewal_plans WHERE tenant_id=? AND package_id=? AND country=''",[$s['tenant_id'],$eligibility['current_package_id']??0]);
        // An explicitly disabled country price must not fall through to a
        // default currency/price that the reseller did not enable there.
        $rows=array_values(array_filter($rows,static fn($r)=>(bool)$r['enabled']));
        if(count($rows)!==1)return null; // Never choose the cheapest duplicate.
        $p=$rows[0];$current=$eligibility['plans'][0]??null;
        if(!$current||$current['id']!==(int)$p['package_id']||!$current['funded'])return null;
        // A reseller prices an offered PACKAGE ID, not an invented frozen plan.
        // Fresh panel terms/cost become the invoice snapshot at checkout.
        return ['id'=>$p['id'],'package_id'=>$current['id'],'name'=>$current['name'],'revision'=>(int)$p['revision'],'package_fingerprint'=>$current['fingerprint'],'package_snapshot'=>$current,'currency'=>$p['currency'],'currency_exponent'=>RampexPaymentVerifier::exponent($p['currency']),'amount_minor'=>(int)$p['amount_minor'],'duration'=>$current['duration'],'period'=>$current['period'],'max_connections'=>$current['max_connections']];
    }
    private function currentOrder(array $s):?array {
        // Timeouts alone do not retire links. Explicit provider handover or
        // disabling a gateway supersedes old attempts without deleting evidence.
        return $this->db->one("SELECT * FROM renewal_orders WHERE tenant_id=? AND line_id=? AND primary_fingerprint=? AND checkout_superseded=0 AND fulfilment_state!='fulfilled' AND payment_state IN ('creating','awaiting','link_uncertain','verification_pending','paid','review') ORDER BY created_at DESC,id DESC LIMIT 1",[$s['tenant_id'],$s['line_id'],$s['primary_fingerprint']]);
    }
    private function publicOrder(?array $o):?array {
        if(!$o)return null;$c=$this->db->one('SELECT gateway FROM renewal_connections WHERE id=?',[$o['connection_id']]);
        $disabled=$c&&!$this->settings->checkoutEnabled($o['tenant_id'],$c['gateway']);
        return ['id'=>$o['id'],'payment_state'=>$o['payment_state'],'fulfilment_state'=>$o['fulfilment_state'],'currency'=>$o['currency'],'amount_minor'=>(int)$o['amount_minor'],'payment_url'=>$o['payment_state']==='awaiting'&&$o['void_state']===null&&!$disabled&&!$o['checkout_superseded']?$o['provider_url']:null,'gateway'=>$c['gateway']??null,'gateway_disabled'=>$disabled,'country'=>$o['country']??null,'void_state'=>$o['void_state']];
    }
    public function info(string $token):array {
        $s=$this->session($token);$o=$s['order_id']?$this->db->one('SELECT * FROM renewal_orders WHERE id=?',[$s['order_id']]):$this->currentOrder($s);$seller=$this->db->one('SELECT name FROM tenants WHERE id=?',[$s['tenant_id']]);$c=$this->settings->connection($s['tenant_id'],$s['gateway']??'rampex');
        if($o&&$o['checkout_superseded'])$o=$this->currentOrder($s);
        if($o&&in_array($o['payment_state'],['not_created','expired'],true))$o=null;
        $this->auth->limit('renewal-capability',$s['id'],60,60);
        if($o){$this->db->query('UPDATE renewal_sessions SET order_id=? WHERE id=?',[$o['id'],$s['id']]);$c=$this->db->one('SELECT * FROM renewal_connections WHERE id=?',[$o['connection_id']]);}
        $renewal=null;if((!$o||$o['payment_state']==='awaiting')&&$s['open']){try{$this->session($token,true);$renewal=$this->eligiblePrice($s,$this->eligibility($s));}catch(Problem){}}
        if($renewal!==null)unset($renewal['package_snapshot']); // Wholesale credits/bouquets stay server-side.
        $gateways=$renewal&&in_array($s['country'],['US','CA'],true)?array_map(static fn($v)=>['gateway'=>$v['gateway'],'sandbox'=>$v['mode']!=='live'],$this->routes($s,$renewal)):[];
        $selected=$renewal?$this->selectedConnection($s,$renewal):null;if(!$o&&$selected)$c=$selected;
        return ['reseller'=>$seller['name']??'Your provider','target_label'=>'Existing account ending '.substr($s['line_id'],-4),'renewal'=>$renewal,'order'=>$this->publicOrder($o),'expires_at'=>$s['expires_at'],'simulated'=>$c&&$c['mode']!=='live','country'=>$s['country'],'gateway'=>$selected['gateway']??$s['gateway'],'gateways'=>$gateways];
    }
    public function selectRoute(array $input):array {
        Input::keys($input,['session_token','country','gateway','cancel_previous']);$token=Input::text($input['session_token']??null,'checkout token',64);$s=$this->session($token,true);$country=$this->country($input['country']??null);$gateway=$input['gateway']??null;
        if($gateway!==null&&!in_array($gateway,['stripe','square','rampex'],true))throw new Problem(422,'Choose an available payment gateway.');
        $this->auth->limit('renewal-route',$s['id'],10,600);
        if(isset($input['cancel_previous'])&&!is_bool($input['cancel_previous']))throw new Problem(422,'Invalid checkout cancellation consent.');
        $candidate=array_replace($s,['country'=>$country,'gateway'=>$gateway]);$eligible=$this->eligibility($candidate);$price=$this->eligiblePrice($candidate,$eligible);$connection=$price?$this->selectedConnection($candidate,$price):null;
        if(!$price||!$connection)throw new Problem(409,'The replacement gateway is not available for this country and currency.');
        // Even cached older clients sending cancel_previous must not get stuck
        // waiting on an external cancellation. Keep the field for compatibility.
        $this->voids()->handover($s,$country,$connection['gateway'],(int)$eligible['expires_at']);
        return $this->info($token);
    }
    public function checkout(array $input,string $ip):array {
        Input::keys($input,['session_token','price_id','price_revision','package_fingerprint','email']);$s=$this->session(Input::text($input['session_token']??null,'checkout token',64),true);
        $this->auth->limit('renewal-checkout',$s['id'],10,3600);$value=$input['email']??null;$email=$value===null||$value===''?null:Input::email($value);
        $eligibility=$this->eligibility($s);$plan=$this->eligiblePrice($s,$eligibility);
        if(!$plan||$plan['id']!==($input['price_id']??null)||$plan['revision']!==($input['price_revision']??null)||$plan['package_fingerprint']!==($input['package_fingerprint']??null))throw new Problem(409,'Current package or price changed or is unavailable. Refresh before paying.');
        $this->country($s['country']);$connection=$this->selectedConnection($s,$plan);if(!$connection)throw new Problem(409,'Provider payments are unavailable for this country and currency.');
        $created=$this->db->transaction(function()use($s,$plan,$email,&$connection,$eligibility):array{
            // Login checkout may precede management enrollment/panel sync, so a
            // panel_customers row is not a reliable shared invoice lock.
            $this->db->one('SELECT id FROM tenants WHERE id=? FOR UPDATE',[$s['tenant_id']]);
            $freshRoute=$this->db->one('SELECT country,gateway FROM renewal_sessions WHERE id=? FOR UPDATE',[$s['id']]);
            if(!$freshRoute||$freshRoute['country']!==$s['country']||$freshRoute['gateway']!==$s['gateway'])throw new Problem(409,'Payment route changed. Refresh before creating a checkout.');
            $connection=$this->selectedConnection($s,$plan);
            if(!$connection)throw new Problem(409,'No enabled payment gateway is available for this country and currency.');
            if($connection['gateway']!==$s['gateway']&&!$this->currentOrder($s)){
                $this->db->query("UPDATE renewal_route_switches SET gateway=?,updated_at=UTC_TIMESTAMP() WHERE tenant_id=? AND line_id=? AND primary_fingerprint=? AND state='ready' AND gateway=?",[$connection['gateway'],$s['tenant_id'],$s['line_id'],$s['primary_fingerprint'],$s['gateway']]);
                $this->db->query('UPDATE renewal_sessions SET gateway=? WHERE id=?',[$connection['gateway'],$s['id']]);$s['gateway']=$connection['gateway'];
            }
            $this->voids()->assertCheckout($s,(int)$eligibility['expires_at']);
            $this->db->one('SELECT panel_id FROM panel_customers WHERE panel_id=? FOR UPDATE',[$s['line_id']]);
            if($old=$this->currentOrder($s)){$this->db->query('UPDATE renewal_sessions SET order_id=? WHERE id=?',[$old['id'],$s['id']]);return ['created'=>false,'order'=>$old];}
            if($connection['gateway']==='rampex'&&$email===null)throw new Problem(422,'Rampex requires an email address. Select Stripe or Square to continue without entering one here.');
            if(!$this->db->one('SELECT id FROM renewal_connections WHERE id=? AND tenant_id=? AND enabled=1 FOR UPDATE',[$connection['id'],$s['tenant_id']]))throw new Problem(409,'Merchant connection changed. Refresh before paying.');
            $snapshot=$this->db->one('SELECT * FROM renewal_plans WHERE id=? AND tenant_id=? FOR UPDATE',[$plan['id'],$s['tenant_id']]);
            if(!$snapshot||!(bool)$snapshot['enabled']||(int)$snapshot['revision']!==$plan['revision']||(int)$snapshot['package_id']!==$plan['package_id'])throw new Problem(409,'Current package price changed before checkout.');
            $snapshot['name']=$plan['name'];$snapshot['plan_fingerprint']=$plan['package_fingerprint'];$snapshot['package_snapshot']=json_encode($plan['package_snapshot'],JSON_THROW_ON_ERROR);
            $id=Database::id();
            $this->db->query('INSERT INTO renewal_orders (id,session_id,tenant_id,brand_id,reseller_id,line_id,primary_fingerprint,connection_id,merchant_id,plan_id,plan_snapshot,currency,amount_minor,email_cipher,expires_at,country) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 30 MINUTE),?)',[$id,$s['id'],$s['tenant_id'],$s['brand_id'],$s['reseller_id'],$s['line_id'],$s['primary_fingerprint'],$connection['id'],$connection['merchant_id'],$plan['id'],json_encode($snapshot,JSON_THROW_ON_ERROR),$plan['currency'],$plan['amount_minor'],$this->secrets->encrypt($email??''),$s['country']]);
            $this->db->query('UPDATE renewal_orders SET quoted_expiry=? WHERE id=?',[$eligibility['expires_at'],$id]);
            $this->voids()->checkoutCreated($s);
            $this->db->query('UPDATE renewal_sessions SET order_id=? WHERE id=?',[$id,$s['id']]);
            return ['created'=>true,'order'=>$this->db->one('SELECT * FROM renewal_orders WHERE id=?',[$id])];
        });
        $o=$created['order'];
        if($created['created']){
            try{$link=$this->gateway->create($connection,$o,$email);$this->db->query("UPDATE renewal_orders SET provider_link_id=?,provider_checkout_id=?,provider_url=?,payment_state='awaiting' WHERE id=?",[$link['link_id'],$link['checkout_id']??null,$link['url'],$o['id']]);}
            catch(RampexLinkRejected $error){$this->db->query("UPDATE renewal_orders SET payment_state='not_created' WHERE id=?",[$o['id']]);throw new Problem(422,$error->getMessage());}
            catch(\Throwable){$this->db->query("UPDATE renewal_orders SET payment_state='link_uncertain' WHERE id=?",[$o['id']]);throw new Problem(503,'Payment link preparation is uncertain. Keep this order and contact your provider; do not pay again.');}
        }
        $fresh=$this->db->one('SELECT * FROM renewal_orders WHERE id=?',[$o['id']]);
        if($fresh['checkout_superseded'])return $this->checkout($input,$ip);
        return ['order'=>$this->publicOrder($fresh)];
    }
    public function status(string $token):array {
        $s=$this->session($token);$o=$s['order_id']?$this->db->one('SELECT * FROM renewal_orders WHERE id=?',[$s['order_id']]):$this->currentOrder($s);
        if($o&&$o['checkout_superseded'])$o=$this->currentOrder($s);
        if($o&&in_array($o['payment_state'],['not_created','expired'],true))$o=null;
        $this->auth->limit('renewal-capability',$s['id'],60,60);
        $c=$o?$this->db->one('SELECT mode FROM renewal_connections WHERE id=?',[$o['connection_id']]):null;
        $disabled=array_keys(array_filter($this->settings->availability($s['tenant_id']),static fn($v)=>!$v['enabled']));
        return ['schema_version'=>1,'order'=>$this->publicOrder($o),'simulated'=>$c&&$c['mode']!=='live','disabled_gateways'=>$disabled];
    }
    public function webhook(string $connectionId,string $raw,string $signature):array {
        $c=$this->db->one('SELECT * FROM renewal_connections WHERE id=?',[Input::id($connectionId)])??throw new Problem(401,'Invalid payment notification.');
        $e=$this->gateway->event($c,$raw,$signature);
        $this->db->query('UPDATE renewal_connections SET webhook_verified_at=UTC_TIMESTAMP() WHERE id=?',[$c['id']]);
        $link=$e['link_id']??null;$o=null;
        if(is_string($link)&&preg_match('/^[A-Za-z0-9._:-]{1,200}$/D',$link))$o=$this->db->one('SELECT id,connection_id FROM renewal_orders WHERE tenant_id=? AND merchant_id=? AND provider_link_id=?',[$c['tenant_id'],$c['merchant_id'],$link]);
        if(!$o&&is_string($link)&&preg_match('/^[A-Za-z0-9._:-]{1,200}$/D',$link)){
            // Recover a lost link-creation reply only through a signed provider
            // order reference; the job still verifies the link via merchant API.
            $ref=self::providerOrderRef($e);
            if($ref===null&&$c['gateway']==='square'){
                $active=$this->settings->connection($c['tenant_id'],'square');$reader=$active&&$active['merchant_id']===$c['merchant_id']&&$active['mode']===$c['mode']?array_replace($c,['api_cipher'=>$active['api_cipher']]):$c;
                $ref=$this->gateway->orderReference($reader,$e);
            }
            if(is_string($ref)&&preg_match('/^[a-f0-9]{32}$/D',$ref))$o=$this->db->one("SELECT id,connection_id FROM renewal_orders WHERE id=? AND tenant_id=? AND merchant_id=? AND provider_link_id IS NULL AND payment_state IN ('creating','link_uncertain','review')",[$ref,$c['tenant_id'],$c['merchant_id']]);
        }
        if(!$o){$this->auth->audit(null,'renewals.webhook_ignored',$c['id'],$c['tenant_id'],['body_sha256'=>hash('sha256',$raw)]);return ['accepted'=>true,'ignored'=>true];}
        $originalConnection=$this->db->one('SELECT gateway,mode FROM renewal_connections WHERE id=?',[$o['connection_id']]);
        if(!$originalConnection||$originalConnection['gateway']!==$c['gateway']||$originalConnection['mode']!==$c['mode'])throw new Problem(403,'Notification gateway or environment differs from the original invoice.');
        $this->db->query('INSERT INTO renewal_events (id,connection_id,order_id,body_sha256,body_cipher,signature) VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id',[Database::id(),$c['id'],$o['id'],hash('sha256',$raw),$this->secrets->encrypt($raw),$signature]);
        return ['accepted'=>true]; // Known links need no network call. Lost Square replies recover only an authenticated opaque order reference; never renew here.
    }
    private static function providerOrderRef(array $event):?string {
        $metadata=is_array($event['metadata']??null)?$event['metadata']:[];
        $value=$event['woo_order_id']??$metadata['order_id']??null;
        return is_string($value)&&preg_match('/^[a-f0-9]{32}$/D',$value)?$value:null;
    }
    public function simulate(array $actor,string $id):array {
        if(!$this->gateway->mockAllowed())throw new Problem(404,'Simulation unavailable.');
        $o=$this->ownedOrder($actor,$id);$c=$this->db->one('SELECT * FROM renewal_connections WHERE id=?',[$o['connection_id']]);
        if($c['mode']!=='mock'||!$o['provider_link_id'])throw new Problem(409,'Not a prepared demo order.');
        $this->db->query("UPDATE renewal_simulations SET status='completed' WHERE link_id=? AND merchant_id=?",[$o['provider_link_id'],$o['merchant_id']]);
        $e=RampexPaymentVerifier::exponent($o['currency']);
        $raw=json_encode(['event'=>'payment.completed','status'=>'completed','link_id'=>$o['provider_link_id'],'currency'=>$o['currency'],'amount'=>number_format($o['amount_minor']/(10**$e),$e,'.',''),'txid_out'=>'0x'.hash('sha256',$o['id']),'paid_at'=>gmdate('Y-m-d\TH:i:s\Z')],JSON_THROW_ON_ERROR);
        return $this->webhook($c['id'],$raw,hash_hmac('sha256',$raw,$this->secrets->decrypt($c['webhook_cipher'])));
    }
    private function ownedOrder(array $actor,string $id):array {
        $o=$this->db->one('SELECT * FROM renewal_orders WHERE id=?',[Input::id($id)])??throw new Problem(404,'Order not found.');$this->management->tenant($actor,$o['tenant_id']);return $o;
    }
    public function orders(array $actor,array $input):array {
        Input::keys($input,['tenant_id']);$t=$this->settings->workspace($actor,$input['tenant_id']??null);
        return ['items'=>$this->db->all('SELECT o.id,o.payment_state,o.fulfilment_state,o.checkout_superseded,o.currency,o.amount_minor,o.created_at,o.paid_at,c.mode FROM renewal_orders o JOIN renewal_connections c ON c.id=o.connection_id WHERE o.tenant_id=? ORDER BY o.created_at DESC,o.id LIMIT 50',[$t['id']]),'simulation_allowed'=>$this->gateway->mockAllowed()];
    }
    public function retry(array $actor,string $id):array {
        $o=$this->ownedOrder($actor,$id);
        if($o['checkout_superseded'])throw new Problem(409,'This invoice was superseded when its gateway was disabled. Reconcile any received funds manually; do not renew it again.');
        if($o['fulfilment_state']==='fulfilled')throw new Problem(409,'A fulfilled invoice cannot be renewed again.');
        if($o['payment_state']!=='paid'){
            if(!in_array($o['payment_state'],['review','verification_pending'],true)||!$this->db->one("SELECT id FROM renewal_events WHERE order_id=? AND state IN ('review','pending','processing') LIMIT 1",[$id]))throw new Problem(409,'Only the original received payment evidence may be rechecked.');
            $this->db->transaction(function()use($id):void{
                $row=$this->db->one('SELECT id,lease_until>UTC_TIMESTAMP() AS busy FROM renewal_orders WHERE id=? FOR UPDATE',[$id]);
                if(!$row||$row['busy'])throw new Problem(409,'This invoice is already being verified; retry later.');
                $this->db->query("UPDATE renewal_events SET state='pending' WHERE order_id=? AND state='review'",[$id]);
                $this->db->query('UPDATE renewal_orders SET next_attempt_at=UTC_TIMESTAMP() WHERE id=?',[$id]);
            });
            $this->auth->audit($actor,'renewals.same_payment_recheck',$id,$o['tenant_id']);return ['queued'=>true,'new_charge'=>false];
        }
        $this->db->query("UPDATE renewal_orders SET fulfilment_state='pending',next_attempt_at=UTC_TIMESTAMP() WHERE id=? AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP())",[$id]);
        $this->auth->audit($actor,'renewals.same_order_retry',$id,$o['tenant_id']);return ['queued'=>true,'new_charge'=>false];
    }
    public function processOne():array {
        if(!$this->settings->installed())return ['worked'=>false];
        $claim=$this->db->transaction(function():?array{
            $o=$this->db->one("SELECT o.* FROM renewal_orders o WHERE (o.lease_until IS NULL OR o.lease_until<UTC_TIMESTAMP()) AND (o.next_attempt_at IS NULL OR o.next_attempt_at<=UTC_TIMESTAMP()) AND (EXISTS(SELECT 1 FROM renewal_events e WHERE e.order_id=o.id AND e.state IN ('pending','processing')) OR (o.payment_state='paid' AND o.fulfilment_state IN ('pending','refresh_pending','reconcile_pending'))) ORDER BY o.created_at,o.id LIMIT 1 FOR UPDATE SKIP LOCKED");
            if(!$o)return null;$lease=bin2hex(random_bytes(32));$this->db->query('UPDATE renewal_orders SET lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 90 SECOND) WHERE id=?',[$lease,$o['id']]);$o['lease_token']=$lease;return $o;
        });
        if(!$claim)return ['worked'=>false];$o=$claim;$lease=$o['lease_token'];
        try{
            $c=$this->db->one('SELECT * FROM renewal_connections WHERE id=?',[$o['connection_id']]);
            $event=$this->db->one("SELECT * FROM renewal_events WHERE order_id=? AND state IN ('pending','processing') ORDER BY created_at,id LIMIT 1",[$o['id']]);
            if($event){
                $sender=$this->db->one('SELECT * FROM renewal_connections WHERE id=?',[$event['connection_id']]);
                if(!$sender||$sender['tenant_id']!==$o['tenant_id']||$sender['merchant_id']!==$o['merchant_id']||$sender['gateway']!==$c['gateway']||$sender['mode']!==$c['mode'])throw new Problem(409,'Payment notification belongs to a different merchant, gateway or environment.');
                $raw=$this->secrets->decrypt($event['body_cipher']);$payload=$this->gateway->event($sender,$raw,$event['signature'],false);
                if(($payload['event']??null)==='payment.expired'){
                    if($o['payment_state']!=='paid'){
                        if($o['provider_link_id']===null){
                            $candidate=$payload['link_id']??null;
                            if(!is_string($candidate)||!preg_match('/^[A-Za-z0-9._:-]{1,200}$/D',$candidate)||self::providerOrderRef($payload)!==$o['id'])throw new Problem(409,'Original expired link is unresolved.');
                            $o['provider_link_id']=$candidate;
                        }
                        $active=$this->settings->connection($o['tenant_id'],$c['gateway']);$reader=$active&&$active['merchant_id']===$o['merchant_id']&&$active['mode']===$c['mode']?$active:$c;
                        $status=$this->gateway->status($reader,$o);
                        if(($status['link_id']??null)!==$o['provider_link_id']||($status['merchant_id']??null)!==$o['merchant_id']||($status['status']??null)!=='expired')throw new Problem(503,'Provider has not confirmed unpaid link expiry; retain the original order.');
                        $this->db->query("UPDATE renewal_orders SET provider_link_id=COALESCE(provider_link_id,?),payment_state='expired',fulfilment_state='not_paid' WHERE id=? AND lease_token=? AND payment_state!='paid' AND fulfilment_state!='fulfilled'",[$o['provider_link_id'],$o['id'],$lease]);
                    }
                    $this->db->query("UPDATE renewal_events SET state='ignored' WHERE id=?",[$event['id']]);
                }
                elseif(($payload['event']??null)!=='payment.completed'){$this->db->query("UPDATE renewal_events SET state='ignored' WHERE id=?",[$event['id']]);}
                else{
                    if($o['provider_link_id']===null){
                        $candidate=$payload['link_id']??null;
                        $ref=self::providerOrderRef($payload);
                        if($ref===null&&$sender['gateway']==='square'){
                            $active=$this->settings->connection($o['tenant_id'],'square');$reader=$active&&$active['merchant_id']===$o['merchant_id']&&$active['mode']===$c['mode']?array_replace($c,['api_cipher'=>$active['api_cipher']]):$c;
                            $ref=$this->gateway->orderReference($reader,$payload);
                        }
                        if(!is_string($candidate)||!preg_match('/^[A-Za-z0-9._:-]{1,200}$/D',$candidate)||$ref!==$o['id'])throw new Problem(409,'Original payment-link identity is unresolved.');
                        $o['provider_link_id']=$candidate;
                    }
                    $active=$this->settings->connection($o['tenant_id'],$c['gateway']);$reader=$active&&$active['merchant_id']===$o['merchant_id']&&$active['mode']===$c['mode']?$active:$c;
                    $receipt=$this->gateway->completed($sender,$raw,$event['signature'],$o,$this->gateway->status($reader,$o));
                    $this->db->transaction(function()use($o,$lease,$receipt,$event,$payload):void{
                        $current=$this->db->one('SELECT * FROM renewal_orders WHERE id=? AND lease_token=? FOR UPDATE',[$o['id'],$lease]);if(!$current)throw new Problem(409,'Order lease changed.');
                        if($current['provider_link_id']!==null&&$current['provider_link_id']!==$o['provider_link_id'])throw new Problem(409,'Order already has another payment link.');
                        $conflict=$this->db->one('SELECT id FROM renewal_orders WHERE connection_id=? AND provider_link_id=? FOR UPDATE',[$o['connection_id'],$o['provider_link_id']]);
                        if($conflict&&$conflict['id']!==$o['id'])throw new Problem(409,'Payment link is already assigned.');
                        if($current['provider_link_id']===null)$this->db->query('UPDATE renewal_orders SET provider_link_id=? WHERE id=?',[$o['provider_link_id'],$o['id']]);
                        $old=$this->db->one('SELECT order_id FROM renewal_settlements WHERE merchant_id=? AND (link_id=? OR settlement_ref=?) FOR UPDATE',[$o['merchant_id'],$o['provider_link_id'],$receipt['settlement_ref']]);
                        if($old&&$old['order_id']!==$o['id'])throw new Problem(409,'Payment is already attached to another renewal.');
                        if(!$old)$this->db->query('INSERT INTO renewal_settlements VALUES (?,?,?,?)',[$o['merchant_id'],$o['provider_link_id'],$o['id'],$receipt['settlement_ref']]);
                        // Local QR/quote expiry cannot cancel money the provider
                        // accepted. Honour this verified immutable quote once;
                        // current ownership/package checks still gate fulfilment.
                        if($current['checkout_superseded'])$this->db->query("UPDATE renewal_orders SET payment_state='paid',paid_at=COALESCE(paid_at,UTC_TIMESTAMP()),fulfilment_state='needs_review' WHERE id=?",[$o['id']]);
                        elseif($current['payment_state']!=='paid')$this->db->query("UPDATE renewal_orders SET payment_state='paid',paid_at=UTC_TIMESTAMP(),fulfilment_state='pending' WHERE id=?",[$o['id']]);
                        $this->db->query("UPDATE renewal_events SET state='verified' WHERE id=?",[$event['id']]);
                    });
                }
            }
            $o=$this->db->one('SELECT * FROM renewal_orders WHERE id=?',[$o['id']]);
            if($o['payment_state']==='paid'&&in_array($o['fulfilment_state'],['pending','refresh_pending','reconcile_pending'],true)){
                // Pausing NEW checkout does not abandon an already verified paid
                // invoice. All account/device/tenant/primary restrictions remain.
                $s=$this->db->one('SELECT * FROM renewal_sessions WHERE id=?',[$o['session_id']]);$d=$this->sessionBinding($s,false);
                if($d['account_ref']!==$s['account_ref']||$d['line_id']!==$o['line_id']||$d['reseller_id']!==$o['reseller_id'])throw new Problem(409,'Paid order ownership changed.');
                if($c['mode']==='sandbox'){
                    $this->db->query("UPDATE renewal_orders SET fulfilment_state='fulfilled',receipt=? WHERE id=? AND lease_token=?",[json_encode(['simulated'=>true,'no_line_change'=>true]),$o['id'],$lease]);
                    return ['worked'=>true,'sandbox'=>true];
                }
                $p=json_decode($o['plan_snapshot'],true,16,JSON_THROW_ON_ERROR);
                if(!is_numeric($o['quoted_expiry']??null)||(int)$o['quoted_expiry']<1)throw new Problem(409,'Original invoice expiry revision is unavailable; retain this paid order for review.');
                $r=$this->worker->renew(['operation_id'=>$o['id'],'brand_id'=>$o['brand_id'],'reseller_id'=>$o['reseller_id'],'line_id'=>$o['line_id'],'package_id'=>(int)$p['package_id'],'plan_fingerprint'=>$p['plan_fingerprint'],'merchant_id'=>$o['merchant_id'],'payment_link_id'=>$o['provider_link_id'],'primary_fingerprint'=>$o['primary_fingerprint'],'expected_expiry'=>(int)$o['quoted_expiry']]);
                $this->db->query('UPDATE renewal_orders SET fulfilment_state=?,receipt=?,next_attempt_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 MINUTE) WHERE id=? AND lease_token=?',[$r['state'],json_encode($r,JSON_THROW_ON_ERROR),$o['id'],$lease]);
            }
        }catch(Problem $error){
            // Preserve money/order evidence. No new charge and no different operation.
            if(in_array($error->status,[401,403,409,422],true)){
                $this->db->query("UPDATE renewal_orders SET fulfilment_state=IF(fulfilment_state='fulfilled','fulfilled','needs_review'),payment_state=IF(payment_state='paid','paid','review') WHERE id=? AND lease_token=?",[$o['id'],$lease]);
                if(isset($event['id']))$this->db->query("UPDATE renewal_events e JOIN renewal_orders o ON o.id=e.order_id SET e.state='review' WHERE e.id=? AND o.lease_token=?",[$event['id'],$lease]);
            }
            if($error->status===503&&isset($event['id'])&&($payload['event']??null)==='payment.completed')$this->db->query("UPDATE renewal_orders SET payment_state='verification_pending' WHERE id=? AND lease_token=? AND payment_state='awaiting'",[$o['id'],$lease]);
            $this->db->query('UPDATE renewal_orders SET next_attempt_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 5 MINUTE) WHERE id=? AND lease_token=?',[$o['id'],$lease]);
        }catch(\Throwable){
            // Corrupt/local evidence must not block every later customer's job
            // or become a second charge. Preserve it for private operator review.
            $this->db->query("UPDATE renewal_orders SET fulfilment_state=IF(fulfilment_state='fulfilled','fulfilled','needs_review'),payment_state=IF(payment_state='paid','paid','review') WHERE id=? AND lease_token=?",[$o['id'],$lease]);
            if(isset($event['id']))$this->db->query("UPDATE renewal_events e JOIN renewal_orders o ON o.id=e.order_id SET e.state='review' WHERE e.id=? AND o.lease_token=?",[$event['id'],$lease]);
        }finally{$this->db->query('UPDATE renewal_orders SET lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?',[$o['id'],$lease]);}
        return ['worked'=>true,'order_id'=>$o['id']];
    }
}
