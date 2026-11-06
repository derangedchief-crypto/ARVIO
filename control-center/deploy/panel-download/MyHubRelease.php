<?php
declare(strict_types=1);
namespace AmAccount;

/** Public release metadata only; the controller checks reseller entitlement. */
final class MyHubRelease
{
    public static function select(array $release):array
    {
        if(($release['tag_name']??null)!=='staging-testing'||($release['draft']??true)!==false)throw new \RuntimeException('Release unavailable.');
        $latest=null;
        foreach($release['assets']??[] as $asset){
            if(!is_array($asset)||!preg_match('/^My-Hub-([0-9]+\.[0-9]+\.[0-9]+)-testing\.apk$/D',$asset['name']??'',$match))continue;
            $url='https://github.com/derangedchief-crypto/ARVIO/releases/download/staging-testing/'.$asset['name'];
            if(($asset['state']??null)!=='uploaded'||($asset['size']??0)<=0||($asset['browser_download_url']??null)!==$url)continue;
            if($latest===null||version_compare($match[1],$latest['version'],'>'))$latest=['version'=>$match[1],'url'=>$url];
        }
        if($latest===null)throw new \RuntimeException('No published My Hub APK.');
        return $latest;
    }

    public static function latest(array $config):array
    {
        $database=$config['my_hub_database']??'';
        if(!is_string($database)||$database==='')throw new \RuntimeException('My Hub not configured.');
        $cache=dirname($database).'/my-hub-release.json';
        if(is_file($cache)&&!is_link($cache)&&time()-filemtime($cache)>=0&&time()-filemtime($cache)<300){
            try{return self::select(json_decode(file_get_contents($cache),true,32,JSON_THROW_ON_ERROR));}catch(\Throwable){}
        }
        if(!function_exists('curl_init'))throw new \RuntimeException('Release connection unavailable.');
        // Fixed published staging-testing release. Its full release document is
        // large enough to time out anonymously; enumerate its asset pages.
        $assets=[];
        for($page=1;$page<=20;$page++){
            $rows=self::assetPage($page);$assets=array_merge($assets,$rows);
            if(count($rows)<100)break;
        }
        if($page>20)throw new \RuntimeException('Release listing is incomplete.');
        $release=['tag_name'=>'staging-testing','draft'=>false,'assets'=>$assets];
        $result=self::select($release);$body=json_encode($release,JSON_THROW_ON_ERROR);
        // A short private cache bounds GitHub requests. Never use an expired
        // cache on failure: do not silently hand out an older build.
        $tmp=@tempnam(dirname($cache),'.hub-release-');
        if($tmp!==false){@chmod($tmp,0600);if(@file_put_contents($tmp,$body)!==false)@rename($tmp,$cache);if(is_file($tmp))@unlink($tmp);}
        return $result;
    }

    private static function assetPage(int $page):array
    {
        $body='';$curl=curl_init('https://api.github.com/repos/derangedchief-crypto/ARVIO/releases/414147175/assets?per_page=100&page='.$page);
        curl_setopt_array($curl,[CURLOPT_HTTPHEADER=>['Accept: application/vnd.github+json','X-GitHub-Api-Version: 2022-11-28'],CURLOPT_USERAGENT=>'MyHub-Panel-Download',CURLOPT_CONNECTTIMEOUT=>3,CURLOPT_TIMEOUT=>10,CURLOPT_FOLLOWLOCATION=>false,CURLOPT_SSL_VERIFYPEER=>true,CURLOPT_SSL_VERIFYHOST=>2,CURLOPT_PROTOCOLS=>CURLPROTO_HTTPS,CURLOPT_WRITEFUNCTION=>static function($c,$chunk)use(&$body){if(strlen($body)+strlen($chunk)>4194304)return 0;$body.=$chunk;return strlen($chunk);}]);
        $ok=curl_exec($curl);$status=curl_getinfo($curl,CURLINFO_RESPONSE_CODE);curl_close($curl);
        if($ok===false||$status!==200)throw new \RuntimeException('Release lookup unavailable.');
        $rows=json_decode($body,true,32,JSON_THROW_ON_ERROR);
        if(!is_array($rows)||!array_is_list($rows)||count($rows)>100)throw new \RuntimeException('Invalid release listing.');
        return $rows;
    }
}
