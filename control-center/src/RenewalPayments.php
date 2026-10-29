<?php
declare(strict_types=1);
namespace ControlCenter;

/** Checkout capabilities do not grant app access. All target/merchant/price data
 * is server-owned; webhook acknowledgement and fulfilment are separate jobs. */
final class RenewalPayments {
    public function __construct(private readonly Database $db,private readonly Config $config,private readonly Auth $auth,private readonly Management $management,private readonly Secrets $secrets,private readonly BrandArtifacts $artifacts,private readonly RenewalSettings $settings,private readonly RampexGateway $gateway,private readonly RenewalWorker $worker) {}
    public static function signingText(string $nonce,string $device,string $package,string $key,string $primary):string {return "ECRENEWCHECKOUT1\n$nonce\n$device\n$package\n$key\n$primary";}
    private function enabled(string $package):bool {
        $p=$this->settings->policy();return $p['enabled']&&(!$p['testing_only']||str_ends_with($package,'.test'));
    }
    private function binding(string $device,string $package,?string $key=null):array {
        if(!preg_match('/^com\.extreme\.brand\.b([a-f0-9]{32})(\.test)?$/D',$package,$m)||!$this->enabled($package))throw new Problem(403,'Renewal checkout is unavailable.');
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
        Input::keys($input,['challenge','device_id','app_package','key_fingerprint','primary_fingerprint','signature']);
        $nonce=EnrollmentProof::hex($input['challenge']??null,64);$device=Input::id($input['device_id']??null);$package=Input::text($input['app_package']??null,'package',80);
        $key=EnrollmentProof::hex($input['key_fingerprint']??null,64);$primary=EnrollmentProof::hex($input['primary_fingerprint']??null,64);
        $d=$this->binding($device,$package,$key);$public=EnrollmentProof::publicKey($d['public_key']);
        if(!hash_equals($public['fingerprint'],$key))throw new Problem(403,'Checkout signing identity mismatch.');
        EnrollmentProof::verifyDeviceSignature($public,Input::text($input['signature']??null,'signature',128),self::signingText($nonce,$device,$package,$key,$primary));
        $this->auth->limit('renewal-open',$device,30,3600);
        $this->db->transaction(function()use($nonce,$device,$package,$key,$primary):void{
            $c=$this->db->one('SELECT * FROM renewal_challenges WHERE nonce_hash=? AND expires_at>UTC_TIMESTAMP() FOR UPDATE',[hash('sha256',$nonce)]);
            if(!$c||$c['used_at']!==null||$c['device_id']!==$device||$c['app_package']!==$package||$c['key_fingerprint']!==$key||$c['primary_fingerprint']!==$primary)throw new Problem(403,'Checkout challenge expired or already used.');
            $this->db->query('UPDATE renewal_challenges SET used_at=UTC_TIMESTAMP() WHERE nonce_hash=?',[$c['nonce_hash']]);
        });
        $scope=$d+['primary_fingerprint'=>$primary];$eligibility=$this->eligibility($scope);
        $plans=$this->eligiblePlans($scope,$eligibility);$connection=$this->settings->connection($d['tenant_id']);
        if(!$connection||!$plans)return ['schema_version'=>1,'available'=>false];
        return $this->db->transaction(function()use($d,$package,$key,$primary):array{
            $this->db->one('SELECT id FROM devices WHERE id=? FOR UPDATE',[$d['id']]);$this->binding($d['id'],$package,$key);
            $row=$this->db->one('SELECT * FROM renewal_sessions WHERE device_id=? AND brand_id=? AND primary_fingerprint=? AND key_fingerprint=? AND account_ref=? AND reseller_id=? AND line_id=? AND expires_at>UTC_TIMESTAMP() ORDER BY created_at DESC LIMIT 1',[$d['id'],$d['brand_id'],$primary,$key,$d['account_ref'],$d['reseller_id'],$d['line_id']]);
            if(!$row){
                $token=bin2hex(random_bytes(32));$id=Database::id();
                $this->db->query('INSERT INTO renewal_sessions (id,token_hash,token_cipher,device_id,tenant_id,brand_id,app_package,key_fingerprint,account_ref,reseller_id,line_id,primary_fingerprint,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 15 MINUTE))',[$id,$this->tokenHash($token),$this->secrets->encrypt($token),$d['id'],$d['tenant_id'],$d['brand_id'],$package,$key,$d['account_ref'],$d['reseller_id'],$d['line_id'],$primary]);
                $row=$this->db->one('SELECT * FROM renewal_sessions WHERE id=?',[$id]);
            }else $token=$this->secrets->decrypt($row['token_cipher']);
            $connection=$this->settings->connection($d['tenant_id']);
            return ['schema_version'=>1,'available'=>true,'checkout_url'=>$this->config->origin().'/renew/'.$token,'session_token'=>$token,'expires_in'=>max(1,min(900,strtotime($row['expires_at'].' UTC')-time())),'simulated'=>$connection['mode']==='mock'];
        });
    }
    private function tokenHash(string $token):string {EnrollmentProof::hex($token,64);return $this->secrets->mac("renewal-checkout-token-v1\n$token");}
    private function session(string $token,bool $checkout=false):array {
        if(!$this->settings->installed())throw new Problem(404,'Checkout unavailable.');
        $s=$this->db->one('SELECT *,expires_at>UTC_TIMESTAMP() AS open FROM renewal_sessions WHERE token_hash=? AND created_at>DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY)',[$this->tokenHash($token)]);
        if(!$s)throw new Problem(404,'Checkout unavailable or expired.');
        if($checkout){
            if(!$s['open'])throw new Problem(409,'This checkout expired; return to the TV for a new QR.');
            $d=$this->binding($s['device_id'],$s['app_package'],$s['key_fingerprint']);
            if($d['account_ref']!==$s['account_ref']||$d['reseller_id']!==$s['reseller_id']||$d['line_id']!==$s['line_id'])throw new Problem(409,'Account ownership changed; contact your provider.');
        }
        return $s;
    }
    private function eligiblePlans(array $s,array $eligibility):array {
        $rows=$this->db->all('SELECT * FROM renewal_plans WHERE tenant_id=? AND enabled=1 ORDER BY amount_minor,id',[$s['tenant_id']]);$allowed=[];
        foreach($rows as $p)foreach($eligibility['plans'] as $current)if($current['id']===(int)$p['package_id']&&hash_equals($current['fingerprint'],$p['plan_fingerprint'])&&$current['funded']){
            $allowed[]=['id'=>$p['id'],'name'=>$p['name'],'revision'=>(int)$p['revision'],'currency'=>$p['currency'],'amount_minor'=>(int)$p['amount_minor'],'duration'=>$current['duration'],'period'=>$current['period'],'max_connections'=>$current['max_connections']];break;
        }
        return $allowed;
    }
    private function currentOrder(array $s):?array {
        // A local quote timeout does not cancel a provider payment link. Keep
        // unresolved orders blocking across brands/devices until reconciled.
        return $this->db->one("SELECT * FROM renewal_orders WHERE tenant_id=? AND line_id=? AND primary_fingerprint=? AND fulfilment_state!='fulfilled' AND payment_state IN ('creating','awaiting','link_uncertain','verification_pending','paid','review') ORDER BY created_at DESC,id DESC LIMIT 1",[$s['tenant_id'],$s['line_id'],$s['primary_fingerprint']]);
    }
    private function publicOrder(?array $o):?array {return $o?['id'=>$o['id'],'payment_state'=>$o['payment_state'],'fulfilment_state'=>$o['fulfilment_state'],'currency'=>$o['currency'],'amount_minor'=>(int)$o['amount_minor'],'payment_url'=>$o['payment_state']==='awaiting'?$o['provider_url']:null]:null;}
    public function info(string $token):array {
        $s=$this->session($token);$o=$s['order_id']?$this->db->one('SELECT * FROM renewal_orders WHERE id=?',[$s['order_id']]):$this->currentOrder($s);$seller=$this->db->one('SELECT name FROM tenants WHERE id=?',[$s['tenant_id']]);$c=$this->settings->connection($s['tenant_id']);
        $this->auth->limit('renewal-capability',$s['id'],60,60);
        if($o){$this->db->query('UPDATE renewal_sessions SET order_id=? WHERE id=?',[$o['id'],$s['id']]);$c=$this->db->one('SELECT * FROM renewal_connections WHERE id=?',[$o['connection_id']]);}
        $plans=[];if(!$o&&$s['open']){try{$this->session($token,true);$plans=$this->eligiblePlans($s,$this->eligibility($s));}catch(Problem){}}
        return ['reseller'=>$seller['name']??'Your provider','target_label'=>'Existing account ending '.substr($s['line_id'],-4),'plans'=>$plans,'order'=>$this->publicOrder($o),'expires_at'=>$s['expires_at'],'simulated'=>$c&&$c['mode']==='mock'];
    }
    public function checkout(array $input,string $ip):array {
        Input::keys($input,['session_token','plan_id','plan_revision','email']);$s=$this->session(Input::text($input['session_token']??null,'checkout token',64),true);
        $this->auth->limit('renewal-checkout',$s['id'],10,3600);$email=Input::email($input['email']??null);
        $eligibility=$this->eligibility($s);$plan=null;
        foreach($this->eligiblePlans($s,$eligibility) as $p)if($p['id']===($input['plan_id']??null)&&$p['revision']===($input['plan_revision']??null))$plan=$p;
        if(!$plan)throw new Problem(409,'Plan changed or is unavailable. Refresh before paying.');
        $connection=$this->settings->connection($s['tenant_id']);if(!$connection)throw new Problem(409,'Provider payments are unavailable.');
        $created=$this->db->transaction(function()use($s,$plan,$email,$connection):array{
            $this->db->one('SELECT panel_id FROM panel_customers WHERE panel_id=? FOR UPDATE',[$s['line_id']]);
            if($old=$this->currentOrder($s)){$this->db->query('UPDATE renewal_sessions SET order_id=? WHERE id=?',[$old['id'],$s['id']]);return ['created'=>false,'order'=>$old];}
            if(!$this->db->one('SELECT id FROM renewal_connections WHERE id=? AND tenant_id=? AND enabled=1 FOR UPDATE',[$connection['id'],$s['tenant_id']]))throw new Problem(409,'Merchant connection changed. Refresh before paying.');
            $snapshot=$this->db->one('SELECT * FROM renewal_plans WHERE id=? AND tenant_id=? FOR UPDATE',[$plan['id'],$s['tenant_id']]);
            if(!$snapshot||!(bool)$snapshot['enabled']||(int)$snapshot['revision']!==$plan['revision'])throw new Problem(409,'Plan changed before checkout.');
            $id=Database::id();
            $this->db->query('INSERT INTO renewal_orders (id,session_id,tenant_id,brand_id,reseller_id,line_id,primary_fingerprint,connection_id,merchant_id,plan_id,plan_snapshot,currency,amount_minor,email_cipher,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 30 MINUTE))',[$id,$s['id'],$s['tenant_id'],$s['brand_id'],$s['reseller_id'],$s['line_id'],$s['primary_fingerprint'],$connection['id'],$connection['merchant_id'],$plan['id'],json_encode($snapshot,JSON_THROW_ON_ERROR),$plan['currency'],$plan['amount_minor'],$this->secrets->encrypt($email)]);
            $this->db->query('UPDATE renewal_sessions SET order_id=? WHERE id=?',[$id,$s['id']]);
            return ['created'=>true,'order'=>$this->db->one('SELECT * FROM renewal_orders WHERE id=?',[$id])];
        });
        $o=$created['order'];
        if($created['created']){
            try{$link=$this->gateway->create($connection,$o,$email);$this->db->query("UPDATE renewal_orders SET provider_link_id=?,provider_url=?,payment_state='awaiting' WHERE id=?",[$link['link_id'],$link['url'],$o['id']]);}
            catch(\Throwable){$this->db->query("UPDATE renewal_orders SET payment_state='link_uncertain' WHERE id=?",[$o['id']]);throw new Problem(503,'Payment link preparation is uncertain. Keep this order and contact your provider; do not pay again.');}
        }
        return ['order'=>$this->publicOrder($this->db->one('SELECT * FROM renewal_orders WHERE id=?',[$o['id']]))];
    }
    public function status(string $token):array {
        $s=$this->session($token);$o=$s['order_id']?$this->db->one('SELECT * FROM renewal_orders WHERE id=?',[$s['order_id']]):$this->currentOrder($s);
        $this->auth->limit('renewal-capability',$s['id'],60,60);
        $c=$o?$this->db->one('SELECT mode FROM renewal_connections WHERE id=?',[$o['connection_id']]):null;
        return ['schema_version'=>1,'order'=>$this->publicOrder($o),'simulated'=>$c&&$c['mode']==='mock'];
    }
    public function webhook(string $connectionId,string $raw,string $signature):array {
        $c=$this->db->one('SELECT * FROM renewal_connections WHERE id=?',[Input::id($connectionId)])??throw new Problem(401,'Invalid payment notification.');
        if(!RampexPaymentVerifier::signatureValid($raw,$signature,$this->secrets->decrypt($c['webhook_cipher'])))throw new Problem(401,'Invalid payment notification.');
        try{$e=json_decode($raw,true,16,JSON_THROW_ON_ERROR);}catch(\JsonException){throw new Problem(422,'Invalid payment notification.');}
        if(!is_array($e)||!is_string($e['link_id']??null)||strlen($e['link_id'])>200)throw new Problem(422,'Invalid payment reference.');
        $o=$this->db->one('SELECT id FROM renewal_orders WHERE connection_id=? AND provider_link_id=?',[$c['id'],$e['link_id']])??throw new Problem(404,'Payment order not found.');
        $this->db->query('INSERT INTO renewal_events (id,connection_id,order_id,body_sha256,body_cipher,signature) VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id',[Database::id(),$c['id'],$o['id'],hash('sha256',$raw),$this->secrets->encrypt($raw),strtolower(trim($signature))]);
        return ['accepted'=>true]; // No provider/worker network calls in webhook request.
    }
    public function simulate(array $actor,string $id):array {
        if(!$this->gateway->mockAllowed())throw new Problem(404,'Simulation unavailable.');
        $o=$this->ownedOrder($actor,$id);$c=$this->db->one('SELECT * FROM renewal_connections WHERE id=?',[$o['connection_id']]);
        if($c['mode']!=='mock'||!$o['provider_link_id'])throw new Problem(409,'Not a prepared demo order.');
        $this->db->query("UPDATE renewal_simulations SET status='completed' WHERE link_id=? AND merchant_id=?",[$o['provider_link_id'],$o['merchant_id']]);
        $raw=json_encode(['event'=>'payment.completed','status'=>'completed','link_id'=>$o['provider_link_id'],'currency'=>$o['currency'],'amount'=>number_format($o['amount_minor']/100,2,'.',''),'txid_out'=>'0x'.hash('sha256',$o['id']),'paid_at'=>gmdate('Y-m-d\TH:i:s\Z')],JSON_THROW_ON_ERROR);
        return $this->webhook($c['id'],$raw,hash_hmac('sha256',$raw,$this->secrets->decrypt($c['webhook_cipher'])));
    }
    private function ownedOrder(array $actor,string $id):array {
        $o=$this->db->one('SELECT * FROM renewal_orders WHERE id=?',[Input::id($id)])??throw new Problem(404,'Order not found.');$this->management->tenant($actor,$o['tenant_id']);return $o;
    }
    public function orders(array $actor,array $input):array {
        Input::keys($input,['tenant_id']);$t=$this->settings->workspace($actor,$input['tenant_id']??null);
        return ['items'=>$this->db->all('SELECT o.id,o.payment_state,o.fulfilment_state,o.currency,o.amount_minor,o.created_at,o.paid_at,c.mode FROM renewal_orders o JOIN renewal_connections c ON c.id=o.connection_id WHERE o.tenant_id=? ORDER BY o.created_at DESC,o.id LIMIT 50',[$t['id']]),'simulation_allowed'=>$this->gateway->mockAllowed()];
    }
    public function retry(array $actor,string $id):array {
        $o=$this->ownedOrder($actor,$id);
        if($o['payment_state']!=='paid'||$o['fulfilment_state']==='fulfilled')throw new Problem(409,'Only an unfulfilled paid order may be retried.');
        $this->db->query("UPDATE renewal_orders SET fulfilment_state='pending',next_attempt_at=UTC_TIMESTAMP() WHERE id=? AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP())",[$id]);
        $this->auth->audit($actor,'renewals.same_order_retry',$id,$o['tenant_id']);return ['queued'=>true,'new_charge'=>false];
    }
    public function processOne():array {
        if(!$this->settings->installed())return ['worked'=>false];
        $claim=$this->db->transaction(function():?array{
            $o=$this->db->one("SELECT o.* FROM renewal_orders o WHERE (o.lease_until IS NULL OR o.lease_until<UTC_TIMESTAMP()) AND (o.next_attempt_at IS NULL OR o.next_attempt_at<=UTC_TIMESTAMP()) AND (EXISTS(SELECT 1 FROM renewal_events e WHERE e.order_id=o.id AND e.state IN ('pending','processing')) OR (o.payment_state='paid' AND o.fulfilment_state IN ('pending','refresh_pending'))) ORDER BY o.created_at,o.id LIMIT 1 FOR UPDATE SKIP LOCKED");
            if(!$o)return null;$lease=bin2hex(random_bytes(32));$this->db->query('UPDATE renewal_orders SET lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 90 SECOND) WHERE id=?',[$lease,$o['id']]);$o['lease_token']=$lease;return $o;
        });
        if(!$claim)return ['worked'=>false];$o=$claim;$lease=$o['lease_token'];
        try{
            $c=$this->db->one('SELECT * FROM renewal_connections WHERE id=?',[$o['connection_id']]);
            $event=$this->db->one("SELECT * FROM renewal_events WHERE order_id=? AND state IN ('pending','processing') ORDER BY created_at,id LIMIT 1",[$o['id']]);
            if($event){
                $raw=$this->secrets->decrypt($event['body_cipher']);$payload=json_decode($raw,true,16,JSON_THROW_ON_ERROR);
                if(($payload['event']??null)!=='payment.completed'){$this->db->query("UPDATE renewal_events SET state='ignored' WHERE id=?",[$event['id']]);}
                else{
                    $receipt=RampexPaymentVerifier::completed($raw,$event['signature'],$this->secrets->decrypt($c['webhook_cipher']),['link_id'=>$o['provider_link_id'],'merchant_id'=>$o['merchant_id'],'currency'=>$o['currency'],'amount_minor'=>(int)$o['amount_minor']],$this->gateway->status($c,$o));
                    $this->db->transaction(function()use($o,$lease,$receipt,$event,$payload):void{
                        $current=$this->db->one('SELECT * FROM renewal_orders WHERE id=? AND lease_token=? FOR UPDATE',[$o['id'],$lease]);if(!$current)throw new Problem(409,'Order lease changed.');
                        $old=$this->db->one('SELECT order_id FROM renewal_settlements WHERE merchant_id=? AND (link_id=? OR settlement_ref=?) FOR UPDATE',[$o['merchant_id'],$o['provider_link_id'],$receipt['settlement_ref']]);
                        if($old&&$old['order_id']!==$o['id'])throw new Problem(409,'Payment is already attached to another renewal.');
                        if(!$old)$this->db->query('INSERT INTO renewal_settlements VALUES (?,?,?,?)',[$o['merchant_id'],$o['provider_link_id'],$o['id'],$receipt['settlement_ref']]);
                        $paid=is_string($payload['paid_at']??null)?strtotime($payload['paid_at']):false;
                        $late=$paid===false||$paid>time()+300||$paid<strtotime($o['created_at'].' UTC')-300||$paid>strtotime($o['expires_at'].' UTC');
                        if($current['payment_state']!=='paid')$this->db->query("UPDATE renewal_orders SET payment_state='paid',paid_at=UTC_TIMESTAMP(),fulfilment_state=? WHERE id=?",[$late?'needs_review':'pending',$o['id']]);
                        $this->db->query("UPDATE renewal_events SET state='verified' WHERE id=?",[$event['id']]);
                    });
                }
            }
            $o=$this->db->one('SELECT * FROM renewal_orders WHERE id=?',[$o['id']]);
            if($o['payment_state']==='paid'&&in_array($o['fulfilment_state'],['pending','refresh_pending'],true)){
                $s=$this->db->one('SELECT * FROM renewal_sessions WHERE id=?',[$o['session_id']]);$d=$this->binding($s['device_id'],$s['app_package'],$s['key_fingerprint']);
                if($d['account_ref']!==$s['account_ref']||$d['line_id']!==$o['line_id']||$d['reseller_id']!==$o['reseller_id'])throw new Problem(409,'Paid order ownership changed.');
                $p=json_decode($o['plan_snapshot'],true,16,JSON_THROW_ON_ERROR);
                $r=$this->worker->renew(['operation_id'=>$o['id'],'brand_id'=>$o['brand_id'],'reseller_id'=>$o['reseller_id'],'line_id'=>$o['line_id'],'package_id'=>(int)$p['package_id'],'plan_fingerprint'=>$p['plan_fingerprint'],'merchant_id'=>$o['merchant_id'],'payment_link_id'=>$o['provider_link_id'],'primary_fingerprint'=>$o['primary_fingerprint']]);
                $this->db->query('UPDATE renewal_orders SET fulfilment_state=?,receipt=?,next_attempt_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 MINUTE) WHERE id=? AND lease_token=?',[$r['state'],json_encode($r,JSON_THROW_ON_ERROR),$o['id'],$lease]);
            }
        }catch(Problem $error){
            // Preserve money/order evidence. No new charge and no different operation.
            if($error->status===409||$error->status===422){
                $this->db->query("UPDATE renewal_orders SET fulfilment_state=IF(fulfilment_state='fulfilled','fulfilled','needs_review'),payment_state=IF(payment_state='paid','paid','review') WHERE id=? AND lease_token=?",[$o['id'],$lease]);
                if(isset($event['id']))$this->db->query("UPDATE renewal_events SET state='review' WHERE id=?",[$event['id']]);
            }
            $this->db->query('UPDATE renewal_orders SET next_attempt_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 5 MINUTE) WHERE id=? AND lease_token=?',[$o['id'],$lease]);
        }finally{$this->db->query('UPDATE renewal_orders SET lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?',[$o['id'],$lease]);}
        return ['worked'=>true,'order_id'=>$o['id']];
    }
}
