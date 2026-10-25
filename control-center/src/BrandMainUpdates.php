<?php
declare(strict_types=1);
namespace ControlCenter;

/** Privileged main-source fanout. Keeps the published brand snapshot and identity. */
final class BrandMainUpdates
{
    public function __construct(private readonly Database $db,private readonly Config $config,private readonly BrandArtifacts $artifacts,private readonly Auth $auth,private readonly ?\Closure $verify=null) {}
    public function enabled(string $channel='testing'):bool {return in_array($channel,['testing','production'],true)&&($this->config->get('brand_main_updates',[])[$channel.'_enabled']??false)===true;}
    private function serialized(callable $work):mixed {
        $key='brand-main-'.substr(hash('sha256',$this->config->get('database')['dsn']),0,32);
        if((int)$this->db->query('SELECT GET_LOCK(?,10)',[$key])->fetchColumn()!==1)throw new Problem(409,'Another main release update is running; retry this cursor.');
        try{return $work();}finally{$this->db->query('SELECT RELEASE_LOCK(?)',[$key]);}
    }
    private function github(string $path):array {
        $raw='';$curl=curl_init('https://api.github.com/repos/derangedchief-crypto/ARVIO/'.$path);
        curl_setopt_array($curl,[CURLOPT_HTTPHEADER=>['Accept: application/vnd.github+json','User-Agent: Extreme-Brand-Main-Updates'],CURLOPT_FOLLOWLOCATION=>false,CURLOPT_CONNECTTIMEOUT=>5,CURLOPT_TIMEOUT=>15,CURLOPT_SSL_VERIFYPEER=>true,CURLOPT_SSL_VERIFYHOST=>2,CURLOPT_PROTOCOLS=>CURLPROTO_HTTPS,CURLOPT_WRITEFUNCTION=>static function($h,$chunk)use(&$raw){if(strlen($raw)+strlen($chunk)>1048576)return 0;$raw.=$chunk;return strlen($chunk);}]);
        $ok=curl_exec($curl);$status=curl_getinfo($curl,CURLINFO_RESPONSE_CODE);curl_close($curl);
        if($ok===false||$status!==200)throw new Problem(503,'Main-source verification unavailable; no rollout authorized.');
        $value=json_decode($raw,true,24,JSON_THROW_ON_ERROR);if(!is_array($value))throw new Problem(503,'Invalid main-source verification.');return $value;
    }
    private function verifySource(array $input):void {
        if($this->verify){($this->verify)($input);return;}
        $main=$this->github('git/ref/heads/main');$tag=$this->github('git/ref/tags/'.$input['workflow_ref']);
        if(($main['object']['sha']??null)!==$input['source_sha']||($tag['object']['sha']??null)!==$input['source_sha']||($tag['object']['type']??null)!=='commit')throw new Problem(409,'Rollout must use the exact current main commit and its immutable source tag.');
        $file=$this->github('contents/app/build.gradle.kts?ref='.$input['source_sha']);$text=base64_decode($file['content']??'',true);
        if(!is_string($text)||!str_contains($text,'val brandDirectory')||!preg_match('/^val platformVersionName = "([0-9.]+)"$/m',$text,$name)||!preg_match('/^val platformVersionCode = ([0-9]+)$/m',$text,$code)||$name[1]!==$input['app_version']||(int)$code[1]!==$input['version_code']||!preg_match('/^\s*versionCode = '.$input['version_code'].'$/m',$text)||!preg_match('/^\s*versionName = "'.preg_quote($input['app_version'],'/').'"$/m',$text))throw new Problem(409,'Main has not integrated compatible branded-build support and the declared release identity.');
        $this->github('contents/branding/central_builder.py?ref='.$input['source_sha']);
    }
    public function fanout(array $input):array {
        Input::keys($input,['source_sha','workflow_ref','app_version','version_code','channel']);$channel=$input['channel']??'testing';if(!is_string($channel)||!in_array($channel,['testing','production'],true))throw new Problem(422,'Invalid main rollout channel.');
        $modern=(bool)$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['021_production_brand_releases.sql']);
        if(!$this->enabled($channel)||!$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['018_brand_main_updates.sql'])||($channel==='production'&&!$modern))throw new Problem(503,'This main-to-brand release channel is not enabled by the owner.');
        $sha=$input['source_sha']??null;$version=$input['app_version']??null;$code=$input['version_code']??null;
        if(!is_string($sha)||!preg_match('/^[a-f0-9]{40}$/D',$sha)||!is_string($version)||strlen($version)>24||!preg_match('/^[0-9]+\.[0-9]+\.[0-9]+$/D',$version)||version_compare($version,'2.0.115','<=')||!is_int($code)||$code<=431||$code>21474||($input['workflow_ref']??null)!=='brand-source-'.$version.'-'.$sha)throw new Problem(409,'Main update requires a newer platform name/code and exact immutable source identity.');
        $existing=$this->db->one('SELECT * FROM brand_main_sources WHERE source_sha=?',[$sha]);
        if(!$existing)$this->verifySource($input);
        elseif($existing['app_version']!==$version||(int)$existing['version_code']!==$code||$existing['workflow_ref']!==$input['workflow_ref'])throw new Problem(409,'Main-source identity is immutable.');
        return $this->serialized(fn()=> $this->db->transaction(function()use($input,$sha,$version,$code,$channel,$modern):array {
            // The database advisory lock serializes registration/catch-up batches;
            // ordinary account and enrollment operations never need that lock.
            $owner=$this->db->one("SELECT id,tenant_id FROM users WHERE role='owner' AND active=1 ORDER BY id LIMIT 1");if(!$owner)throw new Problem(409,'Active platform owner required.');
            $source=$this->db->one('SELECT * FROM brand_main_sources WHERE source_sha=? FOR UPDATE',[$sha]);
            if(!$source){$latest=$this->db->one('SELECT version_code,app_version FROM brand_main_sources ORDER BY version_code DESC LIMIT 1');if($latest&&((int)$latest['version_code']>=$code||version_compare($version,$latest['app_version'],'<=')))throw new Problem(409,'Main update would not increase platform version.');$this->db->query('INSERT INTO brand_main_sources (source_sha,app_version,version_code,workflow_ref) VALUES (?,?,?,?)',[$sha,$version,$code,$input['workflow_ref']]);$source=['cursor_brand'=>'','complete'=>false];}
            if($modern){$rollout=$this->db->one('SELECT * FROM brand_main_rollouts WHERE source_sha=? AND channel=? FOR UPDATE',[$sha,$channel]);if(!$rollout){$this->db->query('INSERT INTO brand_main_rollouts (source_sha,channel) VALUES (?,?)',[$sha,$channel]);$rollout=['cursor_brand'=>'','complete'=>false];}}else $rollout=$source;
            if($rollout['complete'])return ['complete'=>true,'queued'=>0];
            $brands=$this->db->all('SELECT id,tenant_id FROM app_brands WHERE id>? ORDER BY id LIMIT 100',[$rollout['cursor_brand']]);$queued=0;$cursor=$rollout['cursor_brand'];
            foreach($brands as $row){
                $cursor=$row['id'];$tenant=$this->db->one('SELECT * FROM tenants WHERE id=? FOR UPDATE',[$row['tenant_id']]);$brand=$this->db->one('SELECT * FROM app_brands WHERE id=? FOR UPDATE',[$row['id']]);
                if(!$tenant||$tenant['status']!=='active'||!$brand['enabled']||($tenant['kind']!=='owner'&&!$this->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$tenant['id']])))continue;
                $current=$this->artifacts->approved($brand['id'],$channel,true);$live=$current;
                if(!$live&&$channel==='production')$live=$this->artifacts->approved($brand['id'],'testing',true);
                if(!$live||($current&&(int)$current['version_code']>=$code*100000)||(!$current&&(int)$live['version_code']>=($code+1)*100000))continue;
                $prior=$this->db->one('SELECT manifest FROM app_brand_builds WHERE id=? FOR UPDATE',[$live['build_id']]);$manifest=$prior?json_decode($prior['manifest'],true,12,JSON_THROW_ON_ERROR):null;
                if(!$manifest||($manifest['brand_id']??null)!==$brand['id']||($manifest['package_name']??null)!==$brand['package_name'])throw new Problem(409,'Published branding snapshot requires review.');
                // Preserve the LIVE APK brand snapshot, not unpublished/draft artwork.
                $manifest['app_version']=$version;$manifest['app_version_code']=$code;
                $old=$this->db->one('SELECT id FROM app_brand_builds WHERE brand_id=? AND brand_version=? AND app_version=?'.($modern?' AND build_channel=?':''),[$brand['id'],$manifest['brand_version'],$version,...($modern?[$channel]:[])]);if($old)continue;
                $id=Database::id();$this->db->query('INSERT INTO app_brand_builds (id,brand_id,brand_version,app_version,manifest,requested_by,build_channel,source_sha) VALUES (?,?,?,?,?,?,?,?)',[$id,$brand['id'],$manifest['brand_version'],$version,json_encode($manifest,JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR),$owner['id'],$channel,$sha]);
                foreach(array_unique(array_column($manifest['assets'],'id')) as $asset)$this->db->query('INSERT INTO app_brand_build_assets (build_id,asset_id) VALUES (?,?)',[$id,$asset]);
                $this->db->query('INSERT INTO brand_main_jobs (build_id,source_sha) VALUES (?,?)',[$id,$sha]);$queued++;
            }
            $complete=count($brands)<100;if($modern)$this->db->query('UPDATE brand_main_rollouts SET cursor_brand=?,complete=? WHERE source_sha=? AND channel=?',[$cursor,(int)$complete,$sha,$channel]);else $this->db->query('UPDATE brand_main_sources SET cursor_brand=?,complete=? WHERE source_sha=?',[$cursor,(int)$complete,$sha]);
            $this->auth->audit(null,'brand.main_'.$channel.'_fanout',null,null,['source_sha'=>$sha,'queued'=>$queued]);return ['complete'=>$complete,'queued'=>$queued];
        }));
    }
    /** Durable catch-up handles a branding build finishing after a newer main
     * rollout was queued. Never publish the older brand snapshot over that edit. */
    public function refresh(string $brandId):void {
        $channel=$this->config->get('brand_release',[])['default_channel']??'testing';
        if(!$this->enabled($channel)||!$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['021_production_brand_releases.sql']))return;
        $this->serialized(fn()=> $this->db->transaction(function()use($brandId,$channel):void {
            $owner=$this->db->one("SELECT id FROM users WHERE role='owner' AND active=1 ORDER BY id LIMIT 1");if(!$owner)return;
            $before=$this->db->one('SELECT tenant_id FROM app_brands WHERE id=?',[$brandId]);if(!$before)return;$tenant=$this->db->one('SELECT * FROM tenants WHERE id=? FOR UPDATE',[$before['tenant_id']]);$brand=$this->db->one('SELECT * FROM app_brands WHERE id=? FOR UPDATE',[$brandId]);
            if(!$brand||!$brand['enabled']||!$tenant||$tenant['status']!=='active'||($tenant['kind']!=='owner'&&!$this->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$tenant['id']]))){$this->db->query('DELETE FROM brand_main_refreshes WHERE brand_id=?',[$brandId]);return;}
            $source=$this->db->one('SELECT s.* FROM brand_main_sources s JOIN brand_main_rollouts r ON r.source_sha=s.source_sha AND r.channel=? ORDER BY s.version_code DESC LIMIT 1',[$channel]);$live=$this->artifacts->approved($brandId,$channel,true);
            $manifest=null;if($live){$old=$this->db->one('SELECT manifest FROM app_brand_builds WHERE id=? FOR UPDATE',[$live['build_id']]);$manifest=json_decode($old['manifest'],true,12,JSON_THROW_ON_ERROR);$this->db->query("UPDATE app_brand_builds SET state='cancelled' WHERE brand_id=? AND build_channel=? AND brand_version<? AND state IN ('waiting_builder','build_failed','ready_to_publish')",[$brandId,$channel,$manifest['brand_version']]);}
            if($source&&$manifest&&(int)$live['version_code']<(int)$source['version_code']*100000){
                $manifest['app_version']=$source['app_version'];$manifest['app_version_code']=(int)$source['version_code'];
                if(!$this->db->one('SELECT id FROM app_brand_builds WHERE brand_id=? AND brand_version=? AND app_version=? AND build_channel=? FOR UPDATE',[$brandId,$manifest['brand_version'],$source['app_version'],$channel])){
                    $id=Database::id();$this->db->query('INSERT INTO app_brand_builds (id,brand_id,brand_version,app_version,manifest,requested_by,build_channel,source_sha) VALUES (?,?,?,?,?,?,?,?)',[$id,$brandId,$manifest['brand_version'],$source['app_version'],json_encode($manifest,JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR),$owner['id'],$channel,$source['source_sha']]);foreach(array_unique(array_column($manifest['assets'],'id')) as $asset)$this->db->query('INSERT INTO app_brand_build_assets (build_id,asset_id) VALUES (?,?)',[$id,$asset]);$this->db->query('INSERT INTO brand_main_jobs (build_id,source_sha) VALUES (?,?)',[$id,$source['source_sha']]);
                }
            }
            $this->db->query('DELETE FROM brand_main_refreshes WHERE brand_id=?',[$brandId]);
        }));
    }
}
