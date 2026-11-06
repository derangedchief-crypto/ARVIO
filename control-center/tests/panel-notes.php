<?php
declare(strict_types=1);
function panelNotesCases(array &$tests,ControlCenter\Application $app,ControlCenter\Config $config,array $owner):void {
    $tests['panel notes are signed, current, scoped and never replaced with local notes']=function()use($app,$config,$owner):void {
        $binding=$app->db->one('SELECT p.* FROM panel_customers p JOIN panel_resellers r ON r.panel_id=p.reseller_id AND r.tenant_id=p.tenant_id LIMIT 1');check((bool)$binding,'Panel fixture required');
        $proofs=new ControlCenter\EnrollmentProof($config,new ControlCenter\Secrets($config));$secret=$proofs->bridgeSecret();$note="Actual panel note\n<VIP>";$mode='ok';$calls=0;$requested=[];
        $bridge=new ControlCenter\PanelBridge($proofs,function($body,$headers)use($secret,&$note,&$mode,&$calls,&$requested):string {
            $calls++;$h=[];foreach($headers as $line){[$k,$v]=explode(': ',$line,2);$h[$k]=$v;}
            same(ControlCenter\PanelBridge::signature($secret,'line-notes-request-v1',$h['X-Management-Timestamp'],$h['X-Management-Nonce'],$body),$h['X-Management-Signature']);
            $rows=json_decode($body,true)['lines'];check(count($rows)<=25,'Notes requests remain bounded');foreach($rows as &$row){$requested[]=$row['id'];$row['notes']=$note;$row['state']='current';if($mode==='binding')$row['reseller_id']='999999';}unset($row);
            $payload=json_encode(['lines'=>$rows]);return json_encode(['payload'=>$payload,'signature'=>$mode==='tampered'?str_repeat('0',64):ControlCenter\PanelBridge::signature($secret,'line-notes-result-v1',$h['X-Management-Timestamp'],$h['X-Management-Nonce'],$payload)]);
        });
        $management=new ControlCenter\Management($app->db,$config,$app->auth,$bridge);
        $customer=$app->db->one('SELECT * FROM customers WHERE id=?',[$binding['customer_id']]);
        $app->db->query('UPDATE customers SET notes=? WHERE id=?',['Different Hub note',$customer['id']]);
        try {
            $actor=['role'=>'reseller','tenant_id'=>$binding['tenant_id']];
            $find=function()use($management,$actor,$customer):array {$page=1;do{$result=$management->listing($actor,'customers',['page'=>$page]);foreach($result['items'] as $row)if($row['id']===$customer['id'])return $row;$page++;}while($page<=$result['pages']);throw new RuntimeException('Fixture not listed');};
            same($note,$find()['panel_notes']);$note='';same('',$find()['panel_notes']);
            foreach(['tampered','binding'] as $mode){$row=$find();same(null,$row['panel_notes']);same('unavailable',$row['panel_notes_state']);}
            $mode='ok';$note='Device panel note';$device=$management->createDevice($owner,['customer_id'=>$customer['id'],'label'=>'TEST panel notes']);
            $rows=$management->listing($actor,'devices',['customer_id'=>$customer['id'],'visibility'=>'all'])['items'];$found=false;foreach($rows as $row)if($row['id']===$device['id']){same($note,$row['panel_notes']);$found=true;}check($found,'Device note fixture listed');
            $other=$app->db->one('SELECT id FROM tenants WHERE id!=? LIMIT 1',[$binding['tenant_id']]);$before=$calls;
            problem(404,fn()=> $management->listing(['role'=>'reseller','tenant_id'=>$other['id']],'devices',['customer_id'=>$customer['id']]));same($before,$calls);
            $note='Unique IPTV search CAFÉ %_';
            $requested=[];
            $matched=$management->listing($actor,'customers',['search'=>'café %_']);check(in_array($customer['id'],array_column($matched['items'],'id'),true),'Search matches actual panel notes, case-insensitive and literal');
            $allowed=array_column($app->db->all('SELECT panel_id FROM panel_customers WHERE tenant_id=?',[$binding['tenant_id']]),'panel_id');same([],array_values(array_diff($requested,$allowed)));
            same(0,$management->listing($actor,'customers',['search'=>'Different Hub note'])['total']);
            for($i=0;$i<26;$i++)$management->createDevice($owner,['customer_id'=>$customer['id'],'label'=>'TEST notes pagination '.$i]);
            $filter=['search'=>'café %_','customer_id'=>$customer['id'],'visibility'=>'all'];
            $first=$management->listing($actor,'devices',$filter);$second=$management->listing($actor,'devices',$filter+['page'=>2]);
            check($first['total']>=27,'Notes search runs before pagination');same(25,count($first['items']));check(count($second['items'])>=2,'Notes matches on second page');
            same([] ,array_intersect(array_column($first['items'],'id'),array_column($second['items'],'id')));
            $note='';same(0,$management->listing($actor,'devices',$filter)['total']);
            $note='Batch search fixture';
            for($i=0;$i<26;$i++){
                $extra=$management->createCustomer($owner,['tenant_id'=>$binding['tenant_id'],'name'=>'TEST batch notes '.$i]);
                $app->db->query('INSERT INTO panel_customers(panel_id,account_ref,customer_id,tenant_id,reseller_id) VALUES(?,?,?,?,?)',[(string)(90000000+$i),hash('sha256','notes-batch-'.$extra['id']),$extra['id'],$binding['tenant_id'],$binding['reseller_id']]);
            }
            $requested=[];$batch=$management->listing($actor,'customers',['search'=>$note,'page'=>2]);check($batch['total']>=27&&count($batch['items'])>=2,'Customer notes search includes matches beyond first batch/page');check(count(array_unique($requested))>=27,'All scoped bindings searched');
            $mode='tampered';problem(503,fn()=> $management->listing($actor,'devices',$filter));
        }finally{$app->db->query('UPDATE customers SET notes=? WHERE id=?',[$customer['notes'],$customer['id']]);}
    };
}
