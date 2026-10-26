<?php
declare(strict_types=1);
namespace ControlCenter;

/** Workspace subscriptions; no payment processing or client-supplied ownership. */
final class ModuleLicences
{
    public const MODULE='extra_playlists';
    public function __construct(private readonly Database $db,private readonly Management $management,private readonly Auth $auth) {}
    private function ready():void {if(!$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['024_module_licences.sql']))throw new Problem(503,'Upgrade the website database to use Add-ons.');}
    public function policy():array {
        if(!$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['024_module_licences.sql']))return ['schema_version'=>1,'enforced'=>false,'revision'=>0];
        $r=$this->db->one('SELECT module_licences_enforced,module_licences_revision FROM platform_settings WHERE id=1');
        return ['schema_version'=>1,'enforced'=>(bool)$r['module_licences_enforced'],'revision'=>(int)$r['module_licences_revision']];
    }
    public function setPolicy(array $actor,array $input):array {
        Auth::owner($actor);$this->ready();Input::keys($input,['enforced','expected_revision','confirmed']);
        if(!is_bool($input['enforced']??null)||($input['confirmed']??null)!==true)throw new Problem(422,'Confirm the compatible-app rollout before changing enforcement.');
        return $this->db->transaction(function()use($actor,$input):array {
            $r=$this->db->one('SELECT * FROM platform_settings WHERE id=1 FOR UPDATE');
            if(Input::integer($input['expected_revision']??null,1,PHP_INT_MAX)!==(int)$r['module_licences_revision'])throw new Problem(409,'Add-on rollout changed. Reload first.');
            $this->db->query('UPDATE platform_settings SET module_licences_enforced=?,module_licences_revision=module_licences_revision+1 WHERE id=1',[(int)$input['enforced']]);
            $this->auth->audit($actor,'module.rollout_changed',null,null,['enforced'=>$input['enforced']]);return $this->policy();
        });
    }
    public static function monthAfter(string $value):string {
        $d=new \DateTimeImmutable($value,new \DateTimeZone('UTC'));$next=$d->modify('first day of next month');
        return $next->setDate((int)$next->format('Y'),(int)$next->format('m'),min((int)$d->format('d'),(int)$next->format('t')))->format('Y-m-d H:i:s');
    }
    private function now():string {return $this->db->query('SELECT UTC_TIMESTAMP()')->fetchColumn();}
    private function view(?array $r):?array {
        if(!$r)return null;unset($r['key_hash'],$r['created_by']);$r['revision']=(int)$r['revision'];
        $r['effective_status']=$r['status']==='active'&&$r['expires_at']<=$this->now()?'expired':$r['status'];return $r;
    }
    public function status(array $actor,array $input):array {
        $this->ready();Input::keys($input,['tenant_id']);$t=$this->management->workspace($actor,$input['tenant_id']??null);
        return ['tenant_id'=>$t['id'],'module'=>self::MODULE,'licence'=>$this->view($this->db->one('SELECT * FROM module_licences WHERE tenant_id=? AND module=?',[$t['id'],self::MODULE])),'policy'=>$this->policy()];
    }
    public function issue(array $actor,array $input):array {
        Auth::owner($actor);$this->ready();Input::keys($input,['tenant_id','module']);if(($input['module']??null)!==self::MODULE)throw new Problem(422,'Unknown add-on module.');
        return $this->db->transaction(function()use($actor,$input):array {
            $t=$this->management->workspace($actor,$input['tenant_id']??null,true);$old=$this->db->one('SELECT * FROM module_licences WHERE tenant_id=? AND module=? FOR UPDATE',[$t['id'],self::MODULE]);
            if($old&&$old['status']!=='revoked')throw new Problem(409,'A licence already exists. Renew it, or revoke it before issuing a replacement.');
            $key='EH-PL-'.strtoupper(bin2hex(random_bytes(32)));$id=Database::id();$hash=hash('sha256',$key);$hint='…'.substr($key,-8);
            if($old)$this->db->query("UPDATE module_licences SET id=?,key_hash=?,key_hint=?,status='awaiting_activation',activated_at=NULL,expires_at=NULL,revision=revision+1,created_by=?,created_at=UTC_TIMESTAMP() WHERE id=?",[$id,$hash,$hint,$actor['id'],$old['id']]);
            else $this->db->query("INSERT INTO module_licences(id,tenant_id,module,key_hash,key_hint,status,created_by) VALUES(?,?,?,?,?,'awaiting_activation',?)",[$id,$t['id'],self::MODULE,$hash,$hint,$actor['id']]);
            $this->auth->audit($actor,'module.key_issued',$id,$t['id'],['module'=>self::MODULE]);return $this->status($actor,['tenant_id'=>$t['id']])+['key'=>$key];
        });
    }
    public function redeem(array $actor,array $input):array {
        $this->ready();Input::keys($input,['tenant_id','key']);$this->auth->limit('module-redemption',$actor['id'],10,300);
        $key=$input['key']??null;if(!is_string($key)||!preg_match('/^EH-PL-[A-F0-9]{64}$/D',trim($key)))throw new Problem(422,'Invalid licence key.');$hash=hash('sha256',trim($key));
        return $this->db->transaction(function()use($actor,$input,$hash):array {
            $t=$this->management->workspace($actor,$input['tenant_id']??null,true);$r=$this->db->one('SELECT * FROM module_licences WHERE tenant_id=? AND module=? FOR UPDATE',[$t['id'],self::MODULE]);
            if(!$r||!hash_equals($r['key_hash'],$hash))throw new Problem(422,'Invalid licence key for this workspace.');
            if(in_array($r['status'],['suspended','revoked'],true))throw new Problem(403,'This licence is '.$r['status'].'. Contact the administrator.');
            if($r['activated_at']===null){$now=$this->now();$this->db->query("UPDATE module_licences SET status='active',activated_at=?,expires_at=?,revision=revision+1 WHERE id=?",[$now,self::monthAfter($now),$r['id']]);$this->auth->audit($actor,'module.activated',$r['id'],$t['id'],['module'=>self::MODULE]);}
            return $this->status($actor,['tenant_id'=>$t['id']]);
        });
    }
    public function change(array $actor,string $id,array $input):array {
        Auth::owner($actor);$this->ready();Input::keys($input,['action','expected_revision']);Input::id($id);
        if(!in_array($input['action']??null,['renew','suspend','reactivate','revoke'],true))throw new Problem(422,'Invalid licence action.');
        return $this->db->transaction(function()use($actor,$id,$input):array {
            $r=$this->db->one('SELECT * FROM module_licences WHERE id=? FOR UPDATE',[$id])??throw new Problem(404,'Licence not found.');
            if(Input::integer($input['expected_revision']??null,1,PHP_INT_MAX)!==(int)$r['revision'])throw new Problem(409,'Licence changed. Reload first.');
            if($r['status']==='revoked')throw new Problem(409,'Revocation is permanent. Issue a new key instead.');$status=$r['status'];$expires=$r['expires_at'];
            switch($input['action']) {
                case 'renew':if(!$r['activated_at'])throw new Problem(409,'Activate this key before renewing.');$expires=self::monthAfter(max($expires,$this->now()));break;
                case 'suspend':$status='suspended';break;
                case 'reactivate':if($status!=='suspended')throw new Problem(409,'Only suspended licences can be reactivated.');$status=$r['activated_at']?'active':'awaiting_activation';break;
                case 'revoke':$status='revoked';break;
            }
            $this->db->query('UPDATE module_licences SET status=?,expires_at=?,revision=revision+1 WHERE id=?',[$status,$expires,$id]);$this->auth->audit($actor,'module.'.$input['action'],$id,$r['tenant_id'],['module'=>self::MODULE]);return $this->status($actor,['tenant_id'=>$r['tenant_id']]);
        });
    }
    public function decision(array $device):array {
        $policy=$this->policy();$valid=false;
        if($policy['enforced'])$valid=(bool)$this->db->one("SELECT l.id FROM devices d JOIN tenants t ON t.id=d.tenant_id JOIN module_licences l ON l.tenant_id=d.tenant_id WHERE d.id=? AND d.policy='active' AND l.module=? AND l.status='active' AND l.expires_at>UTC_TIMESTAMP() AND t.status='active'",[$device['id'],self::MODULE]);
        return ['schema_version'=>1,'device_id'=>$device['id'],'module'=>self::MODULE,'enforced'=>$policy['enforced'],'allowed'=>!$policy['enforced']||$valid];
    }
    public static function needsLicence(array $before,array $target):bool {
        $lists=static fn($c)=>$c['playlists']?:($c['m3uUrl']!==''?[['id'=>'list_1','m3uUrl'=>$c['m3uUrl']]]:[]);
        $firstActivation=!$lists($before)&&$lists($target)?1:0;
        if(count($lists($target))+count($target['stalkerPortals'])>count($lists($before))+count($before['stalkerPortals'])+$firstActivation)return true;
        $old=[];foreach($lists($before) as $p)$old['m:'.$p['id']]=$p['m3uUrl'];foreach($before['stalkerPortals'] as $p)$old['s:'.$p['id']]=$p['portalUrl'].'\n'.strtoupper($p['macAddress']);
        $total=count($lists($target))+count($target['stalkerPortals']);$ids=array_merge(array_column($lists($target),'id'),array_column($target['stalkerPortals'],'id'));if(count(array_unique($ids))!==count($ids))return true;
        foreach($lists($target) as $i=>$p){$baseChange=$i===0&&($p['id']==='list_1'||$total===1||!$lists($before));if(!$baseChange&&($old['m:'.$p['id']]??null)!==$p['m3uUrl'])return true;}
        foreach($target['stalkerPortals'] as $p)if(($old['s:'.$p['id']]??null)!==$p['portalUrl'].'\n'.strtoupper($p['macAddress']))return true;
        return false;
    }
    public function enforce(array $device,array $before,array $target):void {
        if(self::needsLicence($before,$target)&&!$this->decision($device)['allowed'])throw new Problem(403,'Extra Playlists requires an active monthly reseller add-on. Existing playlists and viewing are unchanged.');
    }
}
