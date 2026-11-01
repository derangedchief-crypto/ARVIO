const assert=require('node:assert/strict');
const fs=require('node:fs');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'/tmp/opencode/control-center-browser-tools/node_modules/playwright');
(async()=>{
 const base=process.env.CONTROL_PREVIEW_URL;
 if(!['127.0.0.1','localhost'].includes(new URL(base).hostname))throw new Error('Local fixtures only');
 const credentials=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
 const browser=await chromium.launch({headless:true});
 try{
  const context=await browser.newContext({viewport:{width:390,height:950}}),page=await context.newPage(),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  const signIn=async(p,email,password)=>{await p.goto(base);await p.getByLabel('Email address').fill(email);await p.getByLabel('Password',{exact:true}).fill(password);await p.getByRole('button',{name:'Sign in',exact:true}).click();await p.getByRole('heading',{name:'Dashboard',exact:true}).waitFor();};
  await signIn(page,credentials.email,credentials.password);
  const id='a'.repeat(32),device='b'.repeat(32),revision='c'.repeat(64);let writes=[];
  // Real authentication/CSRF boundary before installing UI-only synthetic routes.
  const csrf=await page.evaluate(async id=>(await fetch('/api/enrollments/'+id+'/recovery',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({confirm:true,expected_revision:'c'.repeat(64)})})).status,id);
  assert.equal(csrf,403);console.log('PASS recovery POST requires real owner-session CSRF protection');
  const other=await browser.newContext(),reseller=await other.newPage();
  await signIn(reseller,credentials.brand_email,credentials.brand_password);
  const denied=await reseller.evaluate(async id=>(await fetch('/api/enrollments/'+id+'/recovery')).status,id);
  assert.equal(denied,403);await other.close();console.log('PASS reseller cannot preview owner-only recovery');
  await page.route('**/api/enrollments?*',route=>route.fulfill({json:{page:1,pages:1,total:1,items:[{id,installation_id:'d'.repeat(32),label:'Synthetic legacy reset',model:'Fixture TV',os_version:'Android fixture',app_version:'2.0.126-beta',app_package:'com.arvio.tv.beta',account_hint:'Synthetic account',status:'pending',recovery_state:'unidentified',last_request_at:'2026-11-01 00:00:00'}]}}));
  await page.route('**/api/enrollments/'+id+'/recovery',route=>{
   if(route.request().method()==='POST'){
    writes.push(route.request().postDataJSON());return route.fulfill({json:{request_id:id,device_id:device,state:'awaiting_signed_retry'}});
   }
   return route.fulfill({json:{request_id:id,device_id:device,installation_id:'d'.repeat(32),model:'Fixture TV',previous_label:'Retained TV record',previous_last_seen_at:'2026-10-29 12:00:00',expected_revision:revision}});
  });
  await page.evaluate(()=>{location.hash='enrollments';});await page.getByRole('heading',{name:'Website settings',exact:true}).waitFor();
  await page.getByRole('button',{name:'Review recovery',exact:true}).click();
  await page.getByRole('heading',{name:'Review previous device recovery',exact:true}).waitFor();
  assert.equal(writes.length,0);assert.equal(await page.getByRole('button',{name:'Approve / assign',exact:true}).count(),0);
  await page.locator('#modal-form').getByRole('button',{name:'Cancel',exact:true}).click();assert.equal(writes.length,0);
  console.log('PASS legacy recovery is explicit review, not ordinary assignment; opening/cancelling changes nothing');
  await page.getByRole('button',{name:'Review recovery',exact:true}).click();
  await page.getByLabel('Authorize this installation to recover the previous device',{exact:true}).selectOption('authorize');
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
  await page.locator('#modal-form').getByRole('button',{name:'Authorize recovery',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('#toast')?.textContent.includes('Association approved'));
  assert.deepEqual(writes,[{expected_revision:revision,confirm:true}]);assert.deepEqual(errors,[]);
  console.log('PASS explicit owner confirmation posts only reviewed revision and confirmation; mobile layout fits');
  await context.close();
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
