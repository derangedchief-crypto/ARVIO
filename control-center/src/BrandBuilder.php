<?php
declare(strict_types=1);
namespace ControlCenter;

/** Privileged central-builder CLI ONLY. No session, reseller or public HTTP route. */
final class BrandBuilder
{
    public function __construct(private readonly Database $db,private readonly Config $config,private readonly AppBrands $brands,private readonly BrandArtifacts $artifacts,private readonly Secrets $secrets,private readonly Auth $auth) {}
    private function policy():array {
        if(!$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['017_central_brand_builder.sql']))throw new Problem(503,'Apply the central-builder upgrade first.');
        $c=$this->config->get('brand_builder',[]);if(!is_array($c)||($c['enabled']??null)!==true)throw new Problem(503,'Central builder is disabled.');
        $channels=$c['channels']??['testing'];if(!is_array($channels)||!array_is_list($channels)||!$channels||array_diff($channels,['testing','production']))throw new Problem(503,'Invalid central builder channel policy.');return $c;
    }
    private function channel(array $c,mixed $channel):string {if(!is_string($channel)||!in_array($channel,$c['channels']??['testing'],true))throw new Problem(403,'Build channel is not authorized by the owner.');return $channel;}
    private function source(array $c,string $appVersion,mixed $sha):string {
        $expected=$c['sources'][$appVersion]??null;
        if(!is_string($sha)||!preg_match('/^[a-f0-9]{40}$/D',$sha)||!is_string($expected)||!hash_equals($expected,$sha))throw new Problem(409,'Run the owner-pinned source commit for this platform version.');return $sha;
    }
    private function lockJob(string $id):array {
        $before=$this->db->one('SELECT b.tenant_id FROM app_brand_builds j JOIN app_brands b ON b.id=j.brand_id WHERE j.id=?',[Input::id($id)]);if(!$before)throw new Problem(404,'Build not found.');
        $t=$this->db->one('SELECT * FROM tenants WHERE id=? FOR UPDATE',[$before['tenant_id']]);$job=$this->db->one('SELECT * FROM app_brand_builds WHERE id=? FOR UPDATE',[$id]);$brand=$this->db->one('SELECT * FROM app_brands WHERE id=? FOR UPDATE',[$job['brand_id']]);
        $admitted=$t&&($t['kind']==='owner'?$this->db->one("SELECT id FROM users WHERE id=? AND role='owner' AND active=1",[$job['requested_by']]):($t['kind']==='reseller'?$this->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$t['id']]):null));
        if(!$t||$t['status']!=='active'||!$admitted||!$brand||!$brand['enabled'])throw new Problem(409,'Brand workspace is unavailable for building or publication.');
        $job['tenant_id']=$t['id'];return $job;
    }
    private function leased(array $job,mixed $token,array $states,bool $allowExpired=false):void {
        if(!in_array($job['state'],$states,true)||!is_string($token)||!preg_match('/^[a-f0-9]{64}$/D',$token)||!is_string($job['lease_hash'])||!hash_equals($job['lease_hash'],hash('sha256',$token))||(!$allowExpired&&(!$job['lease_until']||strtotime($job['lease_until'].' UTC')<=time())))throw new Problem(409,'Build lease is stale or no longer owns this operation.');
    }
    public function expected(array $job,string $channel):array {
        $m=json_decode($job['manifest'],true,12,JSON_THROW_ON_ERROR);$revision=(int)$job['brand_version'];$code=(int)$m['app_version_code']*100000+$revision;
        if($revision<1||$revision>99999||$code>2147483647||($m['brand_id']??null)!==$job['brand_id']||($m['brand_version']??null)!==$revision||($m['app_version']??null)!==$job['app_version'])throw new Problem(409,'Invalid pinned brand build identity.');
        $version=$m['app_version'].'.'.$revision.($channel==='testing'?'-test':'');$tag='brand-'.$job['brand_id'].($channel==='testing'?'-testing':'');$name='Brand-'.$version.'.apk';
        $signer=$this->config->get('brand_signer_sha256','3273d0b0294a689565ab4b0c8ef7e1887e2ca680240718f5521628b5c46328b7');if(!is_string($signer)||!preg_match('/^[a-f0-9]{64}$/D',$signer))throw new Problem(503,'Invalid central signing policy.');
        return ['channel'=>$channel,'package_name'=>$m['package_name'].($channel==='testing'?'.test':''),'version_name'=>$version,'version_code'=>$code,'trial_protocol'=>1,'signer_sha256'=>$signer,'release_tag'=>$tag,'asset_name'=>$name,'artifact_url'=>'https://github.com/derangedchief-crypto/ARVIO/releases/download/'.$tag.'/'.$name];
    }
    private function context(array $job,string $token):array {
        return ['build_id'=>$job['id'],'source_sha'=>$job['source_sha'],'channel'=>$job['build_channel'],'lease_token'=>$token,'expected'=>$this->expected($job,$job['build_channel']),'bundle'=>$this->brands->export($job['id']),'prepared_receipt'=>$job['artifact_receipt']?json_decode($job['artifact_receipt'],true):null,'prepared_run_id'=>$job['artifact_receipt']?$job['runner_run_id']:null];
    }
    public function claim(array $input):array {
        Input::keys($input,['build_id','channel','source_sha','runner_run_id']);$c=$this->policy();$channel=$this->channel($c,$input['channel']??null);$run=$input['runner_run_id']??null;if($run!==null&&(!is_string($run)||!preg_match('/^[1-9][0-9]{0,19}$/D',$run)))throw new Problem(422,'Invalid central workflow run identity.');
        if(!isset($input['build_id'])||$input['build_id']===''){
            $versions=[];foreach($c['sources']??[] as $version=>$sha)if(is_string($sha)&&$sha===($input['source_sha']??null))$versions[]=(string)$version;
            if(!$versions)throw new Problem(409,'No platform version is authorized for this source commit.');
            $candidate=$this->db->one("SELECT j.id FROM app_brand_builds j JOIN app_brands b ON b.id=j.brand_id JOIN tenants t ON t.id=b.tenant_id LEFT JOIN panel_resellers r ON r.tenant_id=t.id WHERE j.app_version IN (".implode(',',array_fill(0,count($versions),'?')).") AND b.enabled=1 AND t.status='active' AND (t.kind='owner' OR (t.kind='reseller' AND r.tenant_id IS NOT NULL)) AND j.build_attempt<3 AND (j.state IN ('waiting_builder','build_failed') OR (j.state='building' AND j.lease_until<=UTC_TIMESTAMP())) AND (j.build_channel IS NULL OR j.build_channel=?) AND (j.source_sha IS NULL OR j.source_sha=?) ORDER BY j.created_at,j.id LIMIT 1",[...$versions,$channel,$input['source_sha']]);
            if(!$candidate)return ['state'=>'no_job'];$input['build_id']=$candidate['id'];
        }
        return $this->db->transaction(function()use($input,$c,$channel,$run):array {
            $job=$this->lockJob(Input::id($input['build_id']??null));$sha=$this->source($c,$job['app_version'],$input['source_sha']??null);
            if($job['build_channel']!==null&&$job['build_channel']!==$channel)throw new Problem(409,'This build is already pinned to a different channel.');
            if($job['source_sha']!==null&&$job['source_sha']!==$sha)throw new Problem(409,'This build is already pinned to another source commit.');
            $expired=$job['lease_until']!==null&&strtotime($job['lease_until'].' UTC')<=time();
            if($job['state']==='publishing'||$job['state']==='publication_review')throw new Problem(409,'Publication may already exist; reconcile it instead of rebuilding.');
            if(!in_array($job['state'],['waiting_builder','build_failed','ready_to_publish'],true)&&!($job['state']==='building'&&$expired))throw new Problem(409,'Build is unavailable or another runner owns the lease.');
            if($job['state']==='ready_to_publish'&&!$expired)throw new Problem(409,'Prepared build is still owned by its runner.');
            if($job['state']==='ready_to_publish'){
                if(!$job['artifact_receipt']||!$job['runner_run_id'])throw new Problem(409,'Prepared artifact needs owner review; do not rebuild the same version.');
                $token=bin2hex(random_bytes(32));$this->db->query('UPDATE app_brand_builds SET lease_hash=?,lease_until=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 30 MINUTE) WHERE id=?',[hash('sha256',$token),$job['id']]);return $this->context($job,$token);
            }
            if((int)$job['build_attempt']>=3)throw new Problem(409,'Build retry limit reached; owner review is required.');
            $this->brands->export($job['id']);$token=bin2hex(random_bytes(32));
            if($job['artifact_receipt'])throw new Problem(409,'A prepared version cannot be rebuilt with different bytes.');
            $this->db->query("UPDATE app_brand_builds SET state='building',build_channel=?,source_sha=?,runner_run_id=?,lease_hash=?,lease_until=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 30 MINUTE),build_attempt=build_attempt+1,artifact_receipt=NULL,failure_code=NULL,build_started_at=UTC_TIMESTAMP(),build_finished_at=NULL WHERE id=?",[$channel,$sha,$run,hash('sha256',$token),$job['id']]);
            $job=array_replace($job,['state'=>'building','build_channel'=>$channel,'source_sha'=>$sha,'runner_run_id'=>$run]);$this->auth->audit(null,'brand.builder_claimed',$job['id'],$job['tenant_id'],['channel'=>$channel,'attempt'=>(int)$job['build_attempt']+1]);return $this->context($job,$token);
        });
    }
    public function heartbeat(array $input):array {
        Input::keys($input,['build_id','lease_token']);$this->policy();return $this->db->transaction(function()use($input):array {
            $job=$this->lockJob(Input::id($input['build_id']??null));$this->leased($job,$input['lease_token']??null,['building','ready_to_publish','publishing']);
            $this->db->query('UPDATE app_brand_builds SET lease_until=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 30 MINUTE) WHERE id=?',[$job['id']]);return ['state'=>$job['state']];
        });
    }
    private function receipt(array $job,array $input):array {
        Input::keys($input,['channel','package_name','version_name','version_code','trial_protocol','signer_sha256','apk_sha256','apk_size_bytes','provenance_sha256','artifact_url','source_sha']);
        $expected=$this->expected($job,$job['build_channel']);unset($expected['release_tag'],$expected['asset_name']);foreach($expected as $key=>$value)if(($input[$key]??null)!==$value)throw new Problem(409,'Verified artifact does not match its pinned package/version/signing/channel policy.');
        if(($input['source_sha']??null)!==$job['source_sha'])throw new Problem(409,'Artifact source provenance does not match.');foreach(['apk_sha256','provenance_sha256'] as $key)if(!is_string($input[$key]??null)||!preg_match('/^[a-f0-9]{64}$/D',$input[$key]))throw new Problem(422,'Invalid artifact provenance digest.');Input::integer($input['apk_size_bytes']??null,1024,536870912);ksort($input);return $input;
    }
    public function prepared(array $input):array {
        Input::keys($input,['build_id','lease_token','receipt','release_lease']);$this->policy();if(isset($input['release_lease'])&&!is_bool($input['release_lease']))throw new Problem(422,'Invalid lease release.');return $this->db->transaction(function()use($input):array {
            $job=$this->lockJob(Input::id($input['build_id']??null));$this->leased($job,$input['lease_token']??null,['building','ready_to_publish']);if(!is_array($input['receipt']??null))throw new Problem(422,'Missing artifact verification receipt.');$receipt=$this->receipt($job,$input['receipt']);
            if($job['state']==='ready_to_publish'&&json_decode($job['artifact_receipt'],true)!==$receipt)throw new Problem(409,'Prepared receipt cannot change within a lease.');
            $this->db->query("UPDATE app_brand_builds SET state='ready_to_publish',artifact_receipt=?,build_finished_at=UTC_TIMESTAMP(),lease_until=".(($input['release_lease']??false)?'UTC_TIMESTAMP()':'lease_until')." WHERE id=?",[json_encode($receipt,JSON_UNESCAPED_SLASHES|JSON_THROW_ON_ERROR),$job['id']]);return ['state'=>'ready_to_publish','published'=>false];
        });
    }
    public function authorize(array $input):array {
        Input::keys($input,['build_id','lease_token','confirmed_publication']);$c=$this->policy();if(($c['publication_enabled']??null)!==true||($input['confirmed_publication']??null)!==true)throw new Problem(403,'Central artifact publication is disabled or unconfirmed.');
        return $this->db->transaction(function()use($input,$c):array {
            $job=$this->lockJob(Input::id($input['build_id']??null));$this->leased($job,$input['lease_token']??null,['ready_to_publish','publishing']);$this->channel($c,$job['build_channel']);$this->source($c,$job['app_version'],$job['source_sha']);$expected=$this->expected($job,$job['build_channel']);
            $prior=$this->artifacts->approved($job['brand_id'],$job['build_channel']);if($prior&&(int)$prior['version_code']>=$expected['version_code'])throw new Problem(409,'Publication would overwrite an equal or newer approved brand update.');
            if($this->db->one("SELECT id FROM app_brand_builds WHERE brand_id=? AND build_channel=? AND id<>? AND state IN ('publishing','publication_review')",[$job['brand_id'],$job['build_channel'],$job['id']]))throw new Problem(409,'A previous channel publication needs reconciliation.');
            if(!$job['artifact_receipt'])throw new Problem(409,'Verify the signed APK before publication.');$this->receipt($job,json_decode($job['artifact_receipt'],true));
            $this->db->query("UPDATE app_brand_builds SET state='publishing',lease_until=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 30 MINUTE) WHERE id=?",[$job['id']]);$this->auth->audit(null,'brand.publication_authorized',$job['id'],$job['tenant_id']);return ['state'=>'publishing','expected'=>$expected];
        });
    }
    /** Preflight before any remote publication; local delivery is an explicit gate. */
    public function delivery(array $input):array {
        Input::keys($input,['build_id','lease_token']);$this->policy();
        if(!(new BrandDownloads($this->config,$this->artifacts))->enabled())throw new Problem(503,'Enable site APK delivery before publishing.');
        $job=$this->lockForDelivery($input,['ready_to_publish']);return ['ready'=>true];
    }
    private function lockForDelivery(array $input,array $states):array {
        return $this->db->transaction(function()use($input,$states):array{$job=$this->lockJob(Input::id($input['build_id']??null));$this->leased($job,$input['lease_token']??null,$states);if(!$job['artifact_receipt'])throw new Problem(409,'Missing frozen artifact receipt.');return $job;});
    }
    /** Only the restricted SSH CLI streams bytes here, never a reseller HTTP route. */
    public function mirror(array $input,$stream):array {
        Input::keys($input,['build_id','lease_token']);$this->policy();$states=['publishing','publication_review'];
        $job=$this->lockForDelivery($input,$states);$receipt=$this->receipt($job,json_decode($job['artifact_receipt'],true,12,JSON_THROW_ON_ERROR));
        $downloads=new BrandDownloads($this->config,$this->artifacts);
        return $downloads->ingest($receipt,$stream,function(callable $install)use($input,$states):void{
            $this->db->transaction(function()use($input,$states,$install):void{$current=$this->lockJob(Input::id($input['build_id']));$this->leased($current,$input['lease_token'],$states);$install();});
        });
    }
    public function finish(array $input):array {
        Input::keys($input,['build_id','lease_token','receipt']);$this->policy();return $this->db->transaction(function()use($input):array {
            $job=$this->lockJob(Input::id($input['build_id']??null));$this->leased($job,$input['lease_token']??null,['publishing','publication_review','published'],true);if(!is_array($input['receipt']??null))throw new Problem(422,'Missing published download verification.');$receipt=$this->receipt($job,$input['receipt']);if($receipt!==json_decode($job['artifact_receipt'],true))throw new Problem(409,'Published bytes differ from the prepared artifact.');
            if($job['state']==='published')return ['state'=>'published','idempotent'=>true];
            $downloads=new BrandDownloads($this->config,$this->artifacts);if($downloads->enabled()&&!$downloads->available($receipt))throw new Problem(409,'Verified site APK copy is missing.');
            $saved=$this->artifacts->recordVerified($job['id'],$receipt);$this->db->query("UPDATE app_brand_builds SET published_at=UTC_TIMESTAMP(),failure_code=NULL WHERE id=?",[$job['id']]);$this->auth->audit(null,'brand.artifact_published',$job['id'],$job['tenant_id']);return ['state'=>'published','artifact_id'=>$saved['id']];
        });
    }
    public function fail(array $input):array {
        Input::keys($input,['build_id','lease_token']);$this->policy();return $this->db->transaction(function()use($input):array {
            $job=$this->lockJob(Input::id($input['build_id']??null));$this->leased($job,$input['lease_token']??null,['building','ready_to_publish','publishing','publication_review'],true);$uncertain=in_array($job['state'],['publishing','publication_review'],true);$state=$uncertain?'publication_review':'build_failed';
            if(!$uncertain&&$job['artifact_receipt'])$state='ready_to_publish';
            $this->db->query('UPDATE app_brand_builds SET state=?,failure_code=?,lease_until=UTC_TIMESTAMP() WHERE id=?',[$state,$uncertain?'publication_uncertain':($job['artifact_receipt']?'prepared_artifact_review':'build_failed'),$job['id']]);return ['state'=>$state];
        });
    }
    /** Resume only verification of frozen published bytes; never creates a new build. */
    public function reconcile(array $input):array {
        Input::keys($input,['build_id','source_sha','channel']);$c=$this->policy();$channel=$this->channel($c,$input['channel']??null);return $this->db->transaction(function()use($input,$c,$channel):array {
            $job=$this->lockJob(Input::id($input['build_id']??null));$this->source($c,$job['app_version'],$input['source_sha']??null);
            if(!in_array($job['state'],['publishing','publication_review'],true)||strtotime($job['lease_until'].' UTC')>time()||$job['build_channel']!==$channel||$job['source_sha']!==$input['source_sha']||!$job['artifact_receipt'])throw new Problem(409,'No expired or stopped publication is available to reconcile.');
            $token=bin2hex(random_bytes(32));$this->db->query("UPDATE app_brand_builds SET state='publication_review',lease_hash=?,lease_until=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 30 MINUTE) WHERE id=?",[hash('sha256',$token),$job['id']]);
            return ['build_id'=>$job['id'],'source_sha'=>$job['source_sha'],'channel'=>$channel,'lease_token'=>$token,'expected'=>$this->expected($job,$channel),'receipt'=>json_decode($job['artifact_receipt'],true)];
        });
    }
    public function maintain():void {
        // Expiry is not publication success. Keep frozen receipts/tokens for
        // deliberate recovery and never recycle a possibly published identity.
        $this->db->query("UPDATE app_brand_builds SET state='build_failed',failure_code='build_lease_expired' WHERE state='building' AND lease_until<=UTC_TIMESTAMP() LIMIT 1000");
        $this->db->query("UPDATE app_brand_builds SET state='publication_review',failure_code='publication_uncertain' WHERE state='publishing' AND lease_until<=UTC_TIMESTAMP() LIMIT 1000");
    }
}
