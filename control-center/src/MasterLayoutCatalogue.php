<?php
declare(strict_types=1);
namespace ControlCenter;

/** Global names-only catalogue; the source token never leaves owner-only APIs. */
final class MasterLayoutCatalogue
{
    public function __construct(private readonly Config $config,private readonly ?\Closure $transport=null){}
    private function stored():array {
        $path=$this->config->privateFile('master-layout.json');
        return is_file($path)?json_decode(file_get_contents($path),true,16,JSON_THROW_ON_ERROR):['version'=>0,'entries'=>[],'updated_at'=>null];
    }
    public function catalogue(array $actor):array {
        if(!in_array($actor['role']??null,['owner','reseller'],true))throw new Problem(403,'Access denied.');
        $data=$this->stored();
        if(!$data['entries'])throw new Problem(409,'The owner needs to configure the master category catalogue first.');
        return ['version'=>$data['version'],'entries'=>$data['entries'],'updated_at'=>$data['updated_at']];
    }
    public function master(array $actor):array {
        Auth::owner($actor);$data=$this->stored();
        return ['version'=>$data['version'],'configured'=>isset($data['source']),'category_count'=>count($data['entries']),'updated_at'=>$data['updated_at'],
            'url'=>isset($data['source'])?(new Secrets($this->config))->decrypt($data['source']):''];
    }
    public function save(array $actor,array $input):array {
        Auth::owner($actor);Input::keys($input,['expected_version','url']);
        $version=Input::integer($input['expected_version']??null,0,PHP_INT_MAX);
        $prior=$this->stored();if($version!==$prior['version'])throw new Problem(409,'Master catalogue changed. Reload before saving.');
        $url=$input['url']??null;
        if(!is_string($url)||strlen($url)>4096||preg_match('/[\x00-\x20\x7f]/',$url))throw new Problem(422,'Enter a valid master playlist URL.');
        $parts=parse_url($url);
        if(!$parts||($parts['scheme']??'')!=='https'||($parts['host']??'')!=='ocpanelserver.extremeiptv.net'||!str_starts_with($parts['path']??'','/playlist/')||isset($parts['user'])||isset($parts['pass'])||isset($parts['fragment'])||(isset($parts['port'])&&$parts['port']!==443))throw new Problem(422,'Use an HTTPS playlist link from the configured IPTV panel.');
        $entries=$this->download($url);
        $document=['version'=>$version+1,'updated_at'=>gmdate('c'),'entries'=>$entries,'source'=>(new Secrets($this->config))->encrypt($url)];
        $path=$this->config->privateFile('master-layout.json');$mask=umask(0077);
        try{$lock=fopen($this->config->privateFile('master-layout.lock'),'c');}finally{umask($mask);}
        if(!$lock)throw new Problem(503,'Master catalogue storage unavailable.');
        $temp=null;
        try{
            if(!flock($lock,LOCK_EX))throw new Problem(503,'Master catalogue storage unavailable.');
            if($this->stored()['version']!==$version)throw new Problem(409,'Master catalogue changed. Reload before saving.');
            $temp=tempnam(dirname($path),'.master-layout-');chmod($temp,0600);
            if(file_put_contents($temp,json_encode($document,JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR))===false||!rename($temp,$path))throw new Problem(503,'Could not save the master catalogue.');
        }finally{if($temp&&is_file($temp))unlink($temp);flock($lock,LOCK_UN);fclose($lock);}
        return ['version'=>$document['version'],'category_count'=>count($entries),'updated_at'=>$document['updated_at']];
    }
    private function download(string $url):array {
        $buffer='';$bytes=0;$groups=[];$header=false;$error=false;
        $line=static function(string $text)use(&$groups,&$header):void {
            $text=trim($text,"\r\n");
            if(!$header){if(trim($text)==='')return;if(!str_starts_with(ltrim($text,"\xEF\xBB\xBF"),'#EXTM3U'))throw new \RuntimeException();$header=true;return;}
            if(!str_starts_with($text,'#EXTINF:'))return;
            if(!preg_match('/\bgroup-title="([^"]*)"/u',$text,$match)||trim($match[1])==='')return;
            $name=CommandCategories::portableName($match[1]);$groups[$name]=['name'=>$name,'hidden'=>false];
            if(count($groups)>2000)throw new \RuntimeException();
        };
        $consume=static function(string $chunk)use(&$buffer,&$bytes,&$error,$line):int {
            $bytes+=strlen($chunk);if($bytes>100*1024*1024){$error=true;return 0;}
            try{
                $buffer.=$chunk;
                while(($end=strpos($buffer,"\n"))!==false){$line(substr($buffer,0,$end));$buffer=substr($buffer,$end+1);}
                if(strlen($buffer)>65536)throw new \RuntimeException();
                return strlen($chunk);
            }catch(\Throwable){$error=true;return 0;}
        };
        try{
            if($this->transport){$status=($this->transport)($url,$consume);$ok=true;}
            else{
                if(!function_exists('curl_init'))throw new \RuntimeException();$curl=curl_init($url);
                curl_setopt_array($curl,[CURLOPT_FOLLOWLOCATION=>false,CURLOPT_CONNECTTIMEOUT=>5,CURLOPT_TIMEOUT=>90,CURLOPT_SSL_VERIFYPEER=>true,CURLOPT_SSL_VERIFYHOST=>2,CURLOPT_PROTOCOLS=>CURLPROTO_HTTPS,CURLOPT_WRITEFUNCTION=>static fn($c,$chunk)=>$consume($chunk)]);
                $ok=curl_exec($curl);$status=curl_getinfo($curl,CURLINFO_RESPONSE_CODE);curl_close($curl);
            }
            if($buffer!=='')$line($buffer);
            if($ok===false||$status!==200||$error||!$header||!$groups)throw new \RuntimeException();
            return array_values($groups);
        }catch(\Throwable){throw new Problem(503,'Master playlist could not be read. Check the owner-only source link and retry; the previous catalogue was retained.');}
    }
}
