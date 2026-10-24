<?php
declare(strict_types=1);
namespace ControlCenter;

/** Local immutable APK delivery. Never redirects clients to the build provider. */
final class BrandDownloads
{
    public function __construct(private readonly Config $config,private readonly BrandArtifacts $artifacts) {}
    public function enabled():bool { return ($this->config->get('brand_downloads',[])['enabled']??false)===true; }
    private function directory():string { return $this->config->privateFile('brand-apks'); }
    public function file(array $receipt):string {
        $hash=$receipt['apk_sha256']??'';
        if(!is_string($hash)||!preg_match('/^[a-f0-9]{64}$/D',$hash))throw new Problem(409,'Invalid APK receipt.');
        return $this->directory().'/'.$hash.'.apk';
    }
    public function available(array $artifact):bool {
        if(!$this->enabled()||!isset($artifact['apk_size_bytes']))return false;
        $path=$this->file($artifact);clearstatcache(true,$path);
        return !is_link($path)&&is_file($path)&&filesize($path)===(int)$artifact['apk_size_bytes'];
    }
    public function publicInfo(array $artifact):array {
        return ['channel'=>$artifact['channel'],'version_name'=>$artifact['version_name'],'version_code'=>(int)$artifact['version_code'],
            'package_name'=>$artifact['package_name'],'apk_sha256'=>$artifact['apk_sha256'],
            'artifact_url'=>'/downloads/apps/'.$artifact['id'].'/Brand-'.$artifact['version_name'].'.apk',
            'download_ready'=>$this->available($artifact)];
    }
    /** stream is trusted CLI stdin, bounded by the server's frozen size/digest. */
    public function ingest(array $receipt,$stream,callable $install):array {
        if(!$this->enabled())throw new Problem(503,'Site APK delivery is disabled.');
        $size=$receipt['apk_size_bytes']??0;
        if(!is_int($size)||$size<1024||$size>536870912)throw new Problem(409,'Invalid APK size.');
        $directory=$this->directory();if(!is_dir($directory)&&!mkdir($directory,0700,true)&&!is_dir($directory))throw new Problem(503,'APK storage is unavailable.');
        $temp=tempnam($directory,'.upload-');if($temp===false)throw new Problem(503,'APK storage is unavailable.');chmod($temp,0600);
        $out=null;
        try {
            $out=fopen($temp,'wb');$hash=hash_init('sha256');$received=0;
            while(!feof($stream)) {
                $chunk=fread($stream,min(1048576,$size-$received+1));
                if($chunk===false||($chunk===''&&!feof($stream)))throw new Problem(503,'APK transfer stopped.');
                $received+=strlen($chunk);if($received>$size)throw new Problem(422,'APK transfer exceeds approved size.');
                hash_update($hash,$chunk);$offset=0;
                while($offset<strlen($chunk)){$written=fwrite($out,substr($chunk,$offset));if($written===false||$written===0)throw new Problem(503,'APK storage is full.');$offset+=$written;}
            }
            if($received!==$size||!hash_equals($receipt['apk_sha256'],hash_final($hash)))throw new Problem(409,'APK transfer integrity mismatch.');
            // Only APK bytes (already destined for public download) get group read;
            // setup provisions a dedicated shared group/default ACL for PHP/bridge.
            chmod($temp,0640);
            if(!fflush($out)||!fsync($out))throw new Problem(503,'APK storage could not be committed.');fclose($out);$out=null;
            // Revalidate the lease under the database lock AFTER streaming, before rename.
            $install(function()use($temp,$receipt):void {
                $target=$this->file($receipt);
                if(file_exists($target)||is_link($target)){
                    if(is_link($target)||!is_file($target)||filesize($target)!==$receipt['apk_size_bytes']||!hash_equals($receipt['apk_sha256'],hash_file('sha256',$target)))throw new Problem(409,'Immutable APK storage conflict.');
                }elseif(!rename($temp,$target))throw new Problem(503,'APK storage could not be committed.');
            });
            return ['stored'=>true,'apk_sha256'=>$receipt['apk_sha256'],'apk_size_bytes'=>$size];
        } finally {if(is_resource($out))fclose($out);if(is_file($temp))unlink($temp);}
    }
    public function metadata(string $brand,string $channel):array {
        Input::id($brand);if(!in_array($channel,['testing','production'],true))throw new Problem(404,'Update not available.');
        $artifact=$this->artifacts->approved($brand,$channel);
        if(!$artifact||!$this->available($artifact))throw new Problem(404,'Update not available.');
        $info=$this->publicInfo($artifact);$version=$artifact['version_name'];
        return ['tag_name'=>$version,'name'=>$version,'body'=>'App update. Your saved settings are retained.',
            'html_url'=>null,'draft'=>false,'prerelease'=>$channel==='testing',
            'assets'=>[['name'=>'Brand-'.$version.'.apk','browser_download_url'=>$this->config->origin().$info['artifact_url'],'size'=>(int)$artifact['apk_size_bytes']]]];
    }
    /** Single bounded Range only. Null means full body. */
    public static function range(?string $header,int $size):?array {
        if($header===null)return null;
        if(!preg_match('/^bytes=(\d*)-(\d*)$/D',$header,$m)||($m[1]===''&&$m[2]===''))throw new Problem(416,'Requested range is unavailable.');
        if(strlen($m[1])>18||strlen($m[2])>18)throw new Problem(416,'Requested range is unavailable.');
        if($m[1]===''){$suffix=(int)$m[2];if($suffix<1)throw new Problem(416,'Requested range is unavailable.');return [max(0,$size-$suffix),$size-1];}
        $start=(int)$m[1];$end=$m[2]===''?$size-1:min($size-1,(int)$m[2]);
        if($start>=$size||$end<$start)throw new Problem(416,'Requested range is unavailable.');return [$start,$end];
    }
    public function serve(string $id,string $filename,bool $head=false):never {
        $artifact=$this->artifacts->byId($id);
        if(!$artifact||$filename!=='Brand-'.$artifact['version_name'].'.apk'||!$this->available($artifact))throw new Problem(404,'Download not available.');
        $size=(int)$artifact['apk_size_bytes'];$etag='"'.$artifact['apk_sha256'].'"';
        header('Content-Type: application/vnd.android.package-archive');header('Content-Disposition: attachment; filename="'.$filename.'"');
        header('Accept-Ranges: bytes');header('ETag: '.$etag);header('Cache-Control: private, no-store');
        if(($this->config->get('brand_downloads',[])['x_accel']??false)===true){header('X-Accel-Redirect: /_brand_apks/'.$artifact['apk_sha256'].'.apk');exit;}
        $rangeHeader=$_SERVER['HTTP_RANGE']??null;
        if(isset($_SERVER['HTTP_IF_RANGE'])&&$_SERVER['HTTP_IF_RANGE']!==$etag)$rangeHeader=null;
        try{$range=self::range($rangeHeader,$size);}catch(Problem $e){header('Content-Range: bytes */'.$size);http_response_code(416);header('Content-Length: 0');exit;}
        [$start,$end]=$range??[0,$size-1];if($range!==null){http_response_code(206);header("Content-Range: bytes $start-$end/$size");}
        header('Content-Length: '.($end-$start+1));if($head)exit;
        $stream=fopen($this->file($artifact),'rb');if(!$stream)throw new Problem(503,'Download temporarily unavailable.');
        try{if(fseek($stream,$start)!==0)throw new Problem(503,'Download temporarily unavailable.');$remaining=$end-$start+1;
            while($remaining>0&&!connection_aborted()){$chunk=fread($stream,min(1048576,$remaining));if($chunk===false||$chunk==='')break;echo $chunk;$remaining-=strlen($chunk);}
        }finally{fclose($stream);}exit;
    }
}
