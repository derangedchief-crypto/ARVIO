<?php
declare(strict_types=1);
namespace ControlCenter;

/** Account-scoped checkout handover with preserved financial evidence.
 * Customer switching is immediate and local; legacy provider cancellation
 * helpers remain separate. Neither mode deletes invoices or refunds funds. */
final class RenewalCheckoutVoids {
    public function __construct(private readonly Database $db,private readonly RenewalSettings $settings,private readonly RampexGateway $gateway,private readonly Auth $auth) {}
    private function scope(array $s):array {return [$s['tenant_id'],$s['line_id'],$s['primary_fingerprint']];}
    private function pending(array $s,bool $lock=false):array {
        return $this->db->all("SELECT * FROM renewal_orders WHERE tenant_id=? AND line_id=? AND primary_fingerprint=? AND checkout_superseded=0 AND fulfilment_state!='fulfilled' AND payment_state IN ('creating','awaiting','link_uncertain','verification_pending','paid','review') ORDER BY created_at,id".($lock?' FOR UPDATE':''),$this->scope($s));
    }
    public function assertCheckout(array $s,int $expiry):void {
        $r=$this->db->one('SELECT * FROM renewal_route_switches WHERE tenant_id=? AND line_id=? AND primary_fingerprint=? FOR UPDATE',$this->scope($s));
        if(!$r)return;
        if(in_array($r['state'],['switching','blocked'],true))throw new Problem(409,'Previous checkout cancellation has not been confirmed. Recheck the switch; do not start another payment.');
        if($r['state']==='ready'&&(int)$r['quoted_expiry']===$expiry&&($r['country']!==$s['country']||$r['gateway']!==$s['gateway']))throw new Problem(409,'The payment method changed in another checkout. Refresh or select the desired method before paying.');
    }
    public function checkoutCreated(array $s):void {
        $this->db->query("UPDATE renewal_route_switches SET state='completed',updated_at=UTC_TIMESTAMP() WHERE tenant_id=? AND line_id=? AND primary_fingerprint=? AND state='ready'",$this->scope($s));
    }
    public function handover(array $s,string $country,string $gateway,int $expiry):void {
        $this->db->transaction(function()use($s,$country,$gateway,$expiry):void{
            $this->db->one('SELECT id FROM tenants WHERE id=? FOR UPDATE',[$s['tenant_id']]);$scope=$this->scope($s);
            $orders=$this->pending($s,true);
            foreach($orders as $o)if($o['payment_state']==='paid')throw new Problem(409,'Payment has already been received. Your existing renewal must be reconciled rather than charged again.');
            foreach($orders as $o){
                $this->db->query('UPDATE renewal_orders SET checkout_superseded=1 WHERE id=?',[$o['id']]);
                $this->auth->audit(null,'renewals.checkout_superseded',$o['id'],$s['tenant_id'],['replacement_gateway'=>$gateway,'provider_cancelled'=>false]);
            }
            $this->db->query('UPDATE renewal_sessions SET country=?,gateway=?,order_id=NULL WHERE tenant_id=? AND line_id=? AND primary_fingerprint=? AND expires_at>UTC_TIMESTAMP()',[$country,$gateway,...$scope]);
            $this->db->query("INSERT INTO renewal_route_switches (tenant_id,line_id,primary_fingerprint,token,state,country,gateway,quoted_expiry,lease_until) VALUES (?,?,?,?,'ready',?,?,?,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE token=VALUES(token),state='ready',country=VALUES(country),gateway=VALUES(gateway),quoted_expiry=VALUES(quoted_expiry),lease_until=VALUES(lease_until),updated_at=UTC_TIMESTAMP()",[...$scope,Database::id(),$country,$gateway,$expiry]);
        });
    }
    public function switch(array $s,string $country,string $gateway,int $expiry,bool $cancel=true):void {
        $token=Database::id();$scope=$this->scope($s);
        $orders=$this->db->transaction(function()use($s,$country,$gateway,$expiry,$token,$scope):array{
            $this->db->one('SELECT id FROM tenants WHERE id=? FOR UPDATE',[$s['tenant_id']]);
            $r=$this->db->one('SELECT *,lease_until>UTC_TIMESTAMP() AS busy FROM renewal_route_switches WHERE tenant_id=? AND line_id=? AND primary_fingerprint=? FOR UPDATE',$scope);
            if($r&&$r['state']==='switching'&&$r['busy'])throw new Problem(409,'Checkout cancellation is already running. Wait for confirmation rather than starting another payment.');
            $orders=$this->pending($s,true);
            foreach($orders as $o)if($o['payment_state']!=='awaiting'||!$o['provider_link_id'])throw new Problem(409,'An older payment is paid, processing or unresolved. It cannot be automatically voided; retain the original invoice.');
            $this->db->query("INSERT INTO renewal_route_switches (tenant_id,line_id,primary_fingerprint,token,state,country,gateway,quoted_expiry,lease_until) VALUES (?,?,?,?,'switching',?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 3 MINUTE)) ON DUPLICATE KEY UPDATE token=VALUES(token),state='switching',country=VALUES(country),gateway=VALUES(gateway),quoted_expiry=VALUES(quoted_expiry),lease_until=VALUES(lease_until),updated_at=UTC_TIMESTAMP()",[...$scope,$token,$country,$gateway,$expiry]);
            foreach($orders as $o){
                $this->db->query("UPDATE renewal_orders SET void_state='requested',void_token=? WHERE id=?",[$token,$o['id']]);
                $this->auth->audit(null,'renewals.checkout_void_requested',$o['id'],$s['tenant_id'],['gateway'=>$gateway]);
            }return $orders;
        });
        try {
            foreach($orders as $o){
                $c=$this->db->one('SELECT * FROM renewal_connections WHERE id=?',[$o['connection_id']]);
                if(!$c)throw new Problem(409,'Original merchant connection is unavailable.');
                $active=$this->settings->connection($s['tenant_id'],$c['gateway']);$reader=$active&&$active['merchant_id']===$o['merchant_id']&&$active['mode']===$c['mode']?$active:$c;
                $proof=$cancel?$this->gateway->voidUnpaid($reader,$o):$this->gateway->status($reader,$o);
                if(!RampexGateway::unpaidExpiryConfirmed($o,$proof))throw new Problem(409,'The provider has not confirmed that the old invoice expired unpaid. Confirm cancellation to switch; no replacement payment was created.');
                $this->db->transaction(function()use($s,$o,$token):void{
                    $this->db->one('SELECT id FROM tenants WHERE id=? FOR UPDATE',[$s['tenant_id']]);
                    $row=$this->db->one('SELECT payment_state,fulfilment_state,void_token FROM renewal_orders WHERE id=? FOR UPDATE',[$o['id']]);
                    if(!$row||$row['void_token']!==$token||$row['payment_state']!=='awaiting'||$row['fulfilment_state']==='fulfilled')throw new Problem(409,'The original payment changed while cancellation was being checked. No new payment was opened.');
                    $this->db->query("UPDATE renewal_orders SET payment_state='expired',fulfilment_state='not_paid',void_state='confirmed',void_token=NULL WHERE id=?",[$o['id']]);
                    $this->auth->audit(null,'renewals.checkout_void_confirmed',$o['id'],$s['tenant_id']);
                });
            }
            $this->db->transaction(function()use($s,$country,$gateway,$expiry,$token,$scope):void{
                $this->db->one('SELECT id FROM tenants WHERE id=? FOR UPDATE',[$s['tenant_id']]);
                $r=$this->db->one('SELECT token,state FROM renewal_route_switches WHERE tenant_id=? AND line_id=? AND primary_fingerprint=? FOR UPDATE',$scope);
                if(!$r||$r['token']!==$token||$r['state']!=='switching'||$this->pending($s,true))throw new Problem(409,'An older or concurrent payment remains unresolved. No replacement checkout was opened.');
                $this->db->query('UPDATE renewal_sessions SET country=?,gateway=?,order_id=NULL WHERE tenant_id=? AND line_id=? AND primary_fingerprint=? AND expires_at>UTC_TIMESTAMP()',[$country,$gateway,...$scope]);
                $this->db->query("UPDATE renewal_route_switches SET state='ready',updated_at=UTC_TIMESTAMP() WHERE tenant_id=? AND line_id=? AND primary_fingerprint=? AND token=?",[...$scope,$token]);
            });
        }catch(\Throwable $e){
            $this->db->transaction(function()use($scope,$token):void{
                $this->db->query("UPDATE renewal_orders SET void_state='uncertain' WHERE tenant_id=? AND line_id=? AND primary_fingerprint=? AND void_token=? AND void_state='requested'",[...$scope,$token]);
                $this->db->query("UPDATE renewal_route_switches SET state='blocked',updated_at=UTC_TIMESTAMP() WHERE tenant_id=? AND line_id=? AND primary_fingerprint=? AND token=?",[...$scope,$token]);
            });
            if($e instanceof Problem)throw $e;
            throw new Problem(503,'Previous checkout cancellation is uncertain. Recheck the original switch; do not pay again.') ;
        }
    }
}
