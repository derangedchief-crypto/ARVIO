<?php
declare(strict_types=1);
if (PHP_SAPI !== 'cli') { http_response_code(404); exit; }
require dirname(__DIR__) . '/src/bootstrap.php';

use ControlCenter\Application;
use ControlCenter\Config;
use ControlCenter\Migrations;

$config = new Config(dirname(__DIR__));
try {
    switch ($argv[1] ?? 'help') {
        case 'preflight':
            if (PHP_VERSION_ID < 80300) throw new RuntimeException('PHP 8.3+ is required');
            foreach (['pdo_mysql','mbstring','openssl','fileinfo','curl','zlib'] as $extension) {
                if (!extension_loaded($extension)) throw new RuntimeException("Enable PHP extension: $extension");
            }
            if (!is_dir($config->root . '/storage') || !is_writable($config->root . '/storage')) throw new RuntimeException('Make storage/ writable by the website user');
            if (!$config->configured()) throw new RuntimeException('Fill in the private config.php beside public/ first');
            $app = new Application($config);
            $app->db->query('SELECT 1');
            $config->secret('application.key');
            $config->secret('setup.key');
            echo "PHP, required extensions, private storage and database connection are ready.\n";
            break;
        case 'migrate':
            $app = new Application($config);
            $applied = (new Migrations($app->db, $config))->apply();
            echo json_encode(['applied' => $applied], JSON_THROW_ON_ERROR) . "\n";
            break;
        case 'maintenance':
            $app = new Application($config);
            if (!(new Migrations($app->db, $config))->installed()) throw new RuntimeException('Complete owner setup first');
            echo json_encode($app->maintain(), JSON_THROW_ON_ERROR) . "\n";
            break;
        case 'recover-owner':
            if (stream_isatty(STDIN)) throw new RuntimeException('Read the new password from a private file via stdin, not interactive terminal echo or command arguments. See installation instructions.');
            $password = rtrim(stream_get_contents(STDIN, 256), "\r\n");
            $app = new Application($config);
            $app->auth->recoverOwner($password);
            unset($password);
            echo "Owner password reset. All owner sessions were revoked.\n";
            break;
        case 'owner-trial-catalog':
            if(count($argv)!==3)throw new RuntimeException('Usage: php cli/console.php owner-trial-catalog EXACT_PANEL_USERNAME');
            $app=new Application($config);
            $owner=$app->db->one("SELECT * FROM users WHERE role='owner' AND active=1 ORDER BY id LIMIT 1");
            $space=$app->db->one("SELECT id FROM tenants WHERE kind='owner' AND status='active' ORDER BY id LIMIT 1");
            if(!$owner||!$space)throw new RuntimeException('Active owner workspace required.');
            $link=$app->enrollment->linkPanelReseller($owner,$space['id'],['username'=>$argv[2]]);
            $app->trialPolicies->refresh($owner,['tenant_id'=>$space['id']]);
            $policy=$app->trialPolicies->get($owner,['tenant_id'=>$space['id']]);
            echo json_encode(['workspace'=>'owner','panel_username'=>$link['username'],'panel_id'=>$link['panel_id'],'plans'=>$policy['catalog']['plans']??[],'accounts_created'=>false,'offer_settings_changed'=>false],JSON_PRETTY_PRINT|JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR)."\n";
            break;
        case 'fresh-checkins':
            if(count($argv)!==3||$argv[2]!=='--all-workspaces')throw new RuntimeException('Usage: php cli/console.php fresh-checkins --all-workspaces');
            $app=new Application($config);
            $owner=$app->db->one("SELECT * FROM users WHERE role='owner' AND active=1 ORDER BY id LIMIT 1");
            if(!$owner)throw new RuntimeException('Active owner required.');
            $result=(new ControlCenter\DeviceListWindow($app->db,$app->auth))->reset($owner,['scope'=>'all_workspaces','confirm'=>true]);
            echo json_encode($result+['records_deleted'=>false,'credentials_revoked'=>false,'scope'=>'all_workspaces'],JSON_PRETTY_PRINT|JSON_THROW_ON_ERROR)."\n";
            break;
        case 'renewal-jobs':
            if(count($argv)!==2)throw new RuntimeException('Usage: php cli/console.php renewal-jobs');
            $app=new Application($config);$deadline=microtime(true)+45;$worked=0;
            do{$result=$app->renewalPayments->processOne();if(!$result['worked'])break;$worked++;}while(microtime(true)<$deadline);
            echo json_encode(['worked'=>$worked],JSON_THROW_ON_ERROR)."\n";
            break;
        case 'renewal-worker-check':
            if(count($argv)!==2)throw new RuntimeException('Usage: php cli/console.php renewal-worker-check');
            $app=new Application($config);echo json_encode(ControlCenter\RenewalWorkerConnection::probe($app,$config),JSON_PRETTY_PRINT|JSON_THROW_ON_ERROR)."\n";
            break;
        case 'renewal-invoice-status':
            if(count($argv)!==3)throw new RuntimeException('Usage: php cli/console.php renewal-invoice-status ORDER_ID');
            $app=new Application($config);echo json_encode(ControlCenter\RenewalInvoiceStatus::probe($app,$config,$argv[2]),JSON_PRETTY_PRINT|JSON_THROW_ON_ERROR)."\n";
            break;
        case 'renewal-catalogue-check':
            if(count($argv)!==2)throw new RuntimeException('Usage: php cli/console.php renewal-catalogue-check');
            $app=new Application($config);echo json_encode(ControlCenter\RenewalCatalogueConnection::probe($app,$config),JSON_PRETTY_PRINT|JSON_THROW_ON_ERROR)."\n";
            break;
        case 'queue-brand-testing':
            if(count($argv)!==6||!preg_match('/^[1-9][0-9]{0,4}$/D',$argv[5]))throw new RuntimeException('Usage: php cli/console.php queue-brand-testing BRAND_ID EXACT_STAGING_SHA VERSION CODE');
            $app=new Application($config);$artifacts=new ControlCenter\BrandArtifacts($app->db,new ControlCenter\Secrets($config),$config);
            echo json_encode((new ControlCenter\BrandTestingUpdates($app->db,$config,$artifacts,$app->auth))->queue($argv[2],$argv[3],$argv[4],(int)$argv[5]),JSON_THROW_ON_ERROR)."\n";
            break;
        case 'brand-export':
            if(count($argv)!==4)throw new RuntimeException('Usage: php cli/console.php brand-export BUILD_ID PRIVATE_OUTPUT_FILE');
            $app=new Application($config);$bundle=$app->appBrands->export($argv[2]);$mask=umask(0077);
            try{$handle=fopen($argv[3],'x');}finally{umask($mask);}
            if(!$handle)throw new RuntimeException('Choose a new private output file.');
            try{$body=json_encode($bundle,JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR);if(fwrite($handle,$body)!==strlen($body))throw new RuntimeException('Could not fully export build inputs.');}finally{fclose($handle);}
            echo "Pinned brand inputs exported. No APK built or published.\n";
            break;
        default:
            echo "Usage: php cli/console.php preflight|migrate|maintenance|recover-owner|brand-export|owner-trial-catalog|fresh-checkins|renewal-jobs|renewal-catalogue-check|queue-brand-testing\n";
    }
} catch (Throwable $error) {
    // Database exceptions can contain SQL/customer identifiers: do not echo them.
    fwrite(STDERR, $error instanceof PDOException ? "Database operation failed. Check private configuration and database permissions.\n" : $error->getMessage() . "\n");
    exit(1);
}
