import importlib.util
import io
import json
import os
import re
import subprocess
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from branding.test_github_builder import dispatcher


class ProductionTests(unittest.TestCase):
    def test_website_cutover_retains_private_config_and_trial_limits_while_selecting_normal_channel(self):
        root=Path(__file__).resolve().parent.parent
        script=(root/'control-center/deploy/enable-production-brands.sh').read_text()
        code=re.search(r"<<'PHP'\n(.*?)\nPHP",script,re.S)[1]
        original="""<?php return ['environment'=>'production','origin'=>'https://control.fecxbrfzku.net',
        'database'=>['password'=>'SYNTHETIC-PRIVATE'],'custom'=>['keep'=>'yes'],
        'trial_pilot_brand_id'=>'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','trial_channel'=>'testing','trial_reseller_daily_limit'=>1,'trial_public_signup_enabled'=>true,
        'trial_worker'=>['shared_secret'=>'SYNTHETIC-UNCHANGED-KEY'],
        'brand_builder'=>['enabled'=>true,'publication_enabled'=>true,'channels'=>['testing'],'sources'=>['2.0.115'=>'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'],'automatic_testing_publication'=>true],
        'brand_dispatch'=>['enabled'=>true,'token_sha256'=>'SYNTHETIC-UNCHANGED-HASH'],
        'brand_downloads'=>['enabled'=>true,'x_accel'=>false]];"""
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as directory:
            site=Path(directory);(site/'src').mkdir();(site/'src/bootstrap.php').write_text('''<?php namespace ControlCenter;
            class Config { function __construct(public $root,public $values){} }
            class Application { public $db;public $brandBuilder;function __construct($c){$this->db=new class {function one($s){return null;}};$this->brandBuilder=new class {function fanout($i){if($i['channel']!=='production'||$i['version_code']!==432)throw new \\RuntimeException('invalid');return ['complete'=>true,'queued'=>1];}};} }
            ''');(site/'config.php').write_text(original);runner=site/'run.php';runner.write_text(code);after=site/'after.php'
            php=['bash',str(root/'control-center/dev/php.sh')]
            result=subprocess.run(php+[str(runner),str(site),str(after),'c'*40],cwd=root,check=True,capture_output=True,text=True)
            self.assertNotIn('SYNTHETIC',result.stdout+result.stderr);self.assertEqual(original,(site/'config.php').read_text())
            check="""$a=require $argv[1];$b=require $argv[2];
            if($b['brand_builder']['sources']!==['2.0.116'=>str_repeat('c',40)]||$b['brand_builder']['channels']!==['production']||$b['brand_builder']['automatic_production_publication']!==true||$b['brand_builder']['automatic_testing_publication']!==false||$b['brand_builder']['reconciliation_sources']!==[str_repeat('b',40)]||$b['brand_release']['default_channel']!=='production'||$b['trial_channel']!=='production'||$b['brand_main_updates']!==['testing_enabled'=>false,'production_enabled'=>true])exit(1);
            unset($b['brand_release'],$b['brand_main_updates'],$b['brand_builder']['automatic_production_publication'],$b['brand_builder']['reconciliation_sources']);$b['brand_builder']=$a['brand_builder'];$b['trial_channel']='testing';exit($a===$b?0:1);
            """
            subprocess.run(php+['-r',check,str(site/'config.php'),str(after)],cwd=root,check=True,capture_output=True)
    def test_private_configuration_preserves_both_tokens_and_selects_normal_immutable_source(self):
        spec=importlib.util.spec_from_file_location('production',Path(__file__).parent/'deploy/configure-production.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'worker.env';path.write_text('GH_TOKEN=SYNTHETIC-PRIVATE\nBRAND_QUEUE_TOKEN='+'a'*64+'\nBRAND_WORKFLOW_REF=reseller-control-center\nCUSTOM=keep\n');path.chmod(0o600);module.configure(path,'b'*40);first=path.read_text();module.configure(path,'b'*40);self.assertEqual(first,path.read_text());self.assertIn('GH_TOKEN=SYNTHETIC-PRIVATE\n',first);self.assertIn('BRAND_QUEUE_TOKEN='+'a'*64+'\n',first);self.assertIn('BRAND_AUTO_PUBLISH_PRODUCTION=true\n',first);self.assertIn('BRAND_AUTO_PUBLISH_TESTING=false\n',first);self.assertIn('BRAND_WORKFLOW_REF=brand-source-2.0.116-'+'b'*40+'\n',first);self.assertIn('CUSTOM=keep\n',first)
    def test_production_poll_is_forbidden_without_its_distinct_worker_opt_in(self):
        with tempfile.TemporaryDirectory() as directory:
            state=Path(directory);state.chmod(0o700)
            env={'BRAND_SOURCE_SHA':'a'*40,'BRAND_WORKFLOW_REF':'main','BRAND_DISPATCH_STATE':directory,'BRAND_AUTO_PUBLISH_PRODUCTION':'false'}
            with patch.dict(os.environ,env),patch.object(dispatcher,'queue') as queue,patch.object(dispatcher,'gh') as gh:
                with self.assertRaises(ValueError):dispatcher.dispatch(SimpleNamespace(mode='build',channel='production',build_id='',poll=True))
                queue.assert_not_called();gh.assert_not_called()
    def test_authenticated_production_hint_keeps_clean_channel_and_exact_tag(self):
        class Response(io.BytesIO):status=200
        sha='c'*40;tag='brand-source-2.0.116-'+sha;value={'state':'queued','build_id':'d'*32,'channel':'production','source_sha':sha,'workflow_ref':tag,'mode':'publish'}
        env={'BRAND_QUEUE_URL':'https://site.example/api/brand-dispatch/queue','BRAND_QUEUE_TOKEN':'e'*64}
        opener=SimpleNamespace(open=lambda *a,**kw:Response(json.dumps(value).encode()))
        with patch.dict(os.environ,env),patch.object(dispatcher.urllib.request,'build_opener',return_value=opener):self.assertEqual({'build_id':'d'*32,'source_sha':sha,'workflow_ref':tag},dispatcher.queue('a'*40,'production','publish'))


if __name__=='__main__':unittest.main()
