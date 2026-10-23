<?php
declare(strict_types=1);
namespace ControlCenter;

/** Sensitive documents stay encrypted. Browser views contain labels/refs only. */
final class Sources
{
    private readonly Secrets $secrets;
    public function __construct(private readonly Database $db,private readonly Management $management,private readonly Commands $commands,private readonly Auth $auth,private readonly Config $config) {$this->secrets=new Secrets($config);}
    private function decode(string $cipher):array {return json_decode($this->secrets->decrypt($cipher),true,24,JSON_THROW_ON_ERROR);}
    private function encode(array $doc):string {return $this->secrets->encrypt(json_encode($doc,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES|JSON_THROW_ON_ERROR));}
    private function ref(string $device,string $profile,string $kind,string $id):string {return $this->secrets->mac("source-v1\n$device\n$profile\n$kind\n$id");}
    private static function label(mixed $value):string {return mb_substr(Secrets::redact(Input::text($value,'source label',80)),0,80);}
    private static function safeName(array $config,string $label):string {
        $secrets=[];$urls=[$config['m3uUrl'],$config['epgUrl']];
        foreach($config['playlists'] as $p){$urls[]=$p['m3uUrl'];$urls[]=$p['epgUrl'];array_push($urls,...$p['epgUrls']);}
        foreach($config['stalkerPortals'] as $p){$secrets[]=$p['macAddress'];$urls[]=$p['portalUrl'];}
        foreach($urls as $url){$query=parse_url($url,PHP_URL_QUERY);if(!is_string($query))continue;foreach(explode('&',$query) as $pair){$parts=explode('=',$pair,2);$key=strtolower(urldecode($parts[0]));$value=isset($parts[1])?urldecode($parts[1]):'';if(in_array($key,['username','password','token','key','api_key','auth','authorization','secret'],true)&&$value!=='')$secrets[]=$value;}}
        foreach(array_unique($secrets) as $secret)if(mb_stripos($label,$secret)!==false)return '[private source label]';return self::label($label);
    }
    private static function lists(array $config):array {return $config['playlists']?:[['id'=>'list_1','name'=>'List 1','m3uUrl'=>$config['m3uUrl'],'epgUrl'=>$config['epgUrl'],'epgUrls'=>$config['epgUrl']===''?[]:[$config['epgUrl']],'enabled'=>true]];}
    private static function url(mixed $value,bool $empty=false):string {
        if($empty&&$value==='')return '';
        if(!is_string($value)||strlen($value)>4096||preg_match('/[\x00-\x20\x7f]/',$value))throw new Problem(422,'Invalid source/guide URL.');
        $p=parse_url($value);if(!$p||!in_array($p['scheme']??null,['https','http'],true)||empty($p['host'])||isset($p['user'])||isset($p['pass']))throw new Problem(422,'Only HTTP/HTTPS source/guide URLs without user-info are supported.');return $value;
    }
    public static function config(mixed $config,bool $forApply=false):array {
        if(!is_array($config))throw new Problem(422,'Invalid source configuration.');
        Input::keys($config,['m3uUrl','epgUrl','playlists','stalkerPortals','sortOrder']);
        if(($config['sortOrder']??null)!=='provider'||!is_array($config['playlists']??null)||!array_is_list($config['playlists'])||count($config['playlists'])>3||!is_array($config['stalkerPortals']??null)||!array_is_list($config['stalkerPortals'])||count($config['stalkerPortals'])>3)throw new Problem(422,'Invalid source configuration.');
        $ids=[];
        foreach($config['playlists'] as &$p) {
            if(!is_array($p))throw new Problem(422,'Invalid playlist.');Input::keys($p,['id','name','m3uUrl','epgUrl','enabled','epgUrls']);
            if(!is_string($p['id']??null)||!preg_match('/^[A-Za-z0-9_-]{1,64}$/D',$p['id'])||isset($ids[$p['id']])||!is_bool($p['enabled']??null))throw new Problem(422,'Invalid/duplicate playlist identity.');$ids[$p['id']]=true;
            $p['name']=Input::text($p['name']??null,'source label',80);$p['m3uUrl']=self::url($p['m3uUrl']??null);$p['epgUrl']=self::url($p['epgUrl']??'',true);
            $guides=$p['epgUrls']??[];if(!is_array($guides)||!array_is_list($guides)||count($guides)>10)throw new Problem(422,'Too many guide URLs.');$p['epgUrls']=array_values(array_unique(array_map(static fn($v)=>self::url($v),$guides)));
        }unset($p);
        foreach($config['stalkerPortals'] as &$p) {
            if(!is_array($p))throw new Problem(422,'Invalid portal.');Input::keys($p,['id','name','portalUrl','macAddress','enabled']);
            if(!is_string($p['id']??null)||!preg_match('/^[A-Za-z0-9_-]{1,64}$/D',$p['id'])||isset($ids[$p['id']])||!is_bool($p['enabled']??null)||!is_string($p['macAddress']??null)||!preg_match('/^[a-f0-9]{2}(:[a-f0-9]{2}){5}$/iD',$p['macAddress']))throw new Problem(422,'Invalid/duplicate portal identity.');$ids[$p['id']]=true;
            $p['name']=Input::text($p['name']??null,'source label',80);$p['portalUrl']=rtrim(self::url($p['portalUrl']??null),'/');$p['macAddress']=strtoupper($p['macAddress']);
        }unset($p);
        self::url($config['m3uUrl']??null);self::url($config['epgUrl']??null,true);
        if($config['playlists']&&(($config['m3uUrl']??null)!==$config['playlists'][0]['m3uUrl']||($config['epgUrl']??null)!==$config['playlists'][0]['epgUrl']))throw new Problem(422,'Primary source aliases must agree.');
        if($forApply&&!array_filter(self::lists($config),static fn($p)=>$p['enabled']))throw new Problem(422,'At least one M3U/Xtream playlist must stay enabled; the app legacy fallback is unchanged.');return $config;
    }
    private function lease(string $token,mixed $id,mixed $lease):array {
        return $this->commands->leasedDeviceCommand($token,$id,$lease,['get_sources','apply_sources']);
    }
    private function snapshot(string $device,string $id):array {$r=$this->db->one('SELECT * FROM source_snapshots WHERE id=? AND device_id=?',[Input::id($id),$device]);if(!$r)throw new Problem(404,'Source checkpoint not found.');return $r;}
    private function draft(string $device,string $id):array {$r=$this->db->one('SELECT * FROM source_drafts WHERE id=? AND device_id=? AND expires_at>UTC_TIMESTAMP()',[Input::id($id),$device]);if(!$r)throw new Problem(409,'Source preview expired or belongs to another device.');return $r;}
    public function capture(string $token,array $input):array {
        Input::keys($input,['command_id','lease_token','reason','snapshot']);[$d,$c]=$this->lease($token,$input['command_id']??'',$input['lease_token']??'');$this->auth->limit('sources-upload',$d['id'],20,60);
        $reason=$input['reason']??null;if(!in_array($reason,$c['kind']==='get_sources'?['read']:['pre_apply','confirmation'],true))throw new Problem(422,'Invalid source snapshot phase.');
        $doc=$input['snapshot']??null;if(!is_array($doc))throw new Problem(422,'Invalid source snapshot.');Input::keys($doc,['schema_version','profile_ref','revision','config']);
        if(($doc['schema_version']??null)!==1)throw new Problem(422,'Invalid source schema.');EnrollmentProof::hex($doc['profile_ref']??null,64);EnrollmentProof::hex($doc['revision']??null,64);$doc['config']=self::config($doc['config']??[]);
        if($c['kind']==='apply_sources') {$p=json_decode($c['payload'],true,16,JSON_THROW_ON_ERROR);$draft=$this->draft($d['id'],$p['draft_id']);if($doc['profile_ref']!==$draft['profile_ref']||($reason==='pre_apply'&&$doc['revision']!==$draft['expected_revision']))throw new Problem(409,'Source checkpoint does not match the reviewed target.');
            if($reason==='confirmation'&&($doc['config']!=$this->decode($draft['payload_cipher'])||!$this->db->one("SELECT id FROM source_snapshots WHERE command_id=? AND reason='pre_apply'",[$c['id']])))throw new Problem(409,'Source confirmation requires the safety checkpoint and exact reviewed target.');}
        return $this->db->transaction(function()use($token,$d,$c,$doc,$reason,$input):array {
            $this->db->one('SELECT id FROM devices WHERE id=? FOR UPDATE',[$d['id']]);$this->lease($token,$c['id'],$input['lease_token']);
            $existing=$this->db->one('SELECT id FROM source_snapshots WHERE command_id=? AND reason=?',[$c['id'],$reason]);if($existing)return ['id'=>$existing['id']];
            $id=Database::id();$first=!$this->db->one('SELECT id FROM source_snapshots WHERE device_id=? AND profile_ref=?',[$d['id'],$doc['profile_ref']])&&(int)$this->db->query('SELECT COUNT(*) FROM source_snapshots WHERE device_id=? AND protected=1',[$d['id']])->fetchColumn()<10;
            $this->db->query('INSERT INTO source_snapshots (id,device_id,command_id,profile_ref,revision,reason,protected,payload_cipher) VALUES (?,?,?,?,?,?,?,?)',[$id,$d['id'],$c['id'],$doc['profile_ref'],$doc['revision'],$reason,(int)$first,$this->encode($doc)]);
            $keep=$this->db->all('SELECT id FROM source_snapshots WHERE device_id=? AND protected=0 ORDER BY created_at DESC,id DESC LIMIT 50',[$d['id']]);$ids=array_column($keep,'id');if($ids){$marks=implode(',',array_fill(0,count($ids),'?'));$this->db->query("DELETE FROM source_snapshots WHERE device_id=? AND protected=0 AND id NOT IN ($marks) AND NOT EXISTS (SELECT 1 FROM source_drafts x WHERE x.snapshot_id=source_snapshots.id AND x.expires_at>UTC_TIMESTAMP())",array_merge([$d['id']],$ids));}
            return ['id'=>$id,'stored'=>true];
        });
    }
    private function publicConfig(string $device,string $profile,array $config):array {
        $items=[];foreach(self::lists($config) as $index=>$p)$items[]=['ref'=>$this->ref($device,$profile,'m3u',$p['id']),'kind'=>'m3u','name'=>self::safeName($config,$p['name']),'enabled'=>$p['enabled'],'canonical'=>$index===0,'guide_count'=>count($p['epgUrls'])?:($p['epgUrl']===''?0:1)];
        foreach($config['stalkerPortals'] as $p)$items[]=['ref'=>$this->ref($device,$profile,'stalker',$p['id']),'kind'=>'stalker','name'=>self::safeName($config,$p['name']),'enabled'=>$p['enabled'],'canonical'=>false,'guide_count'=>0];return $items;
    }
    public function listing(array $actor,string $device):array {
        $this->management->device($actor,$device);$rows=$this->db->all('SELECT id,command_id,profile_ref,revision,reason,protected,created_at FROM source_snapshots WHERE device_id=? ORDER BY created_at DESC,id DESC LIMIT 60',[$device]);
        $this->commands->expire($device);$commands=$this->db->all("SELECT * FROM commands WHERE device_id=? AND kind IN ('get_sources','apply_sources') ORDER BY created_at DESC,id DESC LIMIT 10",[$device]);
        return ['items'=>$rows,'commands'=>array_map(Commands::publicCommand(...),$commands),'notice'=>'Up to 10 initial profile checkpoints are protected; 50 additional rolling versions are retained (90 days). URLs and credentials are never returned.'];
    }
    public function view(array $actor,string $device,string $id):array {$this->management->device($actor,$device);$row=$this->snapshot($device,$id);$doc=$this->decode($row['payload_cipher']);unset($row['payload_cipher']);$row['entries']=$this->publicConfig($device,$doc['profile_ref'],$doc['config']);return $row;}
    public function preview(array $actor,string $device,array $input):array {
        Input::keys($input,['snapshot_id','entries','rollback_id']);$d=$this->management->device($actor,$device);$this->auth->limit('sources-preview',$actor['id'],30,60);
        if((int)$this->db->query('SELECT COUNT(*) FROM source_drafts WHERE device_id=? AND expires_at>UTC_TIMESTAMP()',[$device])->fetchColumn()>=10)throw new Problem(409,'Ten source previews already active; wait for expiry before creating another.');
        $base=$this->snapshot($device,Input::id($input['snapshot_id']??null));if(strtotime($base['created_at'].' UTC')<time()-600)throw new Problem(409,'Read fresh sources from the app before editing.');$doc=$this->decode($base['payload_cipher']);$target=$doc['config'];$keptPrivate=[];$credentialEdit=false;
        if(isset($input['rollback_id'])) {
            if(isset($input['entries']))throw new Problem(422,'Choose draft editing or rollback.');$old=$this->snapshot($device,Input::id($input['rollback_id']));if($old['profile_ref']!==$base['profile_ref'])throw new Problem(409,'Rollback profile differs.');$target=$this->decode($old['payload_cipher'])['config'];
        } else {
            $entries=$input['entries']??null;if(!is_array($entries)||!array_is_list($entries)||count($entries)>6)throw new Problem(422,'Invalid source draft.');
            $known=[];foreach(['playlists'=>'m3u','stalkerPortals'=>'stalker'] as $field=>$kind)foreach(($field==='playlists'?self::lists($target):$target[$field]) as $p)$known[$this->ref($device,$doc['profile_ref'],$kind,$p['id'])]=['kind'=>$kind,'entry'=>$p];
            $lists=[];$portals=[];$seen=[];
            foreach($entries as $entry) {
                if(!is_array($entry))throw new Problem(422,'Invalid source draft row.');Input::keys($entry,['ref','kind','name','enabled','replace_url','replace_mac','replace_guides','xtream_host','xtream_username','xtream_password']);
                foreach(['replace_url','replace_mac','replace_guides','xtream_host','xtream_username','xtream_password'] as $privateField)if(array_key_exists($privateField,$entry))$credentialEdit=true;
                $ref=$entry['ref']??'';if($ref!=='')EnrollmentProof::hex($ref,64);$existing=$ref!==''?($known[$ref]??null):null;if($ref!==''&&(!$existing||isset($seen[$ref])))throw new Problem(422,'Unknown/duplicate source reference.');$seen[$ref?:Database::id()]=true;
                $kind=$entry['kind']??null;if(!in_array($kind,['m3u','stalker'],true)||($existing&&$existing['kind']!==$kind)||!is_bool($entry['enabled']??null))throw new Problem(422,'Invalid source type/status.');
                $p=$existing['entry']??($kind==='m3u'?['id'=>'managed_'.Database::id(),'m3uUrl'=>'','epgUrl'=>'','epgUrls'=>[]]:['id'=>'managed_'.Database::id(),'portalUrl'=>'','macAddress'=>'']);$name=Input::text($entry['name']??null,'source label',80);$p['name']=$existing&&$name===self::safeName($doc['config'],$p['name'])?$p['name']:$name;$p['enabled']=$entry['enabled'];
                if($existing&&$name===self::safeName($doc['config'],$existing['entry']['name'])&&$name!==$existing['entry']['name'])$keptPrivate[]=$p['id'];
                if(array_key_exists('replace_url',$entry))$p[$kind==='m3u'?'m3uUrl':'portalUrl']=$kind==='stalker'?rtrim(self::url($entry['replace_url']),'/'):self::url($entry['replace_url']);
                if(isset($entry['xtream_host'])||isset($entry['xtream_username'])||isset($entry['xtream_password'])) {
                    if($kind!=='m3u'||isset($entry['replace_url']))throw new Problem(422,'Invalid Xtream source replacement.');$host=rtrim(self::url($entry['xtream_host']??null),'/');$u=Input::text($entry['xtream_username']??null,'Xtream username',256);$pword=Input::text($entry['xtream_password']??null,'Xtream password',256);
                    $parts=parse_url($host);if(isset($parts['query'])||isset($parts['fragment']))throw new Problem(422,'Xtream host must not include query/fragment.');$p['m3uUrl']=$host.'/get.php?username='.rawurlencode($u).'&password='.rawurlencode($pword).'&type=m3u_plus&output=ts';
                    if(!preg_match('/^[A-Za-z0-9._~-]+$/D',$u)||!preg_match('/^[A-Za-z0-9._~-]+$/D',$pword))throw new Problem(422,'Remote Xtream replacements require URL-safe credentials; use the app verified/local workflow for reserved characters.');
                }
                if(array_key_exists('replace_mac',$entry)) {if($kind!=='stalker')throw new Problem(422,'MAC is only valid for portals.');$p['macAddress']=$entry['replace_mac'];}
                if(array_key_exists('replace_guides',$entry)) {if($kind!=='m3u'||!is_array($entry['replace_guides'])||!array_is_list($entry['replace_guides'])||count($entry['replace_guides'])>10)throw new Problem(422,'Invalid guide replacement.');$p['epgUrls']=array_map(static fn($v)=>self::url($v),$entry['replace_guides']);$p['epgUrl']=$p['epgUrls'][0]??'';}
                if($existing&&(($kind==='m3u'&&$p['m3uUrl']!==$existing['entry']['m3uUrl'])||($kind==='stalker'&&($p['portalUrl']!==$existing['entry']['portalUrl']||strtoupper($p['macAddress'])!==strtoupper($existing['entry']['macAddress'])))))$p['id']='managed_'.Database::id();
                if($kind==='m3u')$lists[]=$p;else $portals[]=$p;
            }
            $target=['m3uUrl'=>$lists[0]['m3uUrl']??'','epgUrl'=>$lists[0]['epgUrl']??'','playlists'=>$lists,'stalkerPortals'=>$portals,'sortOrder'=>'provider'];
        }
        $target=self::config($target,true);$primary=self::lists($doc['config'])[0];$nextPrimary=self::lists($target)[0];if($nextPrimary['id']!==$primary['id']||$nextPrimary['m3uUrl']!==$primary['m3uUrl'])throw new Problem(409,'The verified activation source must stay first with unchanged credentials/URL. Use verified activation for account transfers.');
        $oldIds=array_column(array_merge(self::lists($doc['config']),$doc['config']['stalkerPortals']),'id');$nextIds=array_column(array_merge(self::lists($target),$target['stalkerPortals']),'id');sort($oldIds);sort($nextIds);
        if($keptPrivate&&($credentialEdit||$oldIds!==$nextIds))throw new Problem(422,'Give remaining private source labels nonsensitive names before changing sources or credentials.');
        $id=Database::id();$this->db->query('INSERT INTO source_drafts (id,device_id,snapshot_id,profile_ref,expected_revision,restore_checkpoint,payload_cipher,created_by,expires_at) VALUES (?,?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 10 MINUTE))',[$id,$device,$base['id'],$base['profile_ref'],$base['revision'],(int)isset($input['rollback_id']),$this->encode($target),$actor['id']]);$this->auth->audit($actor,'sources.preview_created',$id,$d['tenant_id']);
        $entries=$this->publicConfig($device,$base['profile_ref'],$target);$oldRefs=array_column($this->publicConfig($device,$base['profile_ref'],$doc['config']),'ref');foreach($entries as &$entry)$entry['identity_changed']=!in_array($entry['ref'],$oldRefs,true);unset($entry);
        return ['id'=>$id,'snapshot_id'=>$base['id'],'profile_ref'=>$base['profile_ref'],'entries'=>$entries,'expires_in_seconds'=>600,'warning'=>'Full replacement; removed/disabled/changed sources can stop viewing. Credential/URL changes get NEW source identities, never guessed favourite/category migration. A safety checkpoint is required before apply. Existing guides/favourites history remain.'];
    }
    public function context(string $token,array $input):array {
        Input::keys($input,['command_id','lease_token']);[$d,$c]=$this->lease($token,$input['command_id']??'',$input['lease_token']??'');if($c['kind']!=='apply_sources')throw new Problem(422,'No editable context for this command.');$p=json_decode($c['payload'],true,16,JSON_THROW_ON_ERROR);$draft=$this->draft($d['id'],$p['draft_id']);$base=$this->snapshot($d['id'],$draft['snapshot_id']);
        return ['profile_ref'=>$draft['profile_ref'],'expected_revision'=>$draft['expected_revision'],'restore_checkpoint'=>(bool)$draft['restore_checkpoint'],'before'=>$this->decode($base['payload_cipher'])['config'],'target'=>$this->decode($draft['payload_cipher'])];
    }
    public function maintain():void {$this->db->query('DELETE FROM source_drafts WHERE expires_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY)');$this->db->query('DELETE FROM source_snapshots WHERE protected=0 AND created_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 90 DAY)');}
}
