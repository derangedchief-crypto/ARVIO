<?php
declare(strict_types=1);
function masterLayoutCases(array &$tests,ControlCenter\Config $config,array $owner,array $alpha):void {
    $tests['master catalogue is private, encrypted, streamed and independent of customer devices']=function()use($config,$owner,$alpha):void {
        $path=$config->privateFile('master-layout.json');$prior=is_file($path)?file_get_contents($path):null;if($prior!==null)unlink($path);
        $url='https://ocpanelserver.extremeiptv.net/playlist/fixture-private-token?type=full';$calls=0;$broken=false;
        $catalogue=new ControlCenter\MasterLayoutCatalogue($config,function($received,$consume)use($url,&$calls,&$broken):int {
            same($url,$received);$calls++;
            $m3u=$broken?'not a playlist':"#EXTM3U\n#EXTINF:-1 group-title=\"Sports\",One\nhttps://private.example/secret-stream\n#EXTINF:-1 group-title=\"Premium\",Two\nhttps://private.example/secret-stream\n#EXTINF:-1 group-title=\"News\",Three\nhttps://private.example/secret-stream\n#EXTINF:-1 group-title=\"Sports\",Four\nhttps://private.example/secret-stream\n";
            foreach(str_split($m3u,17) as $chunk)if(!$consume($chunk))break;return 200;
        });
        try {
            problem(403,fn()=>$catalogue->master($alpha));problem(403,fn()=>$catalogue->save($alpha,['url'=>$url,'expected_version'=>0]));same(0,$calls);
            problem(409,fn()=>$catalogue->catalogue($alpha));
            same(3,$catalogue->save($owner,['url'=>$url,'expected_version'=>0])['category_count']);
            $public=$catalogue->catalogue($alpha);same(['Sports','Premium','News'],array_column($public['entries'],'name'));
            check(!str_contains(json_encode($public),'private-token')&&!str_contains(json_encode($public),'secret-stream'),'No source or stream URLs in reseller response');
            check(!str_contains(file_get_contents($path),'fixture-private-token'),'Source encrypted at rest');same($url,$catalogue->master($owner)['url']);
            same(1,$calls);$catalogue->catalogue($alpha);same(1,$calls);
            problem(409,fn()=>$catalogue->save($owner,['url'=>$url,'expected_version'=>0]));same(1,$calls);
            problem(422,fn()=>$catalogue->save($owner,['url'=>'https://other.example/playlist/private','expected_version'=>1]));same(1,$calls);
            $broken=true;problem(503,fn()=>$catalogue->save($owner,['url'=>$url,'expected_version'=>1]));same($public,$catalogue->catalogue($alpha));
            $customer=['profile_ref'=>str_repeat('a',64),'source_ref'=>str_repeat('b',64),'revision'=>str_repeat('c',64),'items'=>[
                ['ref'=>'news','match_name'=>'News','hidden'=>false],['ref'=>'sport','match_name'=>'Sports','hidden'=>false],['ref'=>'extra','match_name'=>'Customer extra','hidden'=>false]]];
            $matched=ControlCenter\DeviceProfiles::matchLayout($customer,$public['entries']);same(['sport','news','extra'],$matched['payload']['order']);same(['Premium'],$matched['missing']);
        }finally{if($prior===null){if(is_file($path))unlink($path);}else file_put_contents($path,$prior);}
    };
}
