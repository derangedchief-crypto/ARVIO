<?php
declare(strict_types=1);
function trialControlCases(array &$tests,ControlCenter\Application $app,ControlCenter\Config $config,array $owner,HttpFixture $http,string $password):void {
    $tests['global trial migration preserves an already published offer but leaves fresh or disabled offers off']=function()use($app,$config):void {
        $sql=str_replace(['platform_settings','trial_offer_publications'],['fixture_trial_settings','fixture_trial_publications'],file_get_contents($config->root.'/database/migrations/019_trial_global_control.sql'));
        foreach([0,1] as $published){
            $app->db->pdo->exec('CREATE TEMPORARY TABLE fixture_trial_settings (id TINYINT PRIMARY KEY,enrollment_enabled BOOLEAN NOT NULL)');$app->db->pdo->exec('CREATE TEMPORARY TABLE fixture_trial_publications (enabled BOOLEAN NOT NULL)');
            try{$app->db->query('INSERT INTO fixture_trial_settings VALUES (1,1)');$app->db->query('INSERT INTO fixture_trial_publications VALUES (?)',[$published]);foreach(explode(';',$sql) as $statement)if(trim($statement)!=='')$app->db->pdo->exec(trim($statement));$row=$app->db->one('SELECT * FROM fixture_trial_settings');same($published,(int)$row['trial_offers_enabled']);same(1,(int)$row['enrollment_enabled']);same(1,(int)$row['trial_control_revision']);}
            finally{$app->db->pdo->exec('DROP TEMPORARY TABLE fixture_trial_settings');$app->db->pdo->exec('DROP TEMPORARY TABLE fixture_trial_publications');}
        }
    };
    $tests['global trial control HTTP mutations require owner CSRF and current revision without changing other trial settings']=function()use($app,$owner,$http,$password):void {
        $session=$app->auth->login(['email'=>$owner['email'],'password'=>$password],'127.0.0.243');$headers=['Cookie'=>'control_session='.$session['token'],'X-CSRF-Token'=>$session['csrf']];$before=$app->trialOffers->globalSettings($owner);$saved=$app->db->all('SELECT * FROM trial_offer_publications ORDER BY tenant_id');
        $tenant=$app->management->createReseller($owner,['company'=>'TEST trial controls','name'=>'TEST trial switch','email'=>'trial-switch@example.test','password'=>$password]);$reseller=$app->auth->login(['email'=>'trial-switch@example.test','password'=>$password],'127.0.0.244');$other=['Cookie'=>'control_session='.$reseller['token'],'X-CSRF-Token'=>$reseller['csrf']];
        same(401,$http->request('GET','/api/trials/settings',null,[],'global-anonymous')['status']);same(403,$http->request('GET','/api/trials/settings',null,$other,'global-reseller')['status']);same(403,$http->request('POST','/api/trials/settings',['enabled'=>false,'expected_revision'=>$before['revision']],['Cookie'=>$headers['Cookie']],'global-no-csrf')['status']);
        try{$reply=$http->request('POST','/api/trials/settings',['enabled'=>false,'expected_revision'=>$before['revision']],$headers,'global-owner');same(200,$reply['status']);same(false,$reply['body']['enabled']);same(false,$reply['body']['effective_enabled']);$revision=$reply['body']['revision'];same(403,$http->request('POST','/api/trials/settings',['enabled'=>true,'expected_revision'=>$revision],$other,'global-reseller')['status']);same(409,$http->request('POST','/api/trials/settings',['enabled'=>true,'expected_revision'=>$before['revision']],$headers,'global-owner')['status']);same(422,$http->request('POST','/api/trials/settings',['enabled'=>true,'expected_revision'=>$revision,'tenant_id'=>$tenant['id']],$headers,'global-owner')['status']);same($saved,$app->db->all('SELECT * FROM trial_offer_publications ORDER BY tenant_id'));}
        finally{$current=$app->trialOffers->globalSettings($owner);$app->trialOffers->setGlobal($owner,['enabled'=>$before['enabled'],'expected_revision'=>$current['revision']]);}
        same(false,$app->trialOffers->globalSettings($owner)['effective_enabled']);
    };
}
