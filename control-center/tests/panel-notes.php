<?php
declare(strict_types=1);
function panelNotesCases(array &$tests,ControlCenter\Application $app,ControlCenter\Config $config,array $owner):void {
    $tests['panel notes are signed, current, scoped and never replaced with local notes']=function()use($app,$config,$owner):void {
        $binding=$app->db->one('SELECT p.* FROM panel_customers p JOIN panel_resellers r ON r.panel_id=p.reseller_id AND r.tenant_id=p.tenant_id LIMIT 1');check((bool)$binding,'Panel fixture required');
        $proofs=new ControlCenter\EnrollmentProof($config,new ControlCenter\Secrets($config));$secret=$proofs->bridgeSecret();$note="Actual panel note\n<VIP>";$mode='ok';$calls=0;
        $bridge=new ControlCenter\PanelBridge($proofs,function($body,$headers)use($secret,&$note,&$mode,&$calls):string {
            $calls++;$h=[];foreach($headers as $line){[$k,$v]=explode(': ',$line,2);$h[$k]=$v;}
            same(ControlCenter\PanelBridge::signature($secret,'line-notes-request-v1',$h['X-Management-Timestamp'],$h['X-Management-Nonce'],$body),$h['X-Management-Signature']);
            $rows=json_decode($body,true)['lines'];foreach($rows as &$row){$row['notes']=$note;$row['state']='current';if($mode==='binding')$row['reseller_id']='999999';}unset($row);
            $payload=json_encode(['lines'=>$rows]);return json_encode(['payload'=>$payload,'signature'=>$mode==='tampered'?str_repeat('0',64):ControlCenter\PanelBridge::signature($secret,'line-notes-result-v1',$h['X-Management-Timestamp'],$h['X-Management-Nonce'],$payload)]);
        });
        $management=new ControlCenter\Management($app->db,$config,$app->auth,$bridge);
        $customer=$app->db->one('SELECT * FROM customers WHERE id=?',[$binding['customer_id']]);
        $app->db->query('UPDATE customers SET notes=? WHERE id=?',['Different Hub note',$customer['id']]);
        try {
            $actor=['role'=>'reseller','tenant_id'=>$binding['tenant_id']];
            $find=function()use($management,$actor,$customer):array {foreach($management->listing($actor,'customers',['search'=>$customer['external_reference']??$customer['name']])['items'] as $row)if($row['id']===$customer['id'])return $row;throw new RuntimeException('Fixture not listed');};
            same($note,$find()['panel_notes']);$note='';same('',$find()['panel_notes']);
            foreach(['tampered','binding'] as $mode){$row=$find();same(null,$row['panel_notes']);same('unavailable',$row['panel_notes_state']);}
            $mode='ok';$note='Device panel note';$device=$management->createDevice($owner,['customer_id'=>$customer['id'],'label'=>'TEST panel notes']);
            $rows=$management->listing($actor,'devices',['customer_id'=>$customer['id'],'visibility'=>'all'])['items'];$found=false;foreach($rows as $row)if($row['id']===$device['id']){same($note,$row['panel_notes']);$found=true;}check($found,'Device note fixture listed');
            $other=$app->db->one('SELECT id FROM tenants WHERE id!=? LIMIT 1',[$binding['tenant_id']]);$before=$calls;
            problem(404,fn()=> $management->listing(['role'=>'reseller','tenant_id'=>$other['id']],'devices',['customer_id'=>$customer['id']]));same($before,$calls);
        }finally{$app->db->query('UPDATE customers SET notes=? WHERE id=?',[$customer['notes'],$customer['id']]);}
    };
}
