<?php
declare(strict_types=1);
namespace ControlCenter;

final class Application
{
    public readonly Database $db;
    public readonly Auth $auth;
    public readonly Management $management;
    public readonly Commands $commands;
    public readonly Enrollment $enrollment;
    public readonly SettingsPresets $presets;
    public readonly CategoryLayouts $layouts;
    public readonly Favourites $favourites;
    public readonly Sources $sources;
    public readonly Preferences $preferences;
    public readonly Support $support;
    public readonly DeviceProfiles $deviceProfiles;
    public readonly AppBrands $appBrands;
    public readonly TrialPolicies $trialPolicies;
    public readonly TrialContacts $trialContacts;
    public readonly TrialOffers $trialOffers;
    public readonly TrialSetup $trialSetup;
    public readonly TrialSelfService $trialSelfService;
    public readonly TrialPublicSessions $trialSessions;
    public readonly TrialSignupBroker $trialSignup;
    public readonly BrandBuilder $brandBuilder;
    public readonly BrandDownloads $brandDownloads;
    public readonly ModuleLicences $moduleLicences;
    public readonly RenewalSettings $renewalSettings;
    public readonly RenewalPayments $renewalPayments;
    public function __construct(public readonly Config $config)
    {
        $this->db = new Database($config);
        if ((new Migrations($this->db, $config))->installed()) $config->secret('application.key', false);
        $this->auth = new Auth($this->db, $config, new Secrets($config));
        $this->management = new Management($this->db, $config, $this->auth);
        $this->moduleLicences=new ModuleLicences($this->db,$this->management,$this->auth,$config);
        $this->commands = new Commands($this->db, $config, $this->auth, $this->management);
        $this->enrollment = new Enrollment($this->db,$config,$this->auth,$this->management,new Secrets($config));
        $this->presets=new SettingsPresets($this->db,$this->management,$this->auth,$config);
        $this->layouts=new CategoryLayouts($this->db,$this->management,$this->auth,$config);
        $this->favourites=new Favourites($this->db,$this->management,$this->commands,$this->auth,$config);
        $this->sources=new Sources($this->db,$this->management,$this->commands,$this->auth,$config);
        $this->preferences=new Preferences($this->db,$this->management,$this->commands,$this->auth);
        $this->support=new Support($this->db,$this->management,$this->commands,$this->auth,$config);
        $this->deviceProfiles=new DeviceProfiles($this->db,$this->management,$this->commands,$this->auth);
        $this->appBrands=new AppBrands($this->db,$this->management,$this->auth,new BrandArtifacts($this->db,new Secrets($config),$config),$config);
        $trialWorker=new TrialWorker($config);$this->trialPolicies=new TrialPolicies($this->db,$this->management,$this->auth,$trialWorker);
        $this->trialContacts=new TrialContacts($this->db,$this->management,$this->auth,new Secrets($config),$config,$trialWorker);
        $secrets=new Secrets($config);$artifacts=new BrandArtifacts($this->db,$secrets,$config);$this->trialOffers=new TrialOffers($this->db,$config,$this->management,$this->auth,$secrets,$this->trialPolicies,$artifacts);
        $this->trialSetup=new TrialSetup($this->db,$this->management,$this->auth,$this->trialPolicies,$this->trialOffers);
        $this->trialSelfService=new TrialSelfService($this->db,$this->management,$this->auth,$this->trialPolicies,$this->trialOffers);
        $this->brandBuilder=new BrandBuilder($this->db,$config,$this->appBrands,$artifacts,$secrets,$this->auth);
        $this->brandDownloads=new BrandDownloads($config,$artifacts);
        $this->trialSessions=new TrialPublicSessions($this->db,$this->trialOffers,$secrets,$this->auth);$this->trialSignup=new TrialSignupBroker($this->db,$config,$secrets,$this->auth,$this->trialOffers,$this->trialSessions,new TrialBudgets($this->db,$config),$trialWorker,$this->trialContacts);
        $gateway=new RampexGateway($config,$this->db,$secrets);$workerConfig=$config;$transport=null;
        if($gateway->mockAllowed()){
            $workerConfig=new Config($config->root,array_replace($config->values,['renewal_worker'=>['base_url'=>'https://simulation.invalid','shared_secret'=>$secrets->mac('renewal-simulator-bridge-v1')]]));
            $transport=(new RenewalSimulation($this->db,$workerConfig))->transport();
        }
        $renewalWorker=new RenewalWorker($workerConfig,$transport);
        $this->renewalSettings=new RenewalSettings($this->db,$config,$this->management,$this->auth,$secrets,$gateway,$renewalWorker);
        $this->renewalPayments=new RenewalPayments($this->db,$config,$this->auth,$this->management,$secrets,$artifacts,$this->renewalSettings,$gateway,$renewalWorker);
    }

    public function serve(string $method, string $path): never
    {
        $migrations = new Migrations($this->db, $this->config);
        if ($method === 'GET' && $path === '/api/status') {
            $installed = $migrations->installed();
            if (!$installed) $this->config->secret('setup.key');
            Http::json(['configured' => true, 'installed' => $installed, 'version' => trim(file_get_contents($this->config->root . '/VERSION'))]);
        }
        if ($method === 'POST' && $path === '/api/setup') {
            Http::sameOrigin($this->config);
            $input = Http::body();
            Input::keys($input, ['setup_key', 'name', 'email', 'password']);
            $key = $input['setup_key'] ?? '';
            if (!is_string($key) || strlen($key) !== 64) throw new Problem(403, 'Invalid setup key.');
            unset($input['setup_key']);
            $this->auth->bootstrap($key, $input);
            Http::json(['ok' => true], 201);
        }
        if (!$migrations->installed()) throw new Problem(503, 'Owner setup is required.');
        if($method==='GET'&&(preg_match('~^/renew/[a-f0-9]{64}$~D',$path)||preg_match('~^/renew/demo/[a-f0-9]{32}$~D',$path))){
            if(!$this->renewalSettings->installed())throw new Problem(404,'Renewal checkout unavailable.');
            header('Content-Type: text/html; charset=utf-8');$html=file_get_contents($this->config->root.'/public/renew.html');
            echo str_replace('?v=dev','?v='.rawurlencode(trim(file_get_contents($this->config->root.'/VERSION'))),$html);exit;
        }
        if($method==='POST'&&preg_match('~^/api/renewals/webhooks/([a-f0-9]{32})$~D',$path,$match)){
            if(!$this->renewalSettings->installed())throw new Problem(404,'Payment notification unavailable.');
            $raw=file_get_contents('php://input',false,null,0,65537);if(strlen($raw)>65536)throw new Problem(413,'Notification too large.');
            Http::json($this->renewalPayments->webhook($match[1],$raw,$_SERVER['HTTP_X_RAMPEX_SIGNATURE']??$_SERVER['HTTP_X_WEBHOOK_SIGNATURE']??''));
        }
        if($method==='POST'&&str_starts_with($path,'/api/public-renewals/')){
            Http::sameOrigin($this->config);$input=Http::body(8192);
            if($path==='/api/public-renewals/info'){Input::keys($input,['session_token']);Http::json($this->renewalPayments->info(Input::text($input['session_token']??null,'checkout token',64)));}
            if($path==='/api/public-renewals/status'){Input::keys($input,['session_token']);Http::json($this->renewalPayments->status(Input::text($input['session_token']??null,'checkout token',64)));}
            if($path==='/api/public-renewals/checkout')Http::json($this->renewalPayments->checkout($input,$_SERVER['REMOTE_ADDR']??''));
            throw new Problem(404,'Renewal route not found.');
        }
        if($method==='POST'&&$path==='/api/device/renewals/login/challenge')Http::json($this->renewalPayments->loginChallenge(Http::body(8192),$_SERVER['REMOTE_ADDR']??''));
        if($method==='POST'&&$path==='/api/device/renewals/login/open')Http::json($this->renewalPayments->loginOpen(Http::body(8192),$_SERVER['REMOTE_ADDR']??''));
        if($method==='POST'&&$path==='/api/device/renewals/challenge')Http::json($this->renewalPayments->challenge(Http::body(8192),$_SERVER['REMOTE_ADDR']??''));
        if($method==='POST'&&$path==='/api/device/renewals/open')Http::json($this->renewalPayments->open(Http::body(8192),$_SERVER['REMOTE_ADDR']??''));
        if($method==='POST'&&$path==='/api/device/renewals/status'){ $input=Http::body(8192);Input::keys($input,['session_token']);Http::json($this->renewalPayments->status(Input::text($input['session_token']??null,'checkout token',64))); }
        if($method==='GET'&&$path==='/api/brand-dispatch/queue'){
            header('Cache-Control: private, no-store');header('Vary: Authorization');
            Http::json($this->brandBuilder->poll($_GET,$_SERVER['HTTP_AUTHORIZATION']??null));
        }
        if($method==='GET'&&preg_match('~^/api/app-updates/([a-f0-9]{32})/(testing|production)$~D',$path,$match))Http::json($this->brandDownloads->metadata($match[1],$match[2]));
        if(in_array($method,['GET','HEAD'],true)&&preg_match('~^/downloads/apps/([a-f0-9]{32})/(Brand-[0-9.]+(?:-test)?\.apk)$~D',$path,$match))$this->brandDownloads->serve($match[1],$match[2],$method==='HEAD');
        if(str_starts_with($path,'/api/public-trials/')){
            $supported=(bool)$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['016_public_trial_signup.sql']);
            if(!$supported){if($method==='GET'&&$path==='/api/public-trials/offer')Http::json(['available'=>false]);throw new Problem(404,'Automatic trials unavailable.');}
            $ip=$_SERVER['REMOTE_ADDR']??'';$this->auth->limit('public-trial-api',(new Secrets($this->config))->mac("trial-ip-v1\n$ip"),120,60);
            if($method==='GET'&&$path==='/api/public-trials/offer'){Input::keys($_GET,['brand_id']);Http::json($this->trialOffers->publicInfo(Input::text($_GET['brand_id']??'','brand',32,false)));}
            if($method==='POST'&&$path==='/api/public-trials/session')Http::json($this->trialSessions->create(Http::body(8192),$ip),201);
            if($method==='POST'&&$path==='/api/public-trials/submit')Http::json($this->trialSignup->submit(Http::body(8192)));
            if($method==='POST'&&$path==='/api/public-trials/status')Http::json($this->trialSignup->status(Http::body(8192)));
            if($method==='POST'&&$path==='/api/public-trials/ack')Http::json($this->trialSignup->acknowledge(Http::body(8192)));
            throw new Problem(404,'Automatic trial route not found.');
        }
        if ($method === 'POST' && $path === '/api/login') {
            Http::sameOrigin($this->config);
            $result = $this->auth->login(Http::body(), $_SERVER['REMOTE_ADDR'] ?? 'unknown');
            Http::cookie($this->config, $result['token']);
            unset($result['token']);
            Http::json($result);
        }
        if($method==='POST'&&$path==='/api/device/enrollment/challenge') Http::json($this->enrollment->challenge(Http::body(),$_SERVER['REMOTE_ADDR']??''));
        if($method==='POST'&&$path==='/api/device/enrollment/register') { $result=$this->enrollment->register(Http::body(),$_SERVER['REMOTE_ADDR']??'');Http::json($result,$result['status']==='not_managed'?200:201); }
        if($method==='POST'&&$path==='/api/device/enrollment/poll') Http::json($this->enrollment->poll(Http::body(),$_SERVER['REMOTE_ADDR']??''));
        if($method==='POST'&&$path==='/api/device/enrollment/confirm') Http::json($this->enrollment->poll(Http::body(),$_SERVER['REMOTE_ADDR']??'',true));
        if ($method === 'POST' && $path === '/api/device/check-in') {
            $credential=Http::bearer();
            try { $result=$this->commands->checkIn($credential, Http::body(), $_SERVER['REMOTE_ADDR'] ?? '');$this->deviceProfiles->tickDevice($result['device_id']);if($this->deviceProfiles->pending($result['device_id']))$result['heartbeat_seconds']=min($result['heartbeat_seconds'],15);Http::json($result); }
            catch(Problem $error) {
                if($error->status===401) {
                    $notice=$this->commands->removalNotice($credential);
                    if($notice!==null) Http::json($notice);
                }
                throw $error;
            }
        }
        if ($method === 'POST' && preg_match('~^/api/device/commands/([a-f0-9]{32})/ack$~D', $path, $match)) {
            $credential=Http::bearer();$this->commands->deviceIdentity($credential);
            $result=$this->commands->acknowledge($credential, $match[1], Http::body(2097152));$this->deviceProfiles->afterCommand($result['device_id'],$match[1]);Http::json($result);
        }
        if($method==='POST'&&$path==='/api/device/favourites/capture') {$credential=Http::bearer();$this->commands->deviceIdentity($credential);Http::json($this->favourites->capture($credential,Http::body(2097152)));}
        if($method==='POST'&&$path==='/api/device/favourites/context')Http::json($this->favourites->context(Http::bearer(),Http::body()));
        if($method==='POST'&&$path==='/api/device/sources/capture')Http::json($this->sources->capture(Http::bearer(),Http::body(262144)));
        if($method==='POST'&&$path==='/api/device/sources/context')Http::json($this->sources->context(Http::bearer(),Http::body()));
        if($method==='POST'&&$path==='/api/device/preferences/capture')Http::json($this->preferences->capture(Http::bearer(),Http::body(65536)));
        if($method==='POST'&&$path==='/api/device/preferences/context')Http::json($this->preferences->context(Http::bearer(),Http::body()));
        if($method==='POST'&&$path==='/api/device/support/upload')Http::json($this->support->upload(Http::bearer(),Http::body(262144)));
        $token = $_COOKIE[Http::COOKIE] ?? '';
        if($method==='POST'&&$path==='/api/device/addons/policy'){Input::keys(Http::body(),[]);Http::json($this->moduleLicences->policy());}
        if($method==='POST'&&$path==='/api/device/addons/authorize'){Input::keys(Http::body(),[]);$d=$this->commands->deviceIdentity(Http::bearer());$this->auth->limit('module-device-check',$d['id'],30,60);Http::json($this->moduleLicences->decision($d));}
        if (!is_string($token)) throw new Problem(401, 'Please sign in.');
        $actor = $this->auth->user($token);
        $this->auth->limit('web-session', $actor['id'], 300, 60);
        if ($method !== 'GET') {
            Http::sameOrigin($this->config);
            $this->auth->verifyCsrf($token, $_SERVER['HTTP_X_CSRF_TOKEN'] ?? '');
        }
        if ($method === 'GET' && $path === '/api/me') Http::json(['user' => $actor, 'csrf' => $this->auth->csrf($token)]);
        if($method==='GET'&&$path==='/api/renewals/settings')Http::json($this->renewalSettings->status($actor,$_GET));
        if($method==='GET'&&$path==='/api/renewals/catalog')Http::json($this->renewalSettings->catalog($actor,$_GET));
        if($method==='POST'&&$path==='/api/renewals/policy')Http::json($this->renewalSettings->setPolicy($actor,Http::body()));
        if($method==='POST'&&$path==='/api/renewals/connection')Http::json($this->renewalSettings->saveConnection($actor,Http::body()));
        if($method==='POST'&&$path==='/api/renewals/prices')Http::json($this->renewalSettings->savePrice($actor,Http::body()));
        if($method==='POST'&&$path==='/api/renewals/plans')throw new Problem(422,'Custom renewal plans are not supported. Set money pricing for an offered panel package.');
        if($method==='GET'&&$path==='/api/renewals/orders')Http::json($this->renewalPayments->orders($actor,$_GET));
        if($method==='POST'&&preg_match('~^/api/renewals/orders/([a-f0-9]{32})/simulate$~D',$path,$m)){Input::keys(Http::body(),[]);Http::json($this->renewalPayments->simulate($actor,$m[1]));}
        if($method==='POST'&&preg_match('~^/api/renewals/orders/([a-f0-9]{32})/retry$~D',$path,$m)){Input::keys(Http::body(),[]);Http::json($this->renewalPayments->retry($actor,$m[1]));}
        if($method==='POST'&&$path==='/api/renewals/process-demo'){
            Auth::owner($actor);Input::keys(Http::body(),[]);if(!(new RampexGateway($this->config,$this->db,new Secrets($this->config)))->mockAllowed())throw new Problem(404,'Demo processor unavailable.');
            Http::json($this->renewalPayments->processOne());
        }
        if($method==='GET'&&$path==='/api/addons')Http::json($this->moduleLicences->status($actor,$_GET));
        if($method==='POST'&&$path==='/api/addons/issue')Http::json($this->moduleLicences->issue($actor,Http::body()),201);
        if($method==='POST'&&$path==='/api/addons/redeem')Http::json($this->moduleLicences->redeem($actor,Http::body()));
        if($method==='POST'&&$path==='/api/addons/policy')Http::json($this->moduleLicences->setPolicy($actor,Http::body()));
        if($method==='POST'&&preg_match('~^/api/addons/([a-f0-9]{32})$~D',$path,$m))Http::json($this->moduleLicences->change($actor,$m[1],Http::body()));
        if($method==='GET'&&$path==='/api/workspace-context'){Input::keys($_GET,[]);Http::json(['workspace'=>$this->management->workspace($actor)]);}
        if($method==='GET'&&$path==='/api/system/status') { Auth::owner($actor); Http::json(['pending_migrations'=>$migrations->pending()]); }
        if($method==='POST'&&$path==='/api/system/migrate') {
            Auth::owner($actor); Input::keys(Http::body(),[]);
            $applied=$migrations->apply(); $this->auth->audit($actor,'system.database_upgraded',null,null,['migrations'=>$applied]);
            Http::json(['applied'=>$applied]);
        }
        if($method==='GET'&&$path==='/api/enrollments/settings') Http::json($this->enrollment->settings($actor));
        if($method==='POST'&&$path==='/api/enrollments/settings') Http::json($this->enrollment->setEnabled($actor,Http::body()));
        if($method==='POST'&&$path==='/api/enrollments/bridge-configuration') { Input::keys(Http::body(),[]); Http::json($this->enrollment->bridgeConfiguration($actor)); }
        if($method==='GET'&&$path==='/api/enrollments') Http::json($this->enrollment->listing($actor,$_GET));
        if($method==='POST'&&preg_match('~^/api/enrollments/([a-f0-9]{32})/approve$~D',$path,$match)) Http::json($this->enrollment->approve($actor,$match[1],Http::body()));
        if($method==='POST'&&preg_match('~^/api/enrollments/([a-f0-9]{32})/reject$~D',$path,$match)) { Input::keys(Http::body(),[]); Http::json($this->enrollment->reject($actor,$match[1])); }
        if ($method === 'POST' && $path === '/api/logout') {
            Input::keys(Http::body(), []);
            $this->auth->logout($token); Http::cookie($this->config, ''); Http::json(['ok' => true]);
        }
        if ($method === 'POST' && $path === '/api/password') {
            $this->auth->changePassword($actor, Http::body());
            Http::cookie($this->config, ''); Http::json(['ok' => true]);
        }
        if ($method === 'GET' && $path === '/api/overview') Http::json($this->management->overview($actor,$_GET));
        if($method==='GET'&&$path==='/api/device-list-window'){Input::keys($_GET,[]);Http::json((new DeviceListWindow($this->db,$this->auth))->state());}
        if($method==='POST'&&$path==='/api/device-list-window')Http::json((new DeviceListWindow($this->db,$this->auth))->reset($actor,Http::body()));
        if($method==='POST'&&$path==='/api/device-recovery/settings')Http::json((new DeviceListWindow($this->db,$this->auth))->setRecovery($actor,Http::body()));
        if($method==='GET'&&$path==='/api/app-brand')Http::json($this->appBrands->get($actor,$_GET));
        if($method==='POST'&&$path==='/api/app-brand')Http::json($this->appBrands->save($actor,Http::body()));
        if($method==='POST'&&$path==='/api/app-brand/assets')Http::json($this->appBrands->upload($actor,Http::body(1500000)),201);
        if($method==='GET'&&preg_match('~^/api/app-brand/assets/([a-f0-9]{32})$~D',$path,$m)){$image=$this->appBrands->image($actor,$m[1]);header('Content-Type: '.$image['mime']);echo $image['image'];exit;}
        if($method==='POST'&&$path==='/api/app-brand/builds')Http::json($this->appBrands->requestBuild($actor,Http::body()),201);
        if($method==='GET'&&preg_match('~^/api/app-brand/builds/([a-f0-9]{32})/inputs$~D',$path,$m)){$file=$this->appBrands->downloadInputs($actor,$m[1]);header('Content-Type: application/json; charset=utf-8');header('Content-Disposition: attachment; filename="'.$file['filename'].'"');echo $file['body'];exit;}
        if($method==='POST'&&preg_match('~^/api/app-brand/builds/([a-f0-9]{32})/cancel$~D',$path,$m))Http::json($this->appBrands->cancelBuild($actor,$m[1],Http::body()));
        if($method==='GET'&&$path==='/api/trial-policy')Http::json($this->trialPolicies->get($actor,$_GET));
        if($method==='GET'&&$path==='/api/trial-setup/model'){Input::keys($_GET,[]);Http::json(['global'=>$this->trialOffers->globalReady()]);}
        if($method==='GET'&&$path==='/api/trial-setup/packages'){Input::keys($_GET,[]);Http::json($this->trialSelfService->packages($actor));}
        if($method==='POST'&&$path==='/api/trial-setup/packages')Http::json($this->trialSelfService->savePackages($actor,Http::body()));
        if($method==='GET'&&$path==='/api/trial-setup')Http::json($this->trialOffers->globalReady()?$this->trialSelfService->status($actor,$_GET):$this->trialSetup->status($actor,$_GET));
        if($method==='POST'&&$path==='/api/trial-setup/admin'){if($this->trialOffers->globalReady())throw new Problem(410,'Per-reseller trial permissions were removed. Use Global approved trial packages.');Http::json($this->trialSetup->saveAdmin($actor,Http::body()));}
        if($method==='POST'&&$path==='/api/trial-setup/app')Http::json($this->trialOffers->globalReady()?$this->trialSelfService->saveApp($actor,Http::body()):$this->trialSetup->saveApp($actor,Http::body()));
        if($method==='GET'&&$path==='/api/trials/settings'){Input::keys($_GET,[]);Http::json($this->trialOffers->globalSettings($actor));}
        if($method==='POST'&&$path==='/api/trials/settings')Http::json($this->trialOffers->setGlobal($actor,Http::body()));
        if($method==='POST'&&$path==='/api/trial-policy/catalog')Http::json($this->trialPolicies->refresh($actor,Http::body()));
        if($method==='POST'&&$path==='/api/trial-policy/grants')Http::json($this->trialPolicies->grant($actor,Http::body()));
        if($method==='POST'&&$path==='/api/trial-policy/default'){if($this->trialOffers->globalReady())throw new Problem(410,'Use App trial settings to save your package, daily limit and app switch together.');Http::json($this->trialPolicies->setDefault($actor,Http::body()));}
        if($method==='GET'&&$path==='/api/trial-offer-publication')Http::json($this->trialOffers->managerState($actor,$_GET));
        if($method==='POST'&&$path==='/api/trial-offer-publication'){if($this->trialOffers->globalReady())throw new Problem(410,'Separate offer publication is no longer needed. Use App trial settings.');Http::json($this->trialOffers->publish($actor,Http::body()));}
        if($method==='GET'&&$path==='/api/trial-contacts')Http::json($this->trialContacts->listing($actor,$_GET));
        if($method==='GET'&&preg_match('~^/api/trial-contacts/([a-f0-9]{32})$~D',$path,$m))Http::json($this->trialContacts->detail($actor,$m[1]));
        if($method==='POST'&&preg_match('~^/api/trial-contacts/([a-f0-9]{32})/redact$~D',$path,$m))Http::json($this->trialContacts->redact($actor,$m[1],Http::body()));
        if($method==='POST'&&preg_match('~^/api/trial-contacts/([a-f0-9]{32})/refresh$~D',$path,$m))Http::json($this->trialContacts->refresh($actor,$m[1],Http::body()));
        if($method==='GET'&&$path==='/api/device-profiles')Http::json($this->deviceProfiles->listing($actor,$_GET));
        if($method==='POST'&&$path==='/api/device-profiles')Http::json($this->deviceProfiles->save($actor,null,Http::body()),201);
        if($method==='GET'&&preg_match('~^/api/device-profiles/([a-f0-9]{32})$~D',$path,$m))Http::json($this->deviceProfiles->get($actor,$m[1]));
        if($method==='POST'&&preg_match('~^/api/device-profiles/([a-f0-9]{32})$~D',$path,$m))Http::json($this->deviceProfiles->save($actor,$m[1],Http::body()));
        if($method==='GET'&&$path==='/api/device-defaults'){Input::keys($_GET,['tenant_id']);Http::json($this->deviceProfiles->policy($actor,$this->management->workspace($actor,$_GET['tenant_id']??null)['id']));}
        if($method==='POST'&&$path==='/api/device-defaults')Http::json($this->deviceProfiles->setPolicy($actor,Http::body()));
        if($method==='GET'&&$path==='/api/device-setups')Http::json($this->deviceProfiles->jobs($actor,$_GET));
        if($method==='GET'&&preg_match('~^/api/device-setups/([a-f0-9]{32})$~D',$path,$m))Http::json($this->deviceProfiles->detail($actor,$m[1]));
        if($method==='POST'&&preg_match('~^/api/device-setups/([a-f0-9]{32})$~D',$path,$m))Http::json($this->deviceProfiles->control($actor,$m[1],Http::body()));
        if($method==='POST'&&preg_match('~^/api/devices/([a-f0-9]{32})/setup$~D',$path,$m))Http::json($this->deviceProfiles->applyExisting($actor,$m[1],Http::body(),$_SERVER['HTTP_IDEMPOTENCY_KEY']??''),201);
        if($method==='GET'&&preg_match('~^/api/devices/([a-f0-9]{32})/commands/([a-f0-9]{32})$~D',$path,$m))Http::json($this->commands->get($actor,$m[1],$m[2]));
        if($method==='GET'&&preg_match('~^/api/devices/([a-f0-9]{32})/support$~D',$path,$m))Http::json($this->support->listing($actor,$m[1]));
        if($method==='GET'&&preg_match('~^/api/devices/([a-f0-9]{32})/support/([a-f0-9]{32})/download$~D',$path,$m)){$file=$this->support->download($actor,$m[1],$m[2]);header('Content-Type: '.$file['mime']);header('Content-Disposition: attachment; filename="'.$file['filename'].'"');echo $file['body'];exit;}
        if($method==='GET'&&$path==='/api/preference-profiles')Http::json($this->preferences->profiles($actor,$_GET));
        if($method==='POST'&&$path==='/api/preference-profiles')Http::json($this->preferences->save($actor,null,Http::body()),201);
        if($method==='POST'&&preg_match('~^/api/preference-profiles/([a-f0-9]{32})$~D',$path,$m))Http::json($this->preferences->save($actor,$m[1],Http::body()));
        if($method==='GET'&&preg_match('~^/api/devices/([a-f0-9]{32})/preferences$~D',$path,$m))Http::json($this->preferences->latest($actor,$m[1]));
        if($method==='POST'&&preg_match('~^/api/devices/([a-f0-9]{32})/preferences/preview$~D',$path,$m))Http::json($this->preferences->preview($actor,$m[1],Http::body()),201);
        if($method==='GET'&&preg_match('~^/api/devices/([a-f0-9]{32})/sources$~D',$path,$m))Http::json($this->sources->listing($actor,$m[1]));
        if($method==='GET'&&preg_match('~^/api/devices/([a-f0-9]{32})/sources/([a-f0-9]{32})$~D',$path,$m))Http::json($this->sources->view($actor,$m[1],$m[2]));
        if($method==='POST'&&preg_match('~^/api/devices/([a-f0-9]{32})/sources/preview$~D',$path,$m))Http::json($this->sources->preview($actor,$m[1],Http::body(262144)),201);
        if($method==='GET'&&$path==='/api/settings-presets')Http::json($this->presets->listing($actor,$_GET));
        if($method==='POST'&&$path==='/api/settings-presets')Http::json($this->presets->create($actor,Http::body()),201);
        if($method==='POST'&&preg_match('~^/api/settings-presets/([a-f0-9]{32})$~D',$path,$match))Http::json($this->presets->change($actor,$match[1],Http::body()));
        if($method==='POST'&&preg_match('~^/api/settings-presets/([a-f0-9]{32})/archive$~D',$path,$match))Http::json($this->presets->change($actor,$match[1],Http::body(),true));
        if($method==='GET'&&$path==='/api/category-layout-templates')Http::json($this->layouts->listing($actor,$_GET));
        if($method==='POST'&&$path==='/api/category-layout-templates')Http::json($this->layouts->create($actor,Http::body(2097152)),201);
        if($method==='GET'&&preg_match('~^/api/category-layout-templates/([a-f0-9]{32})$~D',$path,$match))Http::json($this->layouts->get($actor,$match[1]));
        if($method==='POST'&&preg_match('~^/api/category-layout-templates/([a-f0-9]{32})$~D',$path,$match))Http::json($this->layouts->change($actor,$match[1],Http::body(2097152)));
        if($method==='POST'&&preg_match('~^/api/category-layout-templates/([a-f0-9]{32})/archive$~D',$path,$match))Http::json($this->layouts->change($actor,$match[1],Http::body(),true));
        if ($method === 'GET' && $path === '/api/tenants') Http::json($this->management->tenantListing($actor, $_GET));
        if($method==='GET'&&preg_match('~^/api/tenants/([a-f0-9]{32})$~D',$path,$m))Http::json($this->management->tenant($actor,$m[1]));
        if($method==='GET'&&$path==='/api/panel-resellers')Http::json($this->enrollment->panelLinks($actor));
        if($method==='POST'&&preg_match('~^/api/tenants/([a-f0-9]{32})/panel-link$~D',$path,$match))Http::json($this->enrollment->linkPanelReseller($actor,$match[1],Http::body()));
        if ($method === 'POST' && $path === '/api/resellers') Http::json($this->management->createReseller($actor, Http::body()), 201);
        if ($method === 'POST' && preg_match('~^/api/tenants/([a-f0-9]{32})$~D', $path, $match)) Http::json($this->management->updateTenant($actor, $match[1], Http::body()));
        if ($method === 'GET' && $path === '/api/customers') Http::json($this->management->listing($actor, 'customers', $_GET));
        if ($method === 'POST' && $path === '/api/customers') Http::json($this->management->createCustomer($actor, Http::body()), 201);
        if ($method === 'GET' && preg_match('~^/api/customers/([a-f0-9]{32})$~D', $path, $match)) Http::json($this->management->customer($actor, $match[1]));
        if ($method === 'POST' && preg_match('~^/api/customers/([a-f0-9]{32})$~D', $path, $match)) Http::json($this->management->updateCustomer($actor, $match[1], Http::body()));
        if ($method === 'GET' && $path === '/api/devices') Http::json($this->management->listing($actor, 'devices', $_GET));
        if ($method === 'POST' && $path === '/api/devices') Http::json($this->management->createDevice($actor, Http::body()), 201);
        if ($method === 'GET' && preg_match('~^/api/devices/([a-f0-9]{32})$~D', $path, $match)) Http::json($this->management->device($actor, $match[1]));
        if ($method === 'POST' && preg_match('~^/api/devices/([a-f0-9]{32})$~D', $path, $match)) Http::json($this->management->updateDevice($actor, $match[1], Http::body()));
        if($method==='POST'&&preg_match('~^/api/devices/([a-f0-9]{32})/detach-reseller$~D',$path,$match)) {Input::keys(Http::body(),[]);Http::json($this->management->detachReseller($actor,$match[1]));}
        if($method==='GET'&&preg_match('~^/api/devices/([a-f0-9]{32})/category-layout$~D',$path,$match))Http::json($this->layouts->device($actor,$match[1],$_GET));
        if($method==='POST'&&preg_match('~^/api/devices/([a-f0-9]{32})/category-layout$~D',$path,$match))Http::json($this->commands->enqueue($actor,$match[1],['kind'=>'apply_category_layout','payload'=>Http::body(524288)],$_SERVER['HTTP_IDEMPOTENCY_KEY']??''),201);
        if($method==='GET'&&preg_match('~^/api/devices/([a-f0-9]{32})/favourites$~D',$path,$match))Http::json($this->favourites->listing($actor,$match[1],$_GET));
        if($method==='GET'&&preg_match('~^/api/devices/([a-f0-9]{32})/favourites/backups/([a-f0-9]{32})$~D',$path,$match))Http::json($this->favourites->view($actor,$match[1],$match[2]));
        if($method==='POST'&&preg_match('~^/api/devices/([a-f0-9]{32})/favourites/backups/([a-f0-9]{32})/protect$~D',$path,$match))Http::json($this->favourites->protect($actor,$match[1],$match[2],Http::body()));
        if($method==='GET'&&preg_match('~^/api/devices/([a-f0-9]{32})/favourites/backups/([a-f0-9]{32})/preview$~D',$path,$match))Http::json($this->favourites->preview($actor,$match[1],$match[2]));
        if($method==='POST'&&preg_match('~^/api/devices/([a-f0-9]{32})/favourites/backups/([a-f0-9]{32})/preview$~D',$path,$match)){Input::keys(Http::body(),[]);Http::json($this->favourites->queuePreview($actor,$match[1],$match[2],$_SERVER['HTTP_IDEMPOTENCY_KEY']??''),201);}
        if($method==='POST'&&preg_match('~^/api/devices/([a-f0-9]{32})/favourites/backups/([a-f0-9]{32})/restore$~D',$path,$match))Http::json($this->favourites->queueRestore($actor,$match[1],$match[2],Http::body(),$_SERVER['HTTP_IDEMPOTENCY_KEY']??''),201);
        if (preg_match('~^/api/devices/([a-f0-9]{32})/commands$~D', $path, $match)) {
            if ($method === 'GET') Http::json($this->commands->listing($actor, $match[1], $_GET));
            if ($method === 'POST') Http::json($this->commands->enqueue($actor, $match[1], Http::body(), $_SERVER['HTTP_IDEMPOTENCY_KEY'] ?? ''), 201);
        }
        if($method==='POST'&&preg_match('~^/api/devices/([a-f0-9]{32})/commands/([a-f0-9]{32})/cancel$~D',$path,$match)){Input::keys(Http::body(),[]);Http::json($this->commands->cancel($actor,$match[1],$match[2]));}
        if ($method === 'GET' && $path === '/api/audit') Http::json($this->management->audit($actor, $_GET));
        throw new Problem(404, 'Record not found.');
    }

    public function maintain(): array
    {
        $this->commands->expire();
        $sessions = $this->db->query('DELETE FROM sessions WHERE expires_at <= UTC_TIMESTAMP()')->rowCount();
        $this->db->query('DELETE FROM rate_limits WHERE expires_at <= UTC_TIMESTAMP()');
        $days = $this->config->number('retention_days', 90, 7, 365);
        $commands = $this->db->query("DELETE FROM commands WHERE status IN ('succeeded','failed','expired','cancelled') AND finished_at < DATE_SUB(UTC_TIMESTAMP(), INTERVAL $days DAY) LIMIT 1000")->rowCount();
        $events = $this->db->query("DELETE FROM audit_events WHERE created_at < DATE_SUB(UTC_TIMESTAMP(), INTERVAL $days DAY) LIMIT 1000")->rowCount();
        if((new Migrations($this->db,$this->config))->pending()===[]) {$this->enrollment->maintain();$this->presets->maintain();$this->layouts->maintain();$this->favourites->maintain();$this->sources->maintain();$this->preferences->maintain();$this->support->maintain();$this->deviceProfiles->maintain();$this->appBrands->maintain();$this->trialContacts->maintain();$this->trialSetup->maintain();$this->trialSignup->maintain();$this->brandBuilder->maintain();}
        return ['expired_sessions_removed' => $sessions, 'old_commands_removed' => $commands, 'old_audit_events_removed' => $events];
    }
}
