<?php
declare(strict_types=1);
namespace ControlCenter;

/** Stage milestones, NOT a made-up percentage of Gradle compilation. All clocks
 * are server-side. Mutators are called only while the builder owns the job lock. */
final class BrandBuildProgress
{
    public const PHASES=['preparing','building','verifying','retaining','publishing','mirroring'];
    private const LABELS=['preparing'=>'Preparing approved branding','building'=>'Building and signing APK','verifying'=>'Checking package and signature','retaining'=>'Retaining and verifying the signed build','publishing'=>'Publishing and verifying the download','mirroring'=>'Copying the verified APK to the website'];
    public function __construct(private readonly Database $db,private readonly Config $config) {}
    public function ready():bool {return (bool)$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['020_brand_build_progress.sql']);}
    public function reset(string $id):void {if($this->ready())$this->db->query("UPDATE app_brand_builds SET progress_phase=NULL,progress_started_at=NULL,progress_updated_at=UTC_TIMESTAMP(6),progress_times='{}' WHERE id=?",[$id]);}
    public function advance(array $job,string $phase,bool $countPrevious=true):void {
        if(!$this->ready())return;
        if(!in_array($phase,[...self::PHASES,'prepared','complete','stopped'],true))throw new Problem(422,'Invalid build progress phase.');
        $times=($job['progress_times']??null)?json_decode($job['progress_times'],true,8,JSON_THROW_ON_ERROR):[];$old=$job['progress_phase']??null;
        if($old!==$phase&&$countPrevious&&in_array($old,self::PHASES,true)&&!empty($job['progress_started_at'])){
            $elapsed=(int)$this->db->query('SELECT GREATEST(0,TIMESTAMPDIFF(SECOND,?,UTC_TIMESTAMP(6)))',[$job['progress_started_at']])->fetchColumn();
            if($elapsed<=7200)$times[$old]=(int)($times[$old]??0)+$elapsed;
        }
        $this->db->query('UPDATE app_brand_builds SET progress_phase=?,progress_started_at='.($old===$phase?'progress_started_at':'UTC_TIMESTAMP(6)').',progress_updated_at=UTC_TIMESTAMP(6),progress_times=? WHERE id=?',[$phase,json_encode((object)$times,JSON_THROW_ON_ERROR),$job['id']]);
    }
    public function report(array $job,mixed $phase):void {
        if(!is_string($phase)||!in_array($phase,self::PHASES,true))throw new Problem(422,'Invalid build progress phase.');
        if(!$this->ready())throw new Problem(503,'Apply the build progress upgrade first.');
        $allowed=match($job['state']){'building'=>array_slice(self::PHASES,0,4),'ready_to_publish'=>['retaining'],'publishing'=>['publishing','mirroring'],default=>[]};
        if(!in_array($phase,$allowed,true))throw new Problem(409,'Progress does not match the current build state.');
        $old=array_search($job['progress_phase']??'',self::PHASES,true);$next=array_search($phase,self::PHASES,true);
        if($job['state']!=='ready_to_publish'&&$old!==false&&$next<$old)throw new Problem(409,'Build stage cannot move backwards within an attempt.');
        $this->advance($job,$phase);
    }
    private function range(array $values):?array {
        $values=array_values(array_filter($values,static fn($n)=>is_int($n)&&$n>=1&&$n<=14400));if(count($values)<3)return null;
        sort($values);return ['lower_seconds'=>$values[0],'upper_seconds'=>$values[count($values)-1],'samples'=>count($values)];
    }
    private function baseline(?string $channel,bool $finishing):?array {
        $path=$this->config->root.'/deploy/brand-build-timings.json';if(!is_file($path)||filesize($path)>8192)return null;
        try{$data=json_decode(file_get_contents($path),true,8,JSON_THROW_ON_ERROR);}catch(\JsonException){return null;}
        if(!is_array($data)||($data['schema_version']??null)!==1||($data['channel']??null)!==$channel||!is_array($data['build_seconds']??null)||!is_array($data['publication_seconds']??null))return null;
        $build=array_values(array_filter($data['build_seconds']??[],static fn($n)=>is_int($n)&&$n>=60&&$n<=7200));$finish=array_values(array_filter($data['publication_seconds']??[],static fn($n)=>is_int($n)&&$n>=1&&$n<=1800));
        if(count($build)<3||count($finish)<2)return null;
        return ['lower_seconds'=>($finishing?0:min($build))+min($finish),'upper_seconds'=>($finishing?0:max($build))+max($finish),'samples'=>count($build),'source'=>'measured_baseline'];
    }
    private function eta(array $job,?string $phase):?array {
        $finishing=in_array($phase,['publishing','mirroring'],true);$start=$finishing?($job['progress_started_at']??null):($job['build_started_at']??null);if(!$start)return null;
        $from=$finishing?array_search($phase,self::PHASES,true):0;$values=[];
        if($this->ready()&&$phase!==null&&in_array($phase,self::PHASES,true)){
            $from=array_search($phase,self::PHASES,true);
            foreach($this->db->all("SELECT progress_times FROM app_brand_builds WHERE state='published' AND build_channel=? AND id<>? AND progress_times IS NOT NULL ORDER BY published_at DESC,id DESC LIMIT 20",[$job['build_channel'],$job['id']]) as $row){
                $times=json_decode($row['progress_times'],true,8,JSON_THROW_ON_ERROR);$sum=0;$valid=true;
                foreach(array_slice(self::PHASES,$from) as $step){if(!isset($times[$step])||!is_int($times[$step])||$times[$step]<0||$times[$step]>14400){$valid=false;break;}$sum+=$times[$step];}if($valid)$values[]=$sum;
            }
            $start=$job['progress_started_at']??$start;
        }
        $range=$this->range($values);
        if($range){$range['source']='measured_builds';}
        else {$range=$this->baseline($job['build_channel']??null,$finishing);$start=$finishing?($job['progress_started_at']??null):($job['build_started_at']??null);}
        if(!$range||!$start)return null;$elapsed=max(0,time()-strtotime($start.' UTC'));
        $range['overdue']=$elapsed>=$range['upper_seconds'];
        $range['lower_seconds']=max(0,$range['lower_seconds']-$elapsed);$range['upper_seconds']=max(0,$range['upper_seconds']-$elapsed);return $range;
    }
    public function view(array $job):array {
        $state=$job['state'];$phase=$job['progress_phase']??null;$updated=$job['progress_updated_at']??null;$active=in_array($state,['building','publishing'],true);
        $stale=$active&&$updated&&time()-strtotime($updated.' UTC')>150;
        $completed=null;$label='Waiting for a builder';$eta=null;$paused=false;
        if($state==='published'){$completed=6;$label='Published and verified';$phase='complete';}
        elseif($state==='waiting_builder'){$phase='queued';}
        elseif($state==='ready_to_publish'){$completed=4;$phase='prepared';$paused=true;$label=($job['failure_code']??null)?'Prepared build needs owner review':'Signed and verified — waiting for publication';}
        elseif($state==='publication_review'){$phase='review';$paused=true;$label='Publication needs owner review — no automatic retry';}
        elseif($state==='build_failed'){$phase='failed';$paused=true;$label='Build failed — retry or owner review required';}
        elseif($state==='cancelled'){$phase='cancelled';$paused=true;$label='Build cancelled';}
        elseif($active){$index=array_search($phase,self::PHASES,true);if($index!==false){$completed=$index;$label=self::LABELS[$phase];}else{$phase=$state==='publishing'?'publishing':null;$label=$state==='publishing'?'Publishing and verifying the APK':'Building — detailed stage reporting is not available from this runner';$completed=$state==='publishing'?4:null;}
            if(!$stale)$eta=$this->eta($job,$phase);
        }
        if($stale){$label='Waiting for a fresh builder update — progress may be delayed';$eta=null;}
        $channel=$job['build_channel']??null;if($channel===null&&($this->config->get('brand_builder',[])['channels']??[])===['testing'])$channel='testing';
        return ['phase'=>$phase,'label'=>$label,'completed_stages'=>$completed,'total_stages'=>6,'indeterminate'=>$completed===null,'paused'=>$paused,'stale'=>(bool)$stale,'updated_at'=>$updated,'attempt'=>(int)($job['build_attempt']??0),'eta'=>$eta,'typical'=>$this->baseline($channel,false)];
    }
}
