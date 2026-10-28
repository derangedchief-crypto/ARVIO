<?php
declare(strict_types=1);
namespace ControlCenter;

/** One persistent cosmetic brand per verified reseller or the platform's owner workspace. */
final class AppBrands
{
    private const ASSETS=['loading_asset','logo_asset','icon_asset'];
    // Platform release identity; never selected by reseller inputs.
    private const APP_VERSION='2.0.124';
    private const APP_VERSION_CODE=440;
    public function __construct(private readonly Database $db,private readonly Management $management,private readonly Auth $auth,private readonly ?BrandArtifacts $artifacts=null,private readonly ?Config $config=null) {}
    private function channel():string {
        $channel=$this->config?->get('brand_release',[])['default_channel']??'testing';if(!in_array($channel,['testing','production'],true))throw new Problem(503,'Invalid owner release channel.');
        if($channel==='production'&&!$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['021_production_brand_releases.sql']))throw new Problem(503,'Apply the production branding upgrade first.');return $channel;
    }
    private function channelScope(string $channel):string {
        return $this->db->one('SELECT name FROM schema_migrations WHERE name=?',['021_production_brand_releases.sql'])?" AND build_channel='".$channel."'":'';
    }
    private function platform(string $channel):array {
        if($this->config&&($this->config->get('brand_main_updates',[])[$channel.'_enabled']??false)===true&&$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['018_brand_main_updates.sql'])){
            $row=$this->db->one('SELECT * FROM brand_main_sources ORDER BY version_code DESC LIMIT 1');if($row)return ['version'=>$row['app_version'],'code'=>(int)$row['version_code'],'sha'=>$row['source_sha']];
        }
        if($channel==='production')throw new Problem(503,'Register the approved production main source before requesting a build.');
        // Historical testing snapshots stay buildable on their explicitly pinned
        // old source during cutover. This never chooses production or a new key.
        $pins=$this->config?->get('brand_builder',[])['sources']??[];
        if(isset($pins['2.0.115'])&&!isset($pins[self::APP_VERSION]))return ['version'=>'2.0.115','code'=>431,'sha'=>null];
        // Isolated fixtures deliberately exercise the installed historical APK
        // protocol. This override is unavailable on a production website.
        $fixture=$this->config&&$this->config->local()?$this->config->get('development_brand_platform'):null;
        if(is_array($fixture))return ['version'=>$fixture['version'],'code'=>(int)$fixture['code'],'sha'=>null];
        return ['version'=>self::APP_VERSION,'code'=>self::APP_VERSION_CODE,'sha'=>null];
    }
    private function ready():void {if(!$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['014_reseller_app_brands.sql']))throw new Problem(503,'Apply the app-branding database upgrade first.');}
    private function tenant(array $actor,?string $id,bool $lock=false):array {
        $this->ready();$t=$this->management->workspace($actor,$id,$lock);
        if($t['status']!=='active')throw new Problem(409,'Workspace suspended.');
        // The administrator may prepare ONE separate owner-branded app without
        // creating/relinking a reseller account. This grants no trial/panel rights
        // and never changes the existing generic app or its update channel.
        if($t['kind']==='owner'){Auth::owner($actor);return $t;}
        if($t['kind']!=='reseller')throw new Problem(409,'Choose an owner or verified reseller workspace.');
        if(!$this->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$t['id']]))throw new Problem(409,'A verified linked reseller is required for branded apps.');return $t;
    }
    public function get(array $actor,array $input):array {
        Input::keys($input,['tenant_id']);$t=$this->tenant($actor,$input['tenant_id']??null);$brand=$this->db->one('SELECT * FROM app_brands WHERE tenant_id=?',[$t['id']]);
        $channel=$this->channel();$rows=$brand?$this->db->all('SELECT * FROM app_brand_builds WHERE brand_id=?'.$this->channelScope($channel).' ORDER BY created_at DESC,id DESC LIMIT 10',[$brand['id']]):[];$builds=[];
        foreach($rows as $index=>$row){$item=array_intersect_key($row,array_flip(['id','brand_version','app_version','state','created_at']));if($index===0)$item['progress']=(new BrandBuildProgress($this->db,$this->config))->view($row);$builds[]=$item;}
        $published=[];if($brand&&$this->artifacts&&$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['016_public_trial_signup.sql']))foreach($channel==='production'?['production']:['testing','production'] as $artifactChannel){$artifact=$this->artifacts->approved($brand['id'],$artifactChannel);if($artifact)$published[]=$this->artifacts->resellerInfo($artifact);}
        return ['brand'=>$brand,'builds'=>$builds,'artifacts'=>$published,'release_channel'=>$channel,'tenant_id'=>$t['id'],'workspace_kind'=>$t['kind'],'trial_creation_enabled'=>false];
    }
    public function upload(array $actor,array $input):array {
        Input::keys($input,['tenant_id','image']);$this->auth->limit('brand-image',$actor['id'],20,3600);$t=$this->tenant($actor,$input['tenant_id']??null);
        $encoded=$input['image']??null;if(!is_string($encoded)||strlen($encoded)>1400000)throw new Problem(422,'Upload a PNG, JPEG or WebP image up to 1 MB.');
        $bytes=base64_decode($encoded,true);if($bytes===false||strlen($bytes)<12||strlen($bytes)>1024*1024)throw new Problem(422,'Invalid image.');
        $info=@getimagesizefromstring($bytes);if(!$info||!in_array($info['mime'],['image/png','image/jpeg','image/webp'],true)||$info[0]<1||$info[1]<1||$info[0]>4096||$info[1]>4096||$info[0]*$info[1]>8_000_000)throw new Problem(422,'Use a PNG, JPEG or WebP image up to 4096 pixels and 8 megapixels.');
        return $this->db->transaction(function()use($actor,$t,$bytes,$info):array {
            $this->tenant($actor,$t['id'],true);$hash=hash('sha256',$bytes);$prior=$this->db->one('SELECT id FROM app_brand_assets WHERE tenant_id=? AND sha256=?',[$t['id'],$hash]);if($prior)return ['id'=>$prior['id']];
            if((int)$this->db->query('SELECT COUNT(*) FROM app_brand_assets WHERE tenant_id=?',[$t['id']])->fetchColumn()>=100)throw new Problem(409,'Workspace image quota reached. Ask the owner to review retained builds.');
            $id=Database::id();$this->db->query('INSERT INTO app_brand_assets (id,tenant_id,mime,width,height,sha256,image,created_by) VALUES (?,?,?,?,?,?,?,?)',[$id,$t['id'],$info['mime'],$info[0],$info[1],$hash,$bytes,$actor['id']]);$this->auth->audit($actor,'brand.image_uploaded',$id,$t['id']);return ['id'=>$id];
        });
    }
    public function image(array $actor,string $id):array {
        $this->ready();$row=$this->db->one('SELECT * FROM app_brand_assets WHERE id=?',[Input::id($id)]);if(!$row)throw new Problem(404,'Image not found.');$this->management->tenant($actor,$row['tenant_id']);return $row;
    }
    public function save(array $actor,array $input):array {
        Input::keys($input,['tenant_id','name','expected_version',...self::ASSETS]);$name=Input::text($input['name']??null,'app name',60);
        if(!preg_match('/^[\p{L}\p{N}][\p{L}\p{N} .&\x{2019}\x{0027}()+,\-]*$/uD',$name))throw new Problem(422,'Use a short app name without control characters, markup or formatting codes.');
        $this->auth->limit('brand-edit',$actor['id'],30,3600);
        return $this->db->transaction(function()use($actor,$input,$name):array {
            $t=$this->tenant($actor,$input['tenant_id']??null,true);$prior=$this->db->one('SELECT * FROM app_brands WHERE tenant_id=? FOR UPDATE',[$t['id']]);
            $expected=Input::integer($input['expected_version']??0,0,99998);if($expected!==(int)($prior['version']??0))throw new Problem(409,'Brand changed; load its current version first.');if($prior&&!$prior['enabled'])throw new Problem(409,'Brand editing is disabled by the owner.');
            $assets=[];foreach(self::ASSETS as $key){$id=Input::id($input[$key]??null);if(!$this->db->one('SELECT id FROM app_brand_assets WHERE id=? AND tenant_id=?',[$id,$t['id']]))throw new Problem(404,'Choose an image in your own workspace.');$assets[]=$id;}
            $id=$prior['id']??Database::id();$version=$expected+1;$package=$prior['package_name']??'com.extreme.brand.b'.$id;
            if($prior)$this->db->query('UPDATE app_brands SET name=?,version=?,loading_asset=?,logo_asset=?,icon_asset=?,updated_by=? WHERE id=?',[$name,$version,...$assets,$actor['id'],$id]);
            else $this->db->query('INSERT INTO app_brands (id,tenant_id,package_name,name,loading_asset,logo_asset,icon_asset,updated_by) VALUES (?,?,?,?,?,?,?,?)',[$id,$t['id'],$package,$name,...$assets,$actor['id']]);
            $this->auth->audit($actor,'brand.saved',$id,$t['id'],['version'=>$version]);return ['id'=>$id,'version'=>$version,'package_name'=>$package];
        });
    }
    public function requestBuild(array $actor,array $input):array {
        Input::keys($input,['tenant_id','expected_version']);$this->auth->limit('brand-build',$actor['id'],10,3600);
        return $this->db->transaction(function()use($actor,$input):array {
            $t=$this->tenant($actor,$input['tenant_id']??null,true);$brand=$this->db->one('SELECT * FROM app_brands WHERE tenant_id=? FOR UPDATE',[$t['id']]);if(!$brand||!$brand['enabled'])throw new Problem(409,'Save an enabled brand first.');
            if((int)$brand['version']!==Input::integer($input['expected_version']??null,1,100000))throw new Problem(409,'Brand changed; reload before requesting a build.');
            $channel=$this->channel();$platform=$this->platform($channel);
            $scope=$this->channelScope($channel);$old=$this->db->one('SELECT id,state FROM app_brand_builds WHERE brand_id=? AND brand_version=? AND app_version=?'.$scope,[$brand['id'],$brand['version'],$platform['version']]);
            if($old){
                if($old['state']==='cancelled'){
                    if($this->db->one("SELECT id FROM app_brand_builds WHERE brand_id=?".$scope." AND state IN ('waiting_builder','building','ready_to_publish','publishing','publication_review')",[$brand['id']]))throw new Problem(409,'This brand already has a queued build.');
                    $this->db->query("UPDATE app_brand_builds SET state='waiting_builder' WHERE id=?",[$old['id']]);$old['state']='waiting_builder';$this->auth->audit($actor,'brand.build_requeued',$old['id'],$t['id']);
                }return $old;
            }
            if($this->db->one("SELECT id FROM app_brand_builds WHERE brand_id=?".$scope." AND state IN ('waiting_builder','building','ready_to_publish','publishing','publication_review')",[$brand['id']]))throw new Problem(409,'This brand already has a queued build; finish or cancel it first.');
            $id=Database::id();$images=[];foreach(self::ASSETS as $key){$asset=$this->db->one('SELECT id,sha256 FROM app_brand_assets WHERE id=? AND tenant_id=?',[$brand[$key],$t['id']]);if(!$asset)throw new Problem(409,'A saved image is unavailable.');$images[$key]=$asset;}
            $manifest=['schema_version'=>1,'brand_id'=>$brand['id'],'brand_version'=>(int)$brand['version'],'app_version'=>$platform['version'],'app_version_code'=>$platform['code'],'app_name'=>$brand['name'],'package_name'=>$brand['package_name'],'assets'=>$images];
            $this->db->query('INSERT INTO app_brand_builds (id,brand_id,brand_version,app_version,manifest,requested_by) VALUES (?,?,?,?,?,?)',[$id,$brand['id'],$brand['version'],$platform['version'],json_encode($manifest,JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR),$actor['id']]);
            if(!$platform['sha']&&$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['021_production_brand_releases.sql']))$this->db->query('UPDATE app_brand_builds SET build_channel=? WHERE id=?',[$channel,$id]);
            if($platform['sha']){
                $this->db->query('UPDATE app_brand_builds SET build_channel=?,source_sha=? WHERE id=?',[$channel,$platform['sha'],$id]);
                $this->db->query('INSERT INTO brand_main_jobs (build_id,source_sha) VALUES (?,?)',[$id,$platform['sha']]);
            }
            foreach(array_unique(array_column($images,'id')) as $asset)$this->db->query('INSERT INTO app_brand_build_assets (build_id,asset_id) VALUES (?,?)',[$id,$asset]);
            $this->auth->audit($actor,'brand.build_requested',$id,$t['id'],['version'=>(int)$brand['version']]);return ['id'=>$id,'state'=>'waiting_builder'];
        });
    }
    /** Internal export for the owner download/CLI and restricted central runner.
     * Inputs contain no signing secrets or update URLs. */
    public function export(string $buildId):array {
        $this->ready();$row=$this->db->one('SELECT j.*,b.tenant_id,b.enabled FROM app_brand_builds j JOIN app_brands b ON b.id=j.brand_id WHERE j.id=?',[Input::id($buildId)]);if(!$row||!$row['enabled']||!in_array($row['state'],['waiting_builder','building','build_failed','ready_to_publish'],true))throw new Problem(404,'Build not available.');
        $t=$this->db->one("SELECT t.id FROM tenants t LEFT JOIN panel_resellers r ON r.tenant_id=t.id WHERE t.id=? AND t.status='active' AND (t.kind='owner' OR (t.kind='reseller' AND r.tenant_id IS NOT NULL))",[$row['tenant_id']]);if(!$t)throw new Problem(409,'Workspace suspended or reseller link removed.');$manifest=json_decode($row['manifest'],true,12,JSON_THROW_ON_ERROR);$images=[];
        foreach($manifest['assets'] as $role=>$asset){$a=$this->db->one('SELECT image FROM app_brand_assets WHERE id=? AND tenant_id=?',[$asset['id'],$row['tenant_id']]);if(!$a||!hash_equals($asset['sha256'],hash('sha256',$a['image'])))throw new Problem(409,'Build image integrity failed.');$images[$role]=base64_encode($a['image']);}
        return ['manifest'=>$manifest,'images'=>$images];
    }
    public function downloadInputs(array $actor,string $buildId):array {
        Auth::owner($actor);$this->auth->limit('brand-input-download',$actor['id'],20,3600);$id=Input::id($buildId);$bundle=$this->export($id);
        $brand=$this->db->one('SELECT tenant_id FROM app_brands WHERE id=?',[$bundle['manifest']['brand_id']]);
        $this->auth->audit($actor,'brand.inputs_downloaded',$id,$brand['tenant_id'],['brand_version'=>$bundle['manifest']['brand_version']]);
        return ['filename'=>'brand-input-'.$id.'.json','body'=>json_encode($bundle,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES|JSON_THROW_ON_ERROR)];
    }
    public function cancelBuild(array $actor,string $id,array $input):array {
        $this->ready();Input::keys($input,[]);return $this->db->transaction(function()use($actor,$id):array {
            $job=$this->db->one('SELECT j.id,b.tenant_id FROM app_brand_builds j JOIN app_brands b ON b.id=j.brand_id WHERE j.id=?',[Input::id($id)]);if(!$job)throw new Problem(404,'Build not found.');$tenant=$job['tenant_id'];$this->management->tenant($actor,$tenant,true);
            $job=$this->db->one('SELECT * FROM app_brand_builds WHERE id=? FOR UPDATE',[$id]);if($actor['role']!=='owner'&&$job['requested_by']!==$actor['id'])throw new Problem(403,'Only the requester or owner can cancel this build.');if(!in_array($job['state'],['waiting_builder','ready_to_publish','build_failed','cancelled'],true))throw new Problem(409,'Only a queued, prepared or failed unpublished build can be cancelled.');
            $this->db->query("UPDATE app_brand_builds SET state='cancelled' WHERE id=?",[$id]);$this->auth->audit($actor,'brand.build_cancelled',$id,$tenant);return ['id'=>$id,'state'=>'cancelled'];
        });
    }
    public function maintain():void {
        $this->db->query("DELETE a FROM app_brand_assets a WHERE a.created_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY) AND NOT EXISTS(SELECT 1 FROM app_brands b WHERE b.loading_asset=a.id OR b.logo_asset=a.id OR b.icon_asset=a.id) AND NOT EXISTS(SELECT 1 FROM app_brand_build_assets j WHERE j.asset_id=a.id)");
    }
}
