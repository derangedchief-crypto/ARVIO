#!/usr/bin/env python3
"""Private pilot setup syntax/fail-closed checks; never contacts a live worker."""
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
SCRIPT=ROOT/'deploy/enable-owner-trial-pilot.sh'
PHP=['bash',str(ROOT/'dev/php.sh')]
CODE=SCRIPT.read_text().split("<<'PHP'\n",1)[1].split('\nPHP\n',1)[0]


class PilotScriptTests(unittest.TestCase):
    def test_shell_and_embedded_php_parse_and_private_input_errors_do_not_touch_config(self):
        subprocess.run(['bash','-n',str(SCRIPT)],check=True)
        subprocess.run(PHP+['-l','/dev/stdin'],input=CODE.encode(),check=True,capture_output=True)
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as directory:
            site=Path(directory);(site/'src').mkdir();(site/'src/bootstrap.php').write_text('<?php require '+repr(str(ROOT/'src/bootstrap.php'))+';')
            original="<?php return ['origin'=>'https://control.fecxbrfzku.net','database'=>['password'=>'SYNTHETIC-DO-NOT-PRINT']];"
            (site/'config.php').write_text(original);staged=site/'staged.php';staged.write_text(original)
            for plan,fp in [('0','a'*64),('11;exit','a'*64),('11','not-a-fingerprint')]:
                result=subprocess.run(PHP+['/dev/stdin',str(site),str(staged),'TEST-owner',plan,fp],input=CODE.encode(),capture_output=True)
                self.assertEqual(1,result.returncode);self.assertIn(b'Invalid website or reviewed plan identity',result.stderr)
                self.assertNotIn(b'SYNTHETIC-DO-NOT-PRINT',result.stdout+result.stderr)
                self.assertEqual(original,(site/'config.php').read_text());self.assertEqual(original,staged.read_text())

    def test_config_transformation_preserves_original_private_values_and_limits_only_testing_pilot(self):
        change=re.search(r'\$updated=array_replace\(\$values,.*?;',CODE)[0]
        check="""$values=['origin'=>'https://control.fecxbrfzku.net','trial_public_signup_enabled'=>false,'trial_channel'=>'production','database'=>['password'=>'SYNTHETIC'],'trial_worker'=>['shared_secret'=>str_repeat('a',64)],'custom'=>['keep'=>true]];$brand=['id'=>str_repeat('b',32)];"""+change+"""
        if($updated['trial_pilot_brand_id']!==str_repeat('b',32)||$updated['trial_public_signup_enabled']!==true||$updated['trial_channel']!=='testing'||$updated['trial_reseller_daily_limit']!==1)exit(1);
        unset($updated['trial_pilot_brand_id'],$updated['trial_reseller_daily_limit']);$updated['trial_public_signup_enabled']=false;$updated['trial_channel']='production';exit($values===$updated?0:1);"""
        subprocess.run(PHP+['-r',check],check=True,capture_output=True)


if __name__=='__main__':unittest.main()
