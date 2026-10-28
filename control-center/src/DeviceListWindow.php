<?php
declare(strict_types=1);
namespace ControlCenter;

/** Visibility only. Never deletes devices, credentials or audit/history. */
final class DeviceListWindow {
    public function __construct(private readonly Database $db,private readonly Auth $auth) {}
    public static function installed(Database $db):bool {return (bool)$db->one('SELECT name FROM schema_migrations WHERE name=?',['025_device_recovery.sql']);}
    public static function condition(Database $db,string $alias):string {
        if(!preg_match('/^[a-z]+$/D',$alias))throw new \LogicException('Invalid internal SQL alias');
        return self::installed($db)?"$alias.checkin_generation >= (SELECT device_list_generation FROM platform_settings WHERE id=1)":'1=1';
    }
    public function state():array {
        if(!self::installed($this->db))return ['generation'=>0,'reset_at'=>null,'recovery_enabled'=>false];
        $row=$this->db->one('SELECT device_list_generation,device_list_reset_at,device_recovery_enabled FROM platform_settings WHERE id=1');
        return ['generation'=>(int)$row['device_list_generation'],'reset_at'=>$row['device_list_reset_at'],'recovery_enabled'=>(bool)$row['device_recovery_enabled']];
    }
    public function reset(array $actor,array $input):array {
        Auth::owner($actor);Input::keys($input,['scope','confirm']);
        if(($input['scope']??null)!=='all_workspaces'||($input['confirm']??null)!==true)throw new Problem(422,'Explicit all-workspace visibility reset confirmation required.');
        if(!self::installed($this->db))throw new Problem(503,'Apply device recovery migration first.');
        return $this->db->transaction(function()use($actor):array{
            $this->db->one('SELECT id FROM platform_settings WHERE id=1 FOR UPDATE');
            $this->db->query('UPDATE platform_settings SET device_list_generation=device_list_generation+1,device_list_reset_at=UTC_TIMESTAMP(6) WHERE id=1');
            $this->auth->audit($actor,'device_list.fresh_checkins',null,null,['scope'=>'all_workspaces']);
            return $this->state();
        });
    }
    public function setRecovery(array $actor,array $input):array {
        Auth::owner($actor);Input::keys($input,['enabled']);
        if(!is_bool($input['enabled']??null))throw new Problem(422,'Invalid recovery setting.');
        if(!self::installed($this->db))throw new Problem(503,'Apply device recovery migration first.');
        $this->db->query('UPDATE platform_settings SET device_recovery_enabled=? WHERE id=1',[(int)$input['enabled']]);
        $this->auth->audit($actor,'device_recovery.setting_changed',null,null,['enabled'=>$input['enabled']]);
        return $this->state();
    }
}
