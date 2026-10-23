<?php
declare(strict_types=1);
namespace ControlCenter;

/** Bounded fixed-code support exports and customer-consented safe-panel PNGs. */
final class Support
{
    public const EVENTS=['LIVE_TUNE_REQUEST','LIVE_FAST_STARTED','LIVE_MPV_STARTED','LIVE_RECOVERY','LIVE_STOP','GUIDE_REFRESH_REQUESTED','GUIDE_REFRESH_COMPLETED','GUIDE_REFRESH_FAILED','GUIDE_REFRESH_CANCELLED','PLAYLIST_REFRESH_OK','COMMAND_ACCEPTED','RECEIPT_SUCCEEDED','RECEIPT_FAILED','CACHE_CLEAR_PARTIAL','REOPEN_REQUESTED'];
    private readonly Secrets $secrets;
    public function __construct(private readonly Database $db,private readonly Management $management,private readonly Commands $commands,private readonly Auth $auth,Config $config) {$this->secrets=new Secrets($config);}
    public static function bundle(mixed $doc):array {
        if(!is_array($doc))throw new Problem(422,'Invalid support bundle.');
        Input::keys($doc,['schema_version','profile_ref','snapshot','events']);if(($doc['schema_version']??null)!==1)throw new Problem(422,'Invalid support schema.');EnrollmentProof::hex($doc['profile_ref']??null,64);$doc['snapshot']=CommandSnapshots::validate('get_diagnostics',$doc['snapshot']??null);
        if($doc['snapshot']['profile_ref']!==$doc['profile_ref']||!is_array($doc['events']??null)||!array_is_list($doc['events'])||count($doc['events'])>128)throw new Problem(422,'Invalid support events/profile.');
        foreach($doc['events'] as $event){if(!is_array($event))throw new Problem(422,'Invalid support event.');Input::keys($event,['code','at_ms']);if(!in_array($event['code']??null,self::EVENTS,true)||!is_int($event['at_ms']??null)||$event['at_ms']<0||$event['at_ms']>4102444800000)throw new Problem(422,'Invalid support event code/time.');}
        $doc['event_scope']='device_account';return $doc;
    }
    public static function png(string $encoded):string {
        if(strlen($encoded)>175000)throw new Problem(413,'Support image is too large.');$bytes=base64_decode($encoded,true);
        if($bytes===false||strlen($bytes)>131072||!str_starts_with($bytes,"\x89PNG\r\n\x1a\n"))throw new Problem(422,'Only a bounded PNG support panel is accepted.');
        $offset=8;$width=0;$height=0;$channels=0;$data='';$ended=false;
        while($offset<strlen($bytes)) {
            if($offset+12>strlen($bytes))throw new Problem(422,'Invalid PNG structure.');$length=unpack('N',substr($bytes,$offset,4))[1];$type=substr($bytes,$offset+4,4);if($length>131072||$offset+12+$length>strlen($bytes)||!in_array($type,['IHDR','IDAT','IEND','sBIT','sRGB','gAMA','cHRM','pHYs'],true))throw new Problem(422,'Unsupported PNG metadata or animation.');
            $body=substr($bytes,$offset+8,$length);$crc=substr($bytes,$offset+8+$length,4);if(!hash_equals(hash('crc32b',$type.$body,true),$crc))throw new Problem(422,'Invalid PNG checksum.');
            if(in_array($type,['sBIT','sRGB','gAMA','cHRM','pHYs'],true)&&$length!==(['sBIT'=>$channels,'sRGB'=>1,'gAMA'=>4,'cHRM'=>32,'pHYs'=>9][$type]))throw new Problem(422,'Invalid PNG ancillary data.');
            if($type==='IHDR') {if($offset!==8||$length!==13)throw new Problem(422,'Invalid PNG header.');$dim=unpack('Nwidth/Nheight/Cdepth/Ccolour/Ccompression/Cfilter/Cinterlace',$body);$width=$dim['width'];$height=$dim['height'];$channels=[0=>1,2=>3,4=>2,6=>4][$dim['colour']]??0;if($width<1||$height<1||$width>512||$height>288||$dim['depth']!==8||!$channels||$dim['compression']!==0||$dim['filter']!==0||$dim['interlace']!==0)throw new Problem(422,'Invalid support image dimensions/format.');}
            if($type==='IDAT')$data.=$body;
            $offset+=12+$length;if($type==='IEND'){if($length!==0||$offset!==strlen($bytes))throw new Problem(422,'PNG must not contain trailing content.');$ended=true;break;}
        }
        if(!$ended||!$width||!$height||$data==='')throw new Problem(422,'Incomplete PNG.');if(!function_exists('gzuncompress'))throw new Problem(503,'PHP zlib is required to validate support PNGs.');$expected=($width*$channels+1)*$height;$decoded=@gzuncompress($data,$expected+1);if($decoded===false||strlen($decoded)!==$expected)throw new Problem(422,'Invalid/oversized decoded support image.');
        $inflate=inflate_init(ZLIB_ENCODING_DEFLATE);if(@inflate_add($inflate,$data,ZLIB_FINISH)===false||inflate_get_read_len($inflate)!==strlen($data))throw new Problem(422,'PNG compressed data has trailing content.');
        $stride=$width*$channels+1;for($row=0;$row<$height;$row++)if(ord($decoded[$row*$stride])>4)throw new Problem(422,'Invalid PNG row filter.');return $bytes;
    }
    public function upload(string $token,array $input):array {
        Input::keys($input,['command_id','lease_token','kind','profile_ref','document','png_base64','consented']);[$d,$c]=$this->commands->leasedDeviceCommand($token,$input['command_id']??'',$input['lease_token']??'',['get_support_bundle','capture_app_screen']);$this->auth->limit('support-upload',$d['id'],4,60);EnrollmentProof::hex($input['profile_ref']??null,64);
        if($c['kind']==='get_support_bundle') {if(($input['kind']??null)!=='bundle'||isset($input['png_base64'])||isset($input['consented']))throw new Problem(422,'Invalid support bundle.');$doc=self::bundle($input['document']??[]);if($doc['profile_ref']!==$input['profile_ref'])throw new Problem(422,'Support profile differs.');$plain=json_encode($doc,JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR);if(strlen($plain)>32768)throw new Problem(413,'Support bundle is too large.');$kind='bundle';}
        else {if(($input['kind']??null)!=='panel'||($input['consented']??null)!==true||isset($input['document'])||!is_string($input['png_base64']??null))throw new Problem(422,'A support panel needs explicit customer consent.');$plain=self::png($input['png_base64']);$kind='panel';}
        return $this->db->transaction(function()use($token,$d,$c,$input,$plain,$kind):array {
            $this->db->one('SELECT id FROM devices WHERE id=? FOR UPDATE',[$d['id']]);$this->commands->leasedDeviceCommand($token,$c['id'],$input['lease_token'],[$c['kind']]);$old=$this->db->one('SELECT id FROM support_artifacts WHERE command_id=?',[$c['id']]);if($old)return ['id'=>$old['id'],'stored'=>true];
            $id=Database::id();$this->db->query('INSERT INTO support_artifacts (id,command_id,device_id,profile_ref,kind,bytes,payload_cipher,expires_at) VALUES (?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 7 DAY))',[$id,$c['id'],$d['id'],$input['profile_ref'],$kind,strlen($plain),$this->secrets->encrypt($plain)]);
            $keep=$this->db->all('SELECT id FROM support_artifacts WHERE device_id=? ORDER BY created_at DESC,id DESC LIMIT 15',[$d['id']]);$ids=array_column($keep,'id');$marks=implode(',',array_fill(0,count($ids),'?'));$this->db->query("DELETE FROM support_artifacts WHERE device_id=? AND id NOT IN ($marks)",array_merge([$d['id']],$ids));$this->auth->audit(null,'support.artifact_saved',$id,$d['tenant_id'],['kind'=>$kind,'bytes'=>strlen($plain)]);return ['id'=>$id,'stored'=>true];
        });
    }
    public function listing(array $actor,string $device):array {$this->management->device($actor,$device);return ['items'=>$this->db->all('SELECT id,command_id,profile_ref,kind,bytes,created_at,expires_at FROM support_artifacts WHERE device_id=? AND expires_at>UTC_TIMESTAMP() ORDER BY created_at DESC,id DESC LIMIT 15',[$device]),'retention'=>'Private/encrypted, 7 days, 15 artifacts/device. Event times are device-reported. Panel images exclude TV video and sensitive pages.'];}
    public function download(array $actor,string $device,string $id):array {
        $d=$this->management->device($actor,$device);$this->auth->limit('support-download',$actor['id'],20,60);$r=$this->db->one('SELECT * FROM support_artifacts WHERE id=? AND device_id=? AND expires_at>UTC_TIMESTAMP()',[Input::id($id),$device]);if(!$r)throw new Problem(404,'Support artifact not found or expired.');$this->auth->audit($actor,'support.artifact_downloaded',$id,$d['tenant_id'],['kind'=>$r['kind']]);return ['body'=>$this->secrets->decrypt($r['payload_cipher']),'mime'=>$r['kind']==='panel'?'image/png':'application/json','filename'=>'extreme-support-'.$id.($r['kind']==='panel'?'.png':'.json')];
    }
    public function maintain():void {$this->db->query('DELETE FROM support_artifacts WHERE expires_at<=UTC_TIMESTAMP()');}
}
