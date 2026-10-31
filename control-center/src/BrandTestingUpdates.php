<?php
declare(strict_types=1);
namespace ControlCenter;

/** Owner CLI only: preserve ONE published testing brand on a pinned staging
 * source. Never registers a main source or queues production/other brands. */
final class BrandTestingUpdates {
    public function __construct(private readonly Database $db,private readonly Config $config,private readonly BrandArtifacts $artifacts,private readonly Auth $auth) {}
    public function queue(string $brandId,string $source,string $version,int $code):array {
        Input::id($brandId);EnrollmentProof::hex($source,40);
        if(!preg_match('/^\d+\.\d+\.\d+$/D',$version)||$code<1||$code>21474)throw new Problem(422,'Invalid testing platform version.');
        $c=$this->config->get('brand_builder',[]);
        if(($c['enabled']??false)!==true||!in_array('testing',$c['channels']??[],true))throw new Problem(409,'The existing central testing builder must be enabled.');
        return $this->db->transaction(function()use($brandId,$source,$version,$code):array{
            $b=$this->db->one('SELECT * FROM app_brands WHERE id=?',[$brandId])??throw new Problem(404,'Brand not found.');
            $t=$this->db->one('SELECT * FROM tenants WHERE id=? FOR UPDATE',[$b['tenant_id']]);
            $b=$this->db->one('SELECT * FROM app_brands WHERE id=? FOR UPDATE',[$brandId]);
            $owner=$this->db->one("SELECT * FROM users WHERE role='owner' AND active=1 ORDER BY id LIMIT 1");
            if(!$owner||!$t||$t['status']!=='active'||!$b['enabled']||($t['kind']!=='owner'&&!$this->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$t['id']])))throw new Problem(409,'Brand workspace is unavailable.');
            $registered=$this->db->one('SELECT * FROM brand_staging_sources WHERE source_sha=? OR app_version=? OR version_code=? FOR UPDATE',[$source,$version,$code]);
            if($registered&&($registered['source_sha']!==$source||$registered['app_version']!==$version||(int)$registered['version_code']!==$code))throw new Problem(409,'This staging source/version is already pinned to another identity.');
            $old=$this->db->one("SELECT * FROM app_brand_builds WHERE brand_id=? AND app_version=? AND build_channel='testing' ORDER BY created_at DESC LIMIT 1 FOR UPDATE",[$brandId,$version]);
            if($old){
                $manifest=json_decode($old['manifest'],true,16,JSON_THROW_ON_ERROR);
                if($old['source_sha']!==$source||($manifest['app_version_code']??null)!==$code||$old['state']==='cancelled')throw new Problem(409,'This testing version already has a different immutable job.');
                return ['build_id'=>$old['id'],'channel'=>'testing','source_sha'=>$source,'state'=>$old['state'],'created'=>false];
            }
            $live=$this->artifacts->approved($brandId,'testing',true)??throw new Problem(409,'An existing published testing identity is required.');
            $prior=$this->db->one('SELECT manifest FROM app_brand_builds WHERE id=?',[$live['build_id']]);
            $m=json_decode($prior['manifest'],true,16,JSON_THROW_ON_ERROR);
            if(($m['brand_id']??null)!==$brandId||($m['package_name']??null)!==$b['package_name']||$code*100000+(int)$m['brand_version']<=(int)$live['version_code']||version_compare($version,$m['app_version'],'<='))throw new Problem(409,'Testing update must preserve identity and increase its version.');
            $m['app_version']=$version;$m['app_version_code']=$code;$id=Database::id();
            if(!$registered)$this->db->query('INSERT INTO brand_staging_sources (source_sha,app_version,version_code,approved_by) VALUES (?,?,?,?)',[$source,$version,$code,$owner['id']]);
            $this->db->query("INSERT INTO app_brand_builds (id,brand_id,brand_version,app_version,manifest,requested_by,build_channel,source_sha) VALUES (?,?,?,?,?,?,'testing',?)",[$id,$brandId,$m['brand_version'],$version,json_encode($m,JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR),$owner['id'],$source]);
            foreach(array_unique(array_column($m['assets'],'id')) as $asset)$this->db->query('INSERT INTO app_brand_build_assets (build_id,asset_id) VALUES (?,?)',[$id,$asset]);
            $this->auth->audit($owner,'brand.staging_testing_queued',$id,$t['id'],['source_sha'=>$source]);
            return ['build_id'=>$id,'channel'=>'testing','source_sha'=>$source,'state'=>'waiting_builder','created'=>true];
        });
    }
}
