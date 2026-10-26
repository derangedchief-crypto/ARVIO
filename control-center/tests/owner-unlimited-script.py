"""Execute the helper's PHP against synthetic dependencies, never a live host."""
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]


class OwnerUnlimitedHelperTests(unittest.TestCase):
    def test_private_policy_change_preserves_original_keys_limits_and_opt_out(self):
        script=(ROOT/'control-center/deploy/enable-owner-unlimited-trials.sh').read_text()
        code=re.search(r"<<'PHP'\n(.*?)\nPHP",script,re.S)[1]
        bootstrap='''<?php namespace ControlCenter;
        class Config {function __construct(public $root,public $values){}}
        class Application {public $db;public $trialOffers;function __construct($c){$this->db=new class {
          function transaction($f){return $f();}function all($sql,$args){return [];}
          function one($sql,$args=[]){if(str_contains($sql,'FROM app_brands'))return ['id'=>'46ae4715dbfe4e57dfc7498cfbd6fbfb','tenant_id'=>'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','kind'=>'owner','panel_id'=>'252'];if(str_contains($sql,'FROM users'))return ['id'=>'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb','role'=>'owner','tenant_id'=>null];return ['id'=>'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'];}
        };$this->trialOffers=new class($c->values['synthetic_off']) {function __construct(private $off){}function managerState($a,$i){return ['owner_unlimited'=>true,'can_publish'=>true,'publication'=>['enabled'=>!$this->off,'revision'=>4],'active'=>!$this->off];}function publish($a,$i){if($this->off||$i['expected_revision']!==4||isset($i['daily_limit'])||!$i['enabled']||!$i['confirmed_public_mode'])throw new \\RuntimeException('Incorrect publication');}};}}
        '''
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as directory:
            site=Path(directory);(site/'src').mkdir();(site/'src/bootstrap.php').write_text(bootstrap);runner=site/'run.php';runner.write_text(code)
            for off in [False,True]:
                original="""<?php return ['origin'=>'https://control.fecxbrfzku.net','trial_channel'=>'production','trial_public_signup_enabled'=>true,'trial_pilot_brand_id'=>'46ae4715dbfe4e57dfc7498cfbd6fbfb','trial_reseller_daily_limit'=>1,'database'=>['password'=>'SYNTHETIC-UNCHANGED'],'trial_worker'=>['shared_secret'=>'SYNTHETIC-KEY'],'synthetic_off'=>"""+('true' if off else 'false')+'];'
                (site/'config.php').write_text(original);out=site/'after.php';php=['bash',str(ROOT/'control-center/dev/php.sh')]
                result=subprocess.run(php+[str(runner),str(site),str(out)],check=True,capture_output=True,text=True,cwd=ROOT)
                self.assertNotIn('SYNTHETIC',result.stdout+result.stderr);self.assertIn('"accounts_created": false',result.stdout);self.assertIn('"offer_active": '+('false' if off else 'true'),result.stdout);self.assertEqual(original,(site/'config.php').read_text())
                check="$a=require $argv[1];$b=require $argv[2];if($b['trial_owner_unlimited_brand_id']!=='46ae4715dbfe4e57dfc7498cfbd6fbfb')exit(1);unset($b['trial_owner_unlimited_brand_id']);exit($a===$b?0:1);"
                subprocess.run(php+['-r',check,str(site/'config.php'),str(out)],check=True,capture_output=True,cwd=ROOT)


if __name__=='__main__':unittest.main()
