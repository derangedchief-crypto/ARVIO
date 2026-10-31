// UI-only acceptance against isolated synthetic fixtures. Provider, pricing and
// gateway POSTs below are intercepted; never contact a merchant or panel worker.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'/tmp/opencode/control-center-browser-tools/node_modules/playwright');
let activeBrowser=null;
(async()=>{
 const base=process.env.CONTROL_PREVIEW_URL;assert(base&&['127.0.0.1','localhost'].includes(new URL(base).hostname));
 const credentials=JSON.parse(fs.readFileSync(process.argv[2],'utf8')),browser=await chromium.launch({headless:true});activeBrowser=browser;const report=[];
 const offered=Array.from({length:84},(_,i)=>({id:i+1,name:'Paid plan '+String(i+1).padStart(2,'0'),duration:1,period:'months',max_connections:2,credits:'2',funded:true,fingerprint:'a'.repeat(64)}));
 const trialOptions=offered.map(p=>({...p,name:'Trial option '+String(p.id).padStart(2,'0')}));
 for(const role of ['owner','reseller']){
  const context=await browser.newContext({viewport:{width:1366,height:900}}),page=await context.newPage(),errors=[],posts=[],reads=new Map();page.on('pageerror',e=>errors.push(e.message));
  let revision=3,approved=[1,84],gatewayRevision=7;
  const settings={tenant_id:'f'.repeat(32),policy:{enabled:true,testing_only:true},currency_exponents:{USD:2,CAD:2},catalogue_only:false,simulation_allowed:false,routing:{CA:{gateways:['square','stripe'],revision:2},US:{gateways:['stripe','square'],revision:2}},trial_conversion:{enabled:true,package_id:1,revision:3},prices:[1,2,3,4].map(id=>({id:String(id).repeat(32),package_id:id,country:'CA',currency:'CAD',currency_exponent:2,amount_minor:1000,enabled:true,revision:2})),connections:[{id:'a'.repeat(32),gateway:'square',business_name:'Example merchant',merchant_id:'square:example',mode:'live',checkout_enabled:true,availability_revision:7,api_key_saved:true,webhook_secret_saved:true,webhook_verified_at:'2026-10-31 01:00:00',webhook_url:base+'/api/renewals/webhooks/'+'a'.repeat(32)},{id:'b'.repeat(32),gateway:'stripe',business_name:'Example test merchant',merchant_id:'stripe:example',mode:'sandbox',checkout_enabled:true,availability_revision:1,api_key_saved:true,webhook_secret_saved:true,webhook_verified_at:null,webhook_url:base+'/api/renewals/webhooks/'+'b'.repeat(32)},{id:'c'.repeat(32),gateway:'rampex',business_name:'Saved merchant',merchant_id:'saved',mode:'live',checkout_enabled:false,availability_revision:1,api_key_saved:true,webhook_secret_saved:true,webhook_verified_at:null,webhook_url:base+'/api/renewals/webhooks/'+'c'.repeat(32)}]};
  const orders={simulation_allowed:false,items:Array.from({length:24},(_,i)=>({id:i.toString(16).padStart(32,'0'),payment_state:i===0?'paid':i===1?'review':'awaiting',fulfilment_state:i===0?'pending':'not_paid',checkout_superseded:0,currency:'CAD',amount_minor:1000,mode:'live'}))};
  await page.route('**/api/**',async route=>{
   const req=route.request(),url=new URL(req.url()),path=url.pathname,body=req.method()==='POST'?req.postDataJSON():null;
   const reply=(data,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(data)});
   if(req.method()==='POST'&&path!=='/api/login'){posts.push({path,body});if(path==='/api/renewals/prices'){settings.prices.push({...body,id:'d'.repeat(32),revision:1,amount_minor:Math.round(Number(body.amount)*100),currency_exponent:2});return reply(settings);}if(path==='/api/renewals/gateway-availability'){settings.connections[0].checkout_enabled=body.enabled;settings.connections[0].availability_revision=++gatewayRevision;return reply(settings);}throw Error('Unexpected mutation from UI: '+path);}
   if(path==='/api/renewals/settings')return reply(settings);
   if(path==='/api/renewals/catalog')return reply({packages:offered});
   if(path==='/api/renewals/orders')return reply(orders);
   if(path==='/api/trial-setup/model')return reply({global:true});
   if(path==='/api/trial-setup/packages')return reply({allowed_plan_ids:approved,revision,packages:trialOptions,catalog_fresh:true});
   if(path==='/api/trial-setup')return reply({status:'Trials are off',blockers:[],workspace_kind:role==='owner'?'owner':'reseller',admin_allowed:true,global_enabled:false,worker_configured:true,owner_unlimited:role==='owner',daily_max:1000,settings:{plan_id:1,daily_limit:20,enabled:false,default_revision:2,offer_revision:3},plans:trialOptions.slice(0,2)});
   // The responsive matrix revisits identical read-only fixture data hundreds
   // of times; cache those GET replies rather than weaken the real actor limit.
   if(req.method()==='GET'){let saved=reads.get(req.url());if(!saved){const response=await route.fetch();saved={status:response.status(),headers:response.headers(),body:await response.body()};assert.notEqual(saved.status,429,'Fixture actor rate limit must not be bypassed or mistaken for a valid page');reads.set(req.url(),saved);}return route.fulfill(saved);}
   return route.continue();
  });
  const go=async hash=>{await page.evaluate(async h=>{history.replaceState(null,'','#'+h);await window.route();},hash);};
  try{
   await page.goto(base);await page.getByLabel('Email address',{exact:true}).fill(role==='owner'?credentials.email:credentials.brand_email);await page.getByLabel('Password',{exact:true}).fill(role==='owner'?credentials.password:credentials.brand_password);await page.getByRole('button',{name:'Sign in',exact:true}).click();await page.getByRole('heading',{name:'Dashboard',exact:true}).waitFor();
   await go('renewals');assert(await page.getByRole('tab',{name:'Payments',exact:true}).evaluate(n=>n.getAttribute('aria-selected')==='true'));assert(await page.getByRole('heading',{name:'Payments need attention',exact:true}).isVisible());assert(await page.getByText(/Webhook not yet verified: Stripe/).isVisible());assert(await page.getByRole('button',{name:'Retry same renewal',exact:true}).isVisible());assert(await page.getByRole('button',{name:'Connect Square',exact:true}).count()===0);
   assert.equal(posts.length,0);report.push({role,test:'operational landing and genuine payment/setup failures stay visible',passed:true});
   await page.getByRole('tab',{name:'Plans & pricing',exact:true}).click();assert.equal(await page.getByRole('button',{name:'Edit pricing',exact:true}).count(),4);await page.getByLabel('Plan visibility',{exact:true}).selectOption('all');assert.equal(await page.getByRole('button',{name:'Edit pricing',exact:true}).count()+await page.getByRole('button',{name:'Set price',exact:true}).count(),10);await page.getByLabel('Search paid plans',{exact:true}).fill('84');assert.equal(await page.getByRole('button',{name:'Set price',exact:true}).count(),1);assert(await page.getByText('Paid plan 84',{exact:true}).isVisible());assert.equal(posts.length,0);
   report.push({role,test:'84 packages remain searchable with ten rows per page and no autosave',passed:true});
   await page.getByRole('button',{name:'Set price',exact:true}).click();await page.getByLabel('Payment country',{exact:true}).selectOption('CA');await page.getByLabel('Customer renewal price',{exact:true}).fill('12.50');assert.equal(await page.getByLabel('Currency',{exact:true}).inputValue(),'CAD');await page.locator('#modal-form').getByRole('button',{name:'Save',exact:true}).click();await page.locator('#modal').waitFor({state:'hidden'});assert.equal(posts.length,1);assert.equal(posts[0].body.package_id,84);assert.equal(posts[0].body.country,'CA');assert.equal(posts[0].body.expected_revision,0);assert.equal(posts[0].body.amount,'12.50');
   report.push({role,test:'focused country price form retains original API, currency and revision guards',passed:true});
   await page.getByRole('tab',{name:'Gateways',exact:true}).click();assert(await page.getByText('Manage Square',{exact:true}).isVisible());assert(!(await page.getByRole('button',{name:'Replace Square connection',exact:true}).isVisible()));await page.getByText('Manage Square',{exact:true}).click();await page.getByRole('button',{name:'Disable Square checkouts',exact:true}).click();await page.locator('#modal-form').getByRole('button',{name:'Save',exact:true}).click();await page.locator('#modal').waitFor({state:'hidden'});assert.equal(posts[1].path,'/api/renewals/gateway-availability');assert.equal(posts[1].body.enabled,false);assert.equal(posts[1].body.expected_revision,7);assert.equal(posts[1].body.connection_id,'a'.repeat(32));assert.equal(settings.connections[0].webhook_url,base+'/api/renewals/webhooks/'+'a'.repeat(32));
   report.push({role,test:'gateway management is on demand and toggles preserve connection identity',passed:true});
   await page.getByRole('tab',{name:'Settings',exact:true}).click();assert.equal(await page.getByRole('button',{name:'Rollout policy',exact:true,includeHidden:true}).count(),role==='owner'?1:0);assert.equal(await page.locator('#navigation a[href="#resellers"]').count(),role==='owner'?1:0);await page.getByRole('tab',{name:'Payments',exact:true}).focus();await page.keyboard.press('ArrowRight');assert.equal(await page.getByRole('tab',{name:'Plans & pricing',exact:true}).getAttribute('aria-selected'),'true');assert.equal(posts.length,2);
   report.push({role,test:'keyboard tabs and owner/reseller control boundaries are preserved',passed:true});
   if(role==='owner'){
    await go('trials/approvals');await page.getByLabel('Search global trial packages',{exact:true}).fill('84');const check=page.getByLabel('Globally approve Trial option 84',{exact:true});assert(await check.isChecked());await check.uncheck();await page.getByRole('link',{name:'Trial settings',exact:true}).click();await page.getByRole('link',{name:'Package approvals',exact:true}).click();await page.getByLabel('Search global trial packages',{exact:true}).fill('84');assert(!(await page.getByLabel('Globally approve Trial option 84',{exact:true}).isChecked()));assert.equal(posts.length,2);
    revision=4;await go('trials');await page.getByText('Global approvals changed elsewhere. Discard package edits and review the latest settings.',{exact:true}).waitFor();assert.equal(posts.length,2);await page.evaluate(()=>TrialWorkspace.clear());
    report.push({role,test:'paged approval selection survives navigation and stale conflicts are not hidden',passed:true});
   }
   const routes=['overview','customers','devices','trials','trials/contacts','library','library/settings','library/profiles','library/defaults','branding','renewals','addons','audit','more',...(role==='owner'?['resellers','enrollments','device/'+credentials.device_id]:[])];
   for(const theme of ['light','dark'])for(const width of [1366,768,360]){
    await page.setViewportSize({width,height:900});await page.evaluate(t=>applyTheme(t),theme);
    for(const route of routes){await go(route);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,role+' '+route+' '+width+' '+theme);}
    await go('renewals');const size=await page.evaluate(()=>({height:document.documentElement.scrollHeight,buttons:[...document.querySelectorAll('#main button')].filter(n=>n.getClientRects().length).length}));assert(size.height<4000,'Must stay below one quarter of the 16,287px baseline without shrinking text: '+JSON.stringify(size));assert(size.buttons<35,JSON.stringify(size));
    if(width===360){await page.getByRole('button',{name:'Open navigation',exact:true}).click();assert((await page.locator('#navigation a[href="#devices"]').boundingBox()).height>=44);await page.keyboard.press('Escape');assert.equal(await page.evaluate(()=>document.activeElement.id),'nav-toggle');}
    await page.screenshot({path:'/tmp/opencode/cleanup-'+role+'-'+theme+'-'+width+'.png',fullPage:true});
   }
   assert.equal(posts.length,2,'Opening/filtering views must not mutate settings or enqueue commands');assert.deepEqual(errors,[]);report.push({role,test:'all scoped pages fit desktop/tablet/360px in both themes without background mutations',passed:true});
  }finally{await context.close();}
 }
 await browser.close();fs.writeFileSync('/tmp/opencode/site-cleanup-acceptance.json',JSON.stringify(report,null,2));for(const row of report)console.log('PASS '+row.role+': '+row.test);
})().catch(async e=>{console.error(e);try{await activeBrowser?.close();}finally{process.exitCode=1;}});
