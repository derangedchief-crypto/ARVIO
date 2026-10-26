<?php
declare(strict_types=1);
namespace ControlCenter;

/** Versioned encrypted recovery, deliberately separate from ephemeral snapshots. */
final class Favourites
{
    private readonly Secrets $secrets;
    public function __construct(private readonly Database $db,private readonly Management $management,private readonly Commands $commands,private readonly Auth $auth,private readonly Config $config) {$this->secrets=new Secrets($config);}
    private function device(string $token):array {$d=$this->commands->deviceIdentity($token);if($d['policy']!=='active'||$d['tenant_status']!=='active')throw new Problem(403,'Device management is paused.');return $d;}
    public static function snapshot(array $input):array
    {
        Input::keys($input,['schema_version','profile_ref','source_ref','revision','content_hash','entries','reason']);
        if(($input['schema_version']??null)!==1)throw new Problem(422,'Invalid favourites schema.');
        foreach(['profile_ref','source_ref','revision','content_hash'] as $key)EnrollmentProof::hex($input[$key]??null,64);
        if(!is_array($input['entries']??null)||!array_is_list($input['entries'])||count($input['entries'])>1000)throw new Problem(422,'Invalid favourites list.');
        $seen=[];$ids=[];
        foreach($input['entries'] as &$entry) {
            if(!is_array($entry))throw new Problem(422,'Invalid favourite.');Input::keys($entry,['kind','ref','identity','label','available']);
            if(!in_array($entry['kind']??null,['channel','group'],true)||!is_bool($entry['available']??null))throw new Problem(422,'Invalid favourite kind/status.');
            $ref=EnrollmentProof::hex($entry['ref']??null,64);$identity=Input::text($entry['identity']??null,'favourite identity',512);
            if(strlen($identity)>512||preg_match('/[\x00-\x1F\x7F]/u',$identity)||isset($seen[$ref])||isset($ids[$entry['kind']."\n".$identity]))throw new Problem(422,'Duplicate/invalid favourite identity.');
            $seen[$ref]=true;$ids[$entry['kind']."\n".$identity]=true;$label=Input::text($entry['label']??null,'favourite label',80);
            if(preg_match('~[a-z][a-z0-9+.-]*://|www\.|bearer\s|(?:password|passwd|username|token|secret|authorization|activation[_ -]?code|mac)\s*[:=]~i',$label))$label='[redacted favourite label]';
            $entry['identity']=$identity;$entry['label']=mb_substr(Secrets::redact($label),0,80);
        }
        unset($entry);return $input;
    }
    public function capture(string $token,array $input):array
    {
        $d=$this->device($token);$this->auth->limit('favourites-capture',$d['id'],20,60);$input=self::snapshot($input);$reason=$input['reason']??'automatic';
        if(!in_array($reason,['automatic','manual','pre_restore'],true))throw new Problem(422,'Invalid backup reason.');unset($input['reason']);
        return $this->db->transaction(function()use($token,$d,$input,$reason):array {
            $this->db->one('SELECT id FROM devices WHERE id=? FOR UPDATE',[$d['id']]);$current=$this->device($token);
            $channels=count(array_filter($input['entries'],static fn($r)=>$r['kind']==='channel'));$groups=count($input['entries'])-$channels;
            $this->db->query('INSERT INTO favourites_live_state (device_id,profile_ref,source_ref,revision,channel_count,group_count) VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE revision=VALUES(revision),channel_count=VALUES(channel_count),group_count=VALUES(group_count),updated_at=UTC_TIMESTAMP()',[$d['id'],$input['profile_ref'],$input['source_ref'],$input['revision'],$channels,$groups]);
            $contexts=$this->db->all('SELECT profile_ref,source_ref FROM favourites_live_state WHERE device_id=? ORDER BY updated_at DESC LIMIT 32',[$d['id']]);
            if(count($contexts)===32){$clauses=[];$values=[$d['id']];foreach($contexts as $ctx){$clauses[]='(profile_ref=? AND source_ref=?)';array_push($values,$ctx['profile_ref'],$ctx['source_ref']);}$this->db->query('DELETE FROM favourites_live_state WHERE device_id=? AND NOT ('.implode(' OR ',$clauses).')',$values);}
            $reply=['schema_version'=>1,'profile_ref'=>$input['profile_ref'],'source_ref'=>$input['source_ref'],'stored'=>false];
            // Empty is live status ONLY. It never becomes a backup and never
            // overwrites/deletes a protected or last-good nonempty version.
            if(!$input['entries'])return $reply+['reason'=>'empty_ignored'];
            $existing=$reason==='automatic'?$this->db->one('SELECT id FROM favourites_backups WHERE device_id=? AND profile_ref=? AND source_ref=? AND content_hash=? ORDER BY created_at DESC LIMIT 1',[$d['id'],$input['profile_ref'],$input['source_ref'],$input['content_hash']]):null;
            if($existing){$this->db->query('UPDATE favourites_backups SET last_seen_at=UTC_TIMESTAMP() WHERE id=?',[$existing['id']]);return array_replace($reply,['stored'=>true,'backup_id'=>$existing['id']]);}
            $pinned=(int)$this->db->query('SELECT COUNT(*) FROM favourites_backups WHERE device_id=? AND protected=1',[$d['id']])->fetchColumn();
            $contextPinned=(int)$this->db->query('SELECT COUNT(*) FROM favourites_backups WHERE device_id=? AND profile_ref=? AND source_ref=? AND protected=1',[$d['id'],$input['profile_ref'],$input['source_ref']])->fetchColumn();
            $first=!(bool)$this->db->one('SELECT id FROM favourites_backups WHERE device_id=? AND profile_ref=? LIMIT 1',[$d['id'],$input['profile_ref']]);
            $protect=($first||$reason==='pre_restore')&&$pinned<20&&$contextPinned<5;
            $id=Database::id();$this->db->query('INSERT INTO favourites_backups (id,device_id,profile_ref,source_ref,content_hash,channel_count,group_count,protected,label,reason,payload_cipher) VALUES (?,?,?,?,?,?,?,?,?,?,?)',[$id,$d['id'],$input['profile_ref'],$input['source_ref'],$input['content_hash'],$channels,$groups,(int)$protect,$first?'Initial good favourites':($reason==='pre_restore'?'Before favourites recovery':''),$reason,$this->secrets->encrypt(json_encode($input,JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR))]);
            $this->auth->audit(null,'favourites.backup_saved',$id,$current['tenant_id'],['channels'=>$channels,'groups'=>$groups,'protected'=>$protect]);
            $keep=$this->db->all('SELECT id FROM favourites_backups WHERE device_id=? AND protected=0 ORDER BY last_seen_at DESC,created_at DESC,id DESC LIMIT '.max(1,100-$pinned-($protect?1:0)),[$d['id']]);
            $ids=array_column($keep,'id');if($ids){$marks=implode(',',array_fill(0,count($ids),'?'));$this->db->query("DELETE FROM favourites_backups WHERE device_id=? AND protected=0 AND id NOT IN ($marks)",array_merge([$d['id']],$ids));}
            return array_replace($reply,['stored'=>true,'backup_id'=>$id,'protected'=>$protect]);
        });
    }
    public function listing(array $actor,string $deviceId,array $input):array
    {
        Input::keys($input,['page']);$this->management->device($actor,$deviceId);$page=Input::page($input['page']??1);$offset=($page-1)*20;
        $total=(int)$this->db->query('SELECT COUNT(*) FROM favourites_backups WHERE device_id=?',[$deviceId])->fetchColumn();
        $this->commands->expire($deviceId);$commands=$this->db->all("SELECT * FROM commands WHERE device_id=? AND kind IN ('backup_favourites','preview_favourites_restore','restore_favourites') ORDER BY created_at DESC,id DESC LIMIT 10",[$deviceId]);
        return ['items'=>$this->db->all("SELECT id,profile_ref,source_ref,channel_count,group_count,protected,label,reason,created_at,last_seen_at FROM favourites_backups WHERE device_id=? ORDER BY protected DESC,created_at DESC,id DESC LIMIT 20 OFFSET $offset",[$deviceId]),'page'=>$page,'pages'=>max(1,(int)ceil($total/20)),'total'=>$total,'commands'=>array_map(Commands::publicCommand(...),$commands),'live'=>$this->db->all('SELECT profile_ref,source_ref,channel_count,group_count,updated_at FROM favourites_live_state WHERE device_id=? ORDER BY updated_at DESC LIMIT 32',[$deviceId])]+$this->commands->currentState($actor,$deviceId,['backup_favourites','preview_favourites_restore','restore_favourites']);
    }
    private function backup(string $deviceId,string $backupId):array {$row=$this->db->one('SELECT * FROM favourites_backups WHERE id=? AND device_id=?',[Input::id($backupId),$deviceId]);if(!$row)throw new Problem(404,'Backup not found.');return $row;}
    public function view(array $actor,string $deviceId,string $backupId):array
    {
        $this->management->device($actor,$deviceId);$row=$this->backup($deviceId,$backupId);$doc=json_decode($this->secrets->decrypt($row['payload_cipher']),true,16,JSON_THROW_ON_ERROR);unset($row['payload_cipher']);
        $row['entries']=array_map(static fn($e)=>array_intersect_key($e,array_flip(['kind','ref','label','available'])),$doc['entries']);return $row;
    }
    public function protect(array $actor,string $deviceId,string $backupId,array $input):array
    {
        Input::keys($input,['protected','label']);if(!is_bool($input['protected']??null))throw new Problem(422,'Invalid backup protection.');
        $this->management->device($actor,$deviceId);
        return $this->db->transaction(function()use($actor,$deviceId,$backupId,$input):array {
            $this->db->one('SELECT id FROM devices WHERE id=? FOR UPDATE',[$deviceId]);
            $d=$this->management->device($actor,$deviceId,true);$row=$this->backup($deviceId,$backupId);
            if($input['protected']&&!$row['protected']) {
                $all=(int)$this->db->query('SELECT COUNT(*) FROM favourites_backups WHERE device_id=? AND protected=1',[$deviceId])->fetchColumn();$context=(int)$this->db->query('SELECT COUNT(*) FROM favourites_backups WHERE device_id=? AND profile_ref=? AND source_ref=? AND protected=1',[$deviceId,$row['profile_ref'],$row['source_ref']])->fetchColumn();
                if($all>=20||$context>=5)throw new Problem(409,'Protected backup limit reached (20/device,5/profile-source). Unprotect an old version explicitly first.');
            }
            $label=Input::text($input['label']??$row['label'],'backup label',80,false);$this->db->query('UPDATE favourites_backups SET protected=?,label=? WHERE id=?',[(int)$input['protected'],$label,$backupId]);$this->auth->audit($actor,'favourites.backup_protection_changed',$backupId,$d['tenant_id'],['protected'=>$input['protected']]);return ['id'=>$backupId,'protected'=>$input['protected']];
        });
    }
    public function context(string $token,array $input):array
    {
        Input::keys($input,['backup_id','preview_id']);$d=$this->device($token);$preview=null;
        if(isset($input['preview_id'])) {$row=$this->db->one('SELECT * FROM favourites_restore_previews WHERE command_id=? AND device_id=? AND expires_at>UTC_TIMESTAMP()',[Input::id($input['preview_id']),$d['id']]);if(!$row)throw new Problem(409,'Recovery preview expired; preview again.');$preview=json_decode($row['preview'],true,16,JSON_THROW_ON_ERROR);$backupId=$row['backup_id'];}
        else $backupId=Input::id($input['backup_id']??null);
        $backup=$this->backup($d['id'],$backupId);$reply=['backup_id'=>$backupId,'backup'=>json_decode($this->secrets->decrypt($backup['payload_cipher']),true,16,JSON_THROW_ON_ERROR)];
        if($preview)$reply+=['preview_id'=>$row['command_id'],'preview'=>$preview];return $reply;
    }
    public function preview(array $actor,string $deviceId,string $backupId):array {$this->management->device($actor,$deviceId);$this->backup($deviceId,$backupId);$row=$this->db->one('SELECT command_id,preview,expires_at FROM favourites_restore_previews WHERE device_id=? AND backup_id=? AND expires_at>UTC_TIMESTAMP() ORDER BY expires_at DESC LIMIT 1',[$deviceId,Input::id($backupId)]);if($row)$row['preview']=json_decode($row['preview'],true,16,JSON_THROW_ON_ERROR);return ['preview'=>$row];}
    public function queuePreview(array $actor,string $deviceId,string $backupId,string $key):array {$this->management->device($actor,$deviceId);$this->backup($deviceId,$backupId);return $this->commands->enqueue($actor,$deviceId,['kind'=>'preview_favourites_restore','payload'=>['backup_id'=>$backupId]],$key);}
    public function queueRestore(array $actor,string $deviceId,string $backupId,array $input,string $key):array
    {
        Input::keys($input,['preview_id','mode']);$this->management->device($actor,$deviceId);$this->backup($deviceId,$backupId);$mode=$input['mode']??null;if(!in_array($mode,['merge','replace'],true))throw new Problem(422,'Invalid recovery mode.');
        $row=$this->db->one('SELECT * FROM favourites_restore_previews WHERE command_id=? AND device_id=? AND backup_id=? AND expires_at>UTC_TIMESTAMP()',[Input::id($input['preview_id']??null),$deviceId,$backupId]);if(!$row)throw new Problem(409,'Preview expired or does not match this backup.');
        $preview=json_decode($row['preview'],true,16,JSON_THROW_ON_ERROR);if(!$preview['matched_channels']&&!$preview['matched_groups'])throw new Problem(409,'No saved favourites currently match. Open Live TV/reload before previewing.');
        if($mode==='replace'&&$preview['missing_refs'])throw new Problem(409,'Replace cannot discard missing backup entries; use reviewed merge instead.');
        return $this->commands->enqueue($actor,$deviceId,['kind'=>'restore_favourites','payload'=>['preview_id'=>$row['command_id'],'mode'=>$mode]],$key);
    }
    public function storePreview(string $commandId,array $device,mixed $input,array $payload):array
    {
        if(!is_array($input))throw new Problem(422,'Invalid recovery preview.');$keys=['schema_version','backup_id','profile_ref','source_ref','expected_revision','missing_refs','matched_channels','matched_groups','current_channels','current_groups','backup_channels','backup_groups'];Input::keys($input,$keys);if(array_diff($keys,array_keys($input))||$input['schema_version']!==1||$input['backup_id']!==($payload['backup_id']??null))throw new Problem(422,'Invalid recovery preview target.');
        foreach(['profile_ref','source_ref','expected_revision'] as $field)EnrollmentProof::hex($input[$field],64);
        foreach(['matched_channels','matched_groups','current_channels','current_groups','backup_channels','backup_groups'] as $field)Input::integer($input[$field],0,1000);
        $row=$this->backup($device['id'],$input['backup_id']);$doc=json_decode($this->secrets->decrypt($row['payload_cipher']),true,16,JSON_THROW_ON_ERROR);
        if($input['profile_ref']!==$row['profile_ref']||$input['source_ref']!==$row['source_ref']||$input['backup_channels']!==(int)$row['channel_count']||$input['backup_groups']!==(int)$row['group_count'])throw new Problem(422,'Preview does not match backup scope/counts.');
        $missing=$input['missing_refs'];if(!is_array($missing)||!array_is_list($missing)||count($missing)>1000||count(array_unique($missing,SORT_REGULAR))!==count($missing))throw new Problem(422,'Invalid missing favourites.');foreach($missing as $ref)EnrollmentProof::hex($ref,64);
        $refs=array_column($doc['entries'],'ref');if(array_diff($missing,$refs))throw new Problem(422,'Missing refs do not belong to this backup.');
        $mc=count(array_filter($doc['entries'],static fn($e)=>$e['kind']==='channel'&&in_array($e['ref'],$missing,true)));$mg=count($missing)-$mc;
        if($input['matched_channels']+$mc!==$input['backup_channels']||$input['matched_groups']+$mg!==$input['backup_groups'])throw new Problem(422,'Preview match counts are inconsistent.');
        $this->db->query('INSERT INTO favourites_restore_previews (command_id,device_id,backup_id,preview,expires_at) VALUES (?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 10 MINUTE))',[$commandId,$device['id'],$input['backup_id'],json_encode($input,JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR)]);
        $small=$input;unset($small['missing_refs']);$small['missing_count']=count($missing);$small['preview_id']=$commandId;return $small;
    }
    public function maintain():void {$days=$this->config->number('retention_days',90,7,365);$this->db->query("DELETE FROM favourites_backups WHERE protected=0 AND last_seen_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL $days DAY) LIMIT 1000");$this->db->query('DELETE FROM favourites_restore_previews WHERE expires_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY) LIMIT 1000');}
}
