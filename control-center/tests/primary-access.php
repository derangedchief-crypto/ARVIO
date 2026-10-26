<?php
declare(strict_types=1);
function primaryAccessCases(array &$tests,ControlCenter\Application $app,ControlCenter\Config $config,array $owner):void {
    $tests['primary lookup accepts only nonce-bound signed status for the bound account']=function()use($app,$config):void {
        $proofs=new ControlCenter\EnrollmentProof($config,new ControlCenter\Secrets($config));$secret=$proofs->bridgeSecret();$ref=str_repeat('a',64);
        $transport=function($body,$headers)use($secret,$ref):string {
            $h=[];foreach($headers as $line){[$k,$v]=explode(': ',$line,2);$h[$k]=$v;}
            $request=json_decode($body,true);same(['customer_id'=>'123','account_ref'=>$ref],$request);
            same(ControlCenter\PanelBridge::signature($secret,'primary-line-request-v1',$h['X-Management-Timestamp'],$h['X-Management-Nonce'],$body),$h['X-Management-Signature']);
            $payload=json_encode(['id'=>'123','account_ref'=>$ref,'reseller_id'=>'252','identity_valid'=>true,'active'=>true,'valid_for_seconds'=>23]);
            return json_encode(['payload'=>$payload,'signature'=>ControlCenter\PanelBridge::signature($secret,'primary-line-result-v1',$h['X-Management-Timestamp'],$h['X-Management-Nonce'],$payload)]);
        };
        $bridge=new ControlCenter\PanelBridge($proofs,$transport);same(23,$bridge->primaryLine('123',$ref)['valid_for_seconds']);
        $bad=new ControlCenter\PanelBridge($proofs,fn($b,$h)=>json_encode(['payload'=>'{}','signature'=>str_repeat('0',64)]));problem(503,fn()=> $bad->primaryLine('123',$ref));
        problem(503,fn()=> $bridge->primaryLine('invalid',$ref));
    };
    $tests['production cannot use the local simulator primary-validation exemption']=function()use($app,$config,$owner):void {
        $t=$app->management->workspace($owner);$c=$app->management->createCustomer($owner,['tenant_id'=>$t['id'],'name'=>'TEST primary proof isolation']);$d=$app->management->createDevice($owner,['customer_id'=>$c['id'],'label'=>'TEST isolated proof']);$app->commands->issueTestCredential($owner,$d['id']);
        $production=new ControlCenter\Config($config->root,array_replace($config->values,['environment'=>'production','origin'=>'https://control.example.test']));
        $checker=new ControlCenter\PrimaryLineAccess($app->db,$production);same(['valid'=>false,'seconds'=>0,'reason'=>'primary_unverified'],$checker->device($d));
    };
    $tests['primary status uses the enrolled line not caller-supplied account or source fields']=function()use($app,$config):void {
        // Enrollment cryptography is exercised by the existing signed enrollment
        // suite. Reuse one resulting real binding, with a synthetic signed worker.
        $d=$app->db->one("SELECT d.* FROM devices d JOIN enrollment_requests r ON r.device_id=d.id AND r.key_fingerprint=d.key_fingerprint AND r.app_package=d.app_package JOIN panel_customers p ON p.account_ref=r.account_ref WHERE r.status='approved' LIMIT 1");check((bool)$d,'Signed fixture binding required');
        $binding=$app->db->one("SELECT r.account_ref,p.panel_id,p.reseller_id FROM enrollment_requests r JOIN panel_customers p ON p.account_ref=r.account_ref WHERE r.device_id=? AND r.key_fingerprint=? AND r.app_package=? AND r.status='approved' ORDER BY r.created_at DESC,r.id DESC LIMIT 1",[$d['id'],$d['key_fingerprint'],$d['app_package']]);
        $proofs=new ControlCenter\EnrollmentProof($config,new ControlCenter\Secrets($config));$secret=$proofs->bridgeSecret();$active=true;
        $bridge=new ControlCenter\PanelBridge($proofs,function($body,$headers)use($binding,$secret,&$active):string {
            same(['customer_id'=>$binding['panel_id'],'account_ref'=>$binding['account_ref']],json_decode($body,true));$h=[];foreach($headers as $line){[$k,$v]=explode(': ',$line,2);$h[$k]=$v;}
            $payload=json_encode(['id'=>$binding['panel_id'],'account_ref'=>$binding['account_ref'],'reseller_id'=>$binding['reseller_id'],'identity_valid'=>true,'active'=>$active,'valid_for_seconds'=>$active?12:0]);
            return json_encode(['payload'=>$payload,'signature'=>ControlCenter\PanelBridge::signature($secret,'primary-line-result-v1',$h['X-Management-Timestamp'],$h['X-Management-Nonce'],$payload)]);
        });
        $checker=new ControlCenter\PrimaryLineAccess($app->db,$config,$bridge);$forged=$d;$forged['account_ref']=str_repeat('0',64);$forged['app_package']='foreign.package';$forged['key_fingerprint']=str_repeat('0',64);
        $valid=$checker->device($forged);same(true,$valid['valid']);check($valid['seconds']>0&&$valid['seconds']<=12);
        $active=false;$invalid=$checker->device($forged);same(false,$invalid['valid']);same('primary_invalid',$invalid['reason']);same(0,$invalid['seconds']);
    };
}
