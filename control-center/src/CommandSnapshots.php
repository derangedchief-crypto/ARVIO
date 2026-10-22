<?php
declare(strict_types=1);
namespace ControlCenter;

/** Typed, closed schema. Never accept free-form device files/logs or provider URLs. */
final class CommandSnapshots
{
    public static function validate(string $kind,mixed $input): array
    {
        $settings=['schema_version','profile_ref','sort_order','playlist_count','enabled_playlist_count','portal_count','favourite_channel_count','favourite_group_count'];
        $diagnostics=['schema_version','profile_ref','app_package','app_version','sdk','storage_total','storage_free','channel_count','guide_state'];
        $keys=match($kind){'get_settings','set_settings'=>$settings,'get_diagnostics'=>$diagnostics,default=>throw new Problem(422,'This command cannot return a snapshot.')};
        if(!is_array($input)||array_is_list($input)||array_diff($keys,array_keys($input)))throw new Problem(422,'Invalid snapshot schema.');
        Input::keys($input,array_merge($keys,$kind==='get_diagnostics'?[]:['settings_revision']));
        if(isset($input['settings_revision']))EnrollmentProof::hex($input['settings_revision'],64);
        if($kind==='set_settings'&&!isset($input['settings_revision']))throw new Problem(422,'Updated settings require a revision.');
        foreach($input as $value)if(!is_string($value)||strlen($value)>64)throw new Problem(422,'Invalid snapshot value.');
        if($input['schema_version']!=='1'||!preg_match('/^[a-f0-9]{64}$/D',$input['profile_ref']))throw new Problem(422,'Invalid snapshot version/profile.');
        $numeric=$kind!=='get_diagnostics'?['playlist_count','enabled_playlist_count','portal_count','favourite_channel_count','favourite_group_count']:['sdk','storage_total','storage_free','channel_count'];
        foreach($numeric as $field) {
            if(!preg_match('/^(0|[1-9][0-9]{0,17})$/D',$input[$field]))throw new Problem(422,'Invalid snapshot count.');
            $input[$field]=(int)$input[$field];
        }
        if($kind!=='get_diagnostics') {
            if(!in_array($input['sort_order'],['provider','number','name'],true)||$input['enabled_playlist_count']>$input['playlist_count'])throw new Problem(422,'Invalid settings snapshot.');
            foreach(['playlist_count','enabled_playlist_count','portal_count'] as $field)if($input[$field]>10000)throw new Problem(422,'Invalid settings count.');
            foreach(['favourite_channel_count','favourite_group_count'] as $field)if($input[$field]>10000000)throw new Problem(422,'Invalid favourites count.');
        } else {
            EnrollmentProof::package($input['app_package']);
            if(!preg_match('/^[A-Za-z0-9._+\-]{1,40}$/D',$input['app_version'])||!in_array($input['guide_state'],['idle','loading','failed'],true)
                ||$input['sdk']<26||$input['sdk']>1000||$input['storage_free']>$input['storage_total']||$input['channel_count']>10000000)throw new Problem(422,'Invalid diagnostics snapshot.');
        }
        $input['schema_version']=1;
        return $input;
    }
}
