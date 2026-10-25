import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
import zipfile

ROOT=Path(__file__).resolve().parent.parent
PHP=['bash',str(ROOT/'control-center/dev/php.sh')]


class DeploymentTests(unittest.TestCase):
    def test_trial_bundle_is_additive_and_contains_no_worker_config_or_keys(self):
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as directory:
            target=Path(directory)/'trial.zip'
            subprocess.run(['python3',str(ROOT/'reseller-auth-worker/package.py'),str(target)],check=True,capture_output=True)
            with zipfile.ZipFile(target) as archive:
                self.assertEqual({'install.sh','branded_trials.py','README.md','LICENSE'},set(archive.namelist()))
                subprocess.run(['bash','-n'],input=archive.read('install.sh'),check=True)
                script=archive.read('install.sh').decode()
                self.assertIn('c5a93d1a4d98f214a4427e8ac0ca7dbdcb03fb5d59632ad4212740a188a7e8d1',script)
                self.assertIn('BRANDED_TRIAL_PROVISION_ENABLED=false',script)
                self.assertIn('trap rollback EXIT',script)
                self.assertIn('9ec850d9f790c898421e2da0b15eaf0ee36c5dd492128df71272ce969934ab9e',script)
                self.assertNotIn('systemctl restart brand-dispatch',script)

    def test_private_website_import_preserves_every_other_config_value(self):
        script=ROOT/'control-center/deploy/connect-trial-worker.sh'
        subprocess.run(['bash','-n',str(script)],check=True)
        code=re.search(r"-r '\n(.*?)\n' \"\$config\"",script.read_text(),re.S)[1]
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as directory:
            root=Path(directory);before=root/'before.php';after=root/'after.php';connection=root/'connection.json'
            before.write_text("<?php return ['origin'=>'https://control.fecxbrfzku.net','database'=>['password'=>'SYNTHETIC'], 'application'=>['keep'=>'ORIGINAL'], 'trial_public_signup_enabled'=>false, 'brand_builder'=>['channels'=>['testing']]];")
            original=before.read_bytes();connection.write_text(json.dumps({'base_url':'https://resellerworker.extremeiptv.net','shared_secret':'a'*64}))
            subprocess.run(PHP+['-r',code,str(before),str(after),str(connection)],check=True,capture_output=True)
            check="$a=require $argv[1];$b=require $argv[2];if($b['trial_worker']!==['base_url'=>'https://resellerworker.extremeiptv.net','shared_secret'=>str_repeat('a',64)])exit(1);unset($b['trial_worker']);exit($a===$b?0:1);"
            subprocess.run(PHP+['-r',check,str(before),str(after)],check=True,capture_output=True)
            self.assertEqual(original,before.read_bytes())
            # A different existing secret is never silently rotated.
            before.write_text("<?php return ['origin'=>'https://control.fecxbrfzku.net','trial_worker'=>['shared_secret'=>'"+'b'*64+"']];")
            after.unlink()
            result=subprocess.run(PHP+['-r',code,str(before),str(after),str(connection)],capture_output=True)
            self.assertNotEqual(0,result.returncode);self.assertFalse(after.exists())


if __name__=='__main__':unittest.main()
