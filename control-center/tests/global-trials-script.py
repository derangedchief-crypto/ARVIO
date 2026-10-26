from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]


class GlobalTrialUpgradeTests(unittest.TestCase):
    def test_seed_only_owner_approved_terms_once_and_never_change_private_config(self):
        text=(ROOT/'control-center/deploy/upgrade-global-trials.sh').read_text();code=re.search(r"<<'PHP'\n(.*?)\nPHP",text,re.S)[1]
        bootstrap='''<?php namespace ControlCenter;
        class Config {public $values;function __construct($root){$this->values=require $root.'/config.php';}}
        class Application {public $db;public $auth;function __construct($c){$this->auth=new class {function audit(...$a){}};$this->db=new class($c->values['already_enabled']) {
          function __construct(private $enabled){}function transaction($f){return $f();}
          function one($sql){if(str_contains($sql,'FROM trial_global_packages'))return ['enabled'=>$this->enabled,'allowed_plans'=>'{"71":"existing-global-terms"}'];if(str_contains($sql,'FROM users'))return ['id'=>'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb','role'=>'owner','tenant_id'=>null];if($this->enabled)throw new \\RuntimeException('Must not reseed an existing global policy');return ['allowed_plans'=>'{"17":"owner-approved-terms"}'];}
          function query($sql,$args){if($this->enabled||json_decode($args[0],true)!==[17=>'owner-approved-terms'])throw new \\RuntimeException('Wrong seed');}
        };}}
        '''
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as directory:
            site=Path(directory);(site/'src').mkdir();(site/'src/bootstrap.php').write_text(bootstrap);(site/'run.php').write_text(code)
            for enabled in [False,True]:
                config="<?php return ['already_enabled'=>"+('true' if enabled else 'false')+",'private_secret'=>'SYNTHETIC-UNCHANGED','trial_owner_unlimited_brand_id'=>'UNCHANGED','trial_pilot_brand_id'=>'UNCHANGED'];";(site/'config.php').write_text(config)
                r=subprocess.run(['bash',str(ROOT/'control-center/dev/php.sh'),str(site/'run.php'),str(site)],cwd=ROOT,check=True,capture_output=True,text=True)
                self.assertNotIn('SYNTHETIC',r.stdout+r.stderr);self.assertIn('"accounts_created": false',r.stdout);self.assertIn('"new_apps_auto_enabled": false',r.stdout);self.assertEqual(config,(site/'config.php').read_text())


if __name__=='__main__':unittest.main()
