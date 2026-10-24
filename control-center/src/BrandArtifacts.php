<?php
declare(strict_types=1);
namespace ControlCenter;

/** Trusted CENTRAL publisher output, never APK/client-reported identity proof. */
final class BrandArtifacts
{
    private const SIGNER='3273d0b0294a689565ab4b0c8ef7e1887e2ca680240718f5521628b5c46328b7';
    private const FIELDS=['id','build_id','brand_id','channel','package_name','version_name','version_code','trial_protocol','signer_sha256','apk_sha256','artifact_url','enabled'];
    public function __construct(private readonly Database $db,private readonly Secrets $secrets,private readonly Config $config) {}
    private function mac(array $row):string {$values=[];foreach(self::FIELDS as $key)$values[$key]=in_array($key,['version_code','trial_protocol','enabled'],true)?(int)$row[$key]:$row[$key];if(isset($row['source_sha'])){foreach(['source_sha','apk_size_bytes','provenance_sha256'] as $key)$values[$key]=$key==='apk_size_bytes'?(int)$row[$key]:$row[$key];}return $this->secrets->mac("brand-artifact-v1\n".json_encode($values,JSON_UNESCAPED_SLASHES|JSON_THROW_ON_ERROR));}
    /** Internal publisher callback only; NO public HTTP route. The publisher must
     * verify actual APK bytes/package/version/certificate with SDK tools first. */
    public function recordVerified(string $buildId,array $verified):array {
        Input::keys($verified,['channel','package_name','version_name','version_code','trial_protocol','signer_sha256','apk_sha256','artifact_url','source_sha','apk_size_bytes','provenance_sha256']);
        $job=$this->db->one('SELECT j.*,b.package_name AS brand_package,b.tenant_id FROM app_brand_builds j JOIN app_brands b ON b.id=j.brand_id WHERE j.id=?',[Input::id($buildId)]);if(!$job||!in_array($job['state'],['waiting_builder','building','publishing','publication_review'],true))throw new Problem(409,'Build cannot receive a published artifact.');
        $manifest=json_decode($job['manifest'],true,12,JSON_THROW_ON_ERROR);$revision=(int)$job['brand_version'];$channel=$verified['channel']??null;if(!in_array($channel,['production','testing'],true)||$revision<1||$revision>99999)throw new Problem(422,'Invalid artifact channel or revision.');
        $package=$job['brand_package'].($channel==='testing'?'.test':'');$version=$manifest['app_version'].'.'.$revision.($channel==='testing'?'-test':'');$code=(int)$manifest['app_version_code']*100000+$revision;
        if($code>2147483647||($verified['package_name']??null)!==$package||($verified['version_name']??null)!==$version||($verified['version_code']??null)!==$code)throw new Problem(409,'Published APK identity/version does not match its pinned build.');
        $signer=$this->config->get('brand_signer_sha256',self::SIGNER);if(!is_string($signer)||!preg_match('/^[a-f0-9]{64}$/D',$signer)||($verified['signer_sha256']??null)!==$signer||!is_string($verified['apk_sha256']??null)||!preg_match('/^[a-f0-9]{64}$/D',$verified['apk_sha256']))throw new Problem(409,'Artifact signer or digest did not match central policy.');
        $protocol=Input::integer($verified['trial_protocol']??0,0,1);$tag='brand-'.$job['brand_id'].($channel==='testing'?'-testing':'');$prefix='https://github.com/derangedchief-crypto/ARVIO/releases/download/'.$tag.'/';$url=$verified['artifact_url']??null;
        if(isset($verified['source_sha'])){
            if(!preg_match('/^[a-f0-9]{40}$/D',$verified['source_sha'])||($job['source_sha']??null)!==$verified['source_sha']||!is_string($verified['provenance_sha256']??null)||!preg_match('/^[a-f0-9]{64}$/D',$verified['provenance_sha256']))throw new Problem(409,'Artifact provenance is invalid.');Input::integer($verified['apk_size_bytes']??null,1024,536870912);
        }elseif(isset($verified['apk_size_bytes'])||isset($verified['provenance_sha256']))throw new Problem(409,'Incomplete artifact provenance.');
        if(!is_string($url)||strlen($url)>300||!str_starts_with($url,$prefix)||!preg_match('/^[a-zA-Z0-9._-]+\.apk$/D',substr($url,strlen($prefix))))throw new Problem(422,'Artifact must use the central brand update channel.');
        return $this->db->transaction(function()use($job,$verified,$package,$version,$code,$channel,$protocol,$url):array {
            $tenant=$this->db->one('SELECT status,kind FROM tenants WHERE id=? FOR UPDATE',[$job['tenant_id']]);$current=$this->db->one('SELECT state FROM app_brand_builds WHERE id=? FOR UPDATE',[$job['id']]);$brand=$this->db->one('SELECT enabled FROM app_brands WHERE id=? FOR UPDATE',[$job['brand_id']]);
            $admitted=$tenant&&($tenant['kind']==='owner'?$this->db->one("SELECT id FROM users WHERE id=? AND role='owner' AND active=1",[$job['requested_by']]):($tenant['kind']==='reseller'?$this->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$job['tenant_id']]):null));
            if(!$tenant||$tenant['status']!=='active'||!$admitted||!$brand||!$brand['enabled']||!$current||!in_array($current['state'],['waiting_builder','building','publishing','publication_review'],true))throw new Problem(409,'Build changed before artifact registration.');
            $row=['id'=>Database::id(),'build_id'=>$job['id'],'brand_id'=>$job['brand_id'],'channel'=>$channel,'package_name'=>$package,'version_name'=>$version,'version_code'=>$code,'trial_protocol'=>$protocol,'signer_sha256'=>$verified['signer_sha256'],'apk_sha256'=>$verified['apk_sha256'],'artifact_url'=>$url,'enabled'=>1];
            if(isset($verified['source_sha'])){$row['source_sha']=$verified['source_sha'];$row['apk_size_bytes']=$verified['apk_size_bytes'];$row['provenance_sha256']=$verified['provenance_sha256'];}
            $columns=array_keys($row);$columns[]='verification_mac';$this->db->query('INSERT INTO app_brand_artifacts ('.implode(',',$columns).') VALUES ('.implode(',',array_fill(0,count($columns),'?')).')',[...array_values($row),$this->mac($row)]);$this->db->query("UPDATE app_brand_builds SET state='published' WHERE id=?",[$job['id']]);return ['id'=>$row['id']];
        });
    }
    public function approved(string $brandId,string $channel):?array {
        $row=$this->db->one('SELECT * FROM app_brand_artifacts WHERE brand_id=? AND channel=? AND enabled=1 ORDER BY version_code DESC LIMIT 1',[$brandId,$channel]);
        if(!$row)return null;if(!hash_equals($this->mac($row),$row['verification_mac']))throw new Problem(503,'Artifact registry integrity check failed.');return $row;
    }
    public function byId(string $id):?array {
        $row=$this->db->one('SELECT * FROM app_brand_artifacts WHERE id=? AND enabled=1',[Input::id($id)]);
        if(!$row)return null;if(!hash_equals($this->mac($row),$row['verification_mac']))throw new Problem(503,'Artifact registry integrity check failed.');return $row;
    }
    public function resellerInfo(array $artifact):array {return (new BrandDownloads($this->config,$this))->publicInfo($artifact);}
    /** Package admission only; signer is central artifact provenance, NOT a client
     * authenticity claim. Account ownership and device possession remain separate. */
    public function enrollmentPackage(mixed $package):?string {
        if(!is_string($package)||!preg_match('/^com\.extreme\.brand\.b([a-f0-9]{32})(\.test)?$/D',$package,$match))return null;
        if(!$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['016_public_trial_signup.sql']))return null;
        $artifact=$this->approved($match[1],isset($match[2])?'testing':'production');return $artifact&&$artifact['package_name']===$package?$package:null;
    }
}
