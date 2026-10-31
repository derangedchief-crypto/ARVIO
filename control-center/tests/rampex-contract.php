<?php
declare(strict_types=1);
function rampexContractCases(array &$tests):void {
    $secret='SYNTHETIC-WEBHOOK-SECRET-ONLY';
    $order=['link_id'=>'fixture-link','merchant_id'=>'merchant-A','currency'=>'USD','amount_minor'=>1050];
    $status=$order+['status'=>'completed','fully_paid'=>true];
    $event=['event'=>'payment.completed','status'=>'completed','link_id'=>'fixture-link','amount'=>'10.50','currency'=>'USD','txid_out'=>'0x'.str_repeat('a',64),'payout_amount'=>'9.95'];
    $verify=static function(array $e,array $s)use($order,$secret):array{
        $raw=json_encode($e,JSON_THROW_ON_ERROR);return ControlCenter\RampexPaymentVerifier::completed($raw,hash_hmac('sha256',$raw,$secret),$secret,$order,$s);
    };
    $tests['Rampex raw-body signature matches documented public test vector']=static function():void{
        $raw='{"event":"payment.completed","link_id":"1736515200-abc123","status":"completed","amount":"100.50","currency":"USD","received_amount":100.5,"received_currency":"polygon_usdc","received_asset":"usdc","received_network":"polygon","payout_network":"polygon","payout_amount":99.495,"payout_currency":"USDC","payout_usd_value":99.495,"txid_in":"0x1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b","txid_out":"0x9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f","customer_email":"customer@example.com","paid_at":"2026-09-24T12:00:00.000Z","metadata":{"source":"api","order_id":"ORDER-98765"}}';
        $secret='whsec_sample_secret_7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c';$signature='ef27f33282c6e878b8078285d85c95d01badc0cbbbb93aa70515e5ad25aaf28f';
        same(true,ControlCenter\RampexPaymentVerifier::signatureValid($raw,$signature,$secret));
        same(false,ControlCenter\RampexPaymentVerifier::signatureValid($raw."\n",$signature,$secret));
    };
    $tests['Rampex amount comparison uses exact minor units without float rounding']=static function():void{
        same(1050,ControlCenter\RampexPaymentVerifier::minor('10.50','USD'));same(1050,ControlCenter\RampexPaymentVerifier::minor(10.5,'CAD'));
        foreach(['10.005','1e2','-1','NaN','42949672.96','0'] as $amount)problem(422,fn()=>ControlCenter\RampexPaymentVerifier::minor($amount,'USD'));
        same(15000000,ControlCenter\RampexPaymentVerifier::minor('150000','IDR'));same(5000001,ControlCenter\RampexPaymentVerifier::minor('50000.01','USD'));
        same(1500,ControlCenter\RampexPaymentVerifier::minor('1500','JPY'));same(1500,ControlCenter\RampexPaymentVerifier::minor('1500.00','JPY'));same(1050,ControlCenter\RampexPaymentVerifier::minor('10.50','INR'));
        same(37,count(ControlCenter\RampexPaymentVerifier::currencyExponents()));problem(422,fn()=>ControlCenter\RampexPaymentVerifier::minor('1500.50','JPY'));
        problem(422,fn()=>ControlCenter\RampexPaymentVerifier::minor('10','ZZZ'));
    };
    $tests['Rampex valid gross payment is not compared against net payout fees']=static function()use($verify,$event,$status):void{
        same(1050,$verify($event,$status)['amount_minor']);
    };
    $tests['Rampex wrong merchant link currency or price never authorizes renewal']=static function()use($verify,$event,$status):void{
        foreach([['merchant_id'=>'merchant-B'],['link_id'=>'foreign-link'],['currency'=>'CAD'],['amount_minor'=>1049],['fully_paid'=>false],['status'=>'active']] as $override)
            problem(409,fn()=> $verify($event,array_replace($status,$override)));
        problem(409,fn()=> $verify(array_replace($event,['amount'=>'9.50']),$status));
        problem(409,fn()=> $verify(array_replace($event,['currency'=>'CAD']),$status));
    };
    $tests['Rampex extra deposits and non-completed events are not subscription renewals']=static function()use($verify,$event,$status):void{
        problem(422,fn()=> $verify(array_replace($event,['event'=>'payment.extra_deposit']),$status));
        problem(422,fn()=> $verify(array_replace($event,['status'=>'expired']),$status));
    };
    $tests['Rampex signature failure is rejected before untrusted JSON is parsed']=static function()use($secret,$order,$status):void{
        problem(401,fn()=>ControlCenter\RampexPaymentVerifier::completed('{bad json',str_repeat('0',64),$secret,$order,$status));
    };
}
