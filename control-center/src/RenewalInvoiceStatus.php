<?php
declare(strict_types=1);
namespace ControlCenter;

/** Operator-only CLI diagnostics. SELECTs and provider status GETs only: no
 * creation, cancellation, record clearing, job processing or panel renewal. */
final class RenewalInvoiceStatus {
    public static function probe(Application $app,Config $config,string $id,?RampexGateway $gateway=null):array {
        $o=$app->db->one('SELECT * FROM renewal_orders WHERE id=?',[Input::id($id)])??throw new Problem(404,'Renewal invoice not found.');
        $c=$app->db->one('SELECT * FROM renewal_connections WHERE id=?',[$o['connection_id']]);
        if(!$c)throw new Problem(409,'Original merchant connection is unavailable.');
        $active=$app->db->one('SELECT * FROM renewal_connections WHERE tenant_id=? AND gateway=? AND merchant_id=? AND mode=? AND enabled=1 ORDER BY created_at DESC,id DESC LIMIT 1',[$o['tenant_id'],$c['gateway'],$o['merchant_id'],$c['mode']]);
        $reader=$active??$c;
        $out=['order_reference'=>$o['id'],'local_payment_state'=>$o['payment_state'],'local_renewal_state'=>$o['fulfilment_state'],'gateway'=>$c['gateway'],'provider_reference'=>$o['provider_link_id'],'provider_confirmed_expired_unpaid'=>false,'records_changed'=>false,'payment_requested'=>false,'renewal_requested'=>false];
        $snapshot=json_decode($o['plan_snapshot'],true,16,JSON_THROW_ON_ERROR);$package=(int)($snapshot['package_id']??0);
        $prices=$app->db->all("SELECT currency,enabled FROM renewal_plans WHERE tenant_id=? AND package_id=? AND country='CA'",[$o['tenant_id'],$package]);
        if(!$prices)$prices=$app->db->all("SELECT currency,enabled FROM renewal_plans WHERE tenant_id=? AND package_id=? AND country=''",[$o['tenant_id'],$package]);
        $prices=array_values(array_filter($prices,static fn($p)=>(bool)$p['enabled']));
        $currency=count($prices)===1?$prices[0]['currency']:null;$out['invoice_package_canada_price_currency']=$currency;
        $s=$app->db->one('SELECT app_package FROM renewal_sessions WHERE id=?',[$o['session_id']]);
        $out['configured_canada_routes_for_package_price']=$currency?array_map(static fn($v)=>['gateway'=>$v['gateway'],'mode'=>$v['mode']],$app->renewalSettings->candidates($o['tenant_id'],'CA',$currency,str_ends_with($s['app_package']??'','.test'))):[];
        if(!$o['provider_link_id'])return $out+['provider_check'=>'Original provider resource is unresolved; retain this invoice.'];
        try {
            $gateway??=new RampexGateway($config,$app->db,new Secrets($config));
            $status=$gateway->status($reader,$o);
            $out['provider_status']=$status['status']??'unknown';$out['provider_fully_paid']=$status['fully_paid']??null;
            $out['provider_resource_matches']=($status['link_id']??null)===$o['provider_link_id'];
            $out['provider_merchant_matches']=($status['merchant_id']??null)===$o['merchant_id'];
            $out['provider_quote_matches']=($status['amount_minor']??null)===(int)$o['amount_minor']&&($status['currency']??null)===$o['currency'];
            $out['provider_confirmed_expired_unpaid']=RampexGateway::unpaidExpiryConfirmed($o,$status);
        }catch(Problem $e){$out['provider_check_error']=$e->getMessage();}
        catch(\Throwable $e){$out['provider_check_error_type']=get_class($e);}
        return $out;
    }
}
