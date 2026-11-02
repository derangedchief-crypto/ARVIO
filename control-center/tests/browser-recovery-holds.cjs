const assert=require('node:assert/strict');
const fs=require('node:fs');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'/tmp/opencode/control-center-browser-tools/node_modules/playwright');
(async()=>{
 const base=process.env.CONTROL_PREVIEW_URL;
 if(!['127.0.0.1','localhost'].includes(new URL(base).hostname))throw new Error('Local fixtures only');
 const c=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
 const browser=await chromium.launch({headless:true});
 try{
  const context=await browser.newContext({viewport:{width:390,height:950}}),page=await context.newPage(),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.goto(base);await page.getByLabel('Email address').fill(c.brand_email);await page.getByLabel('Password',{exact:true}).fill(c.brand_password);await page.getByRole('button',{name:'Sign in',exact:true}).click();await page.getByRole('heading',{name:'Dashboard',exact:true}).waitFor();
  const id='a'.repeat(32),device='b'.repeat(32),revision='c'.repeat(64),writes=[];
  assert.equal(await page.evaluate(async id=>(await fetch('/api/recovery-holds/'+id,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:'allow',confirm:true})})).status,id),403);
  assert.equal(await page.evaluate(async id=>(await fetch('/api/recovery-holds/'+id)).status,id),404);
  console.log('PASS recovery hold POST requires CSRF and foreign/unknown request is not exposed');
  await page.route('**/api/recovery-holds?*',route=>route.fulfill({json:{page:1,pages:1,total:1,items:[{id,installation_id:'d'.repeat(32),label:'Reset TV',model:'Fixture TV',line_username:'fixture-line',app_version:'2.0.131-beta',app_package:'com.arvio.tv.beta',recovery_state:'unidentified',last_request_at:'2026-11-02 00:00:00'}],restricted_devices:[{id:device,label:'Restricted TV',model:'Fixture',policy:'blocked'}]}}));
  await page.route('**/api/recovery-holds/'+id,route=>{
   if(route.request().method()==='POST'){writes.push(route.request().postDataJSON());return route.fulfill({json:{state:'awaiting_signed_retry',device_id:device}});}
   return route.fulfill({json:{request_id:id,expected_revision:revision,devices:[{id:device,label:'Existing TV',model:'Fixture TV',policy:'active',hint_matches:false}]}});
  });
  await page.getByRole('button',{name:'Open navigation',exact:true}).click();
  await page.locator('#navigation').getByRole('link',{name:'Recovery holds',exact:true}).click();await page.getByRole('heading',{name:'Recovery holds',exact:true}).waitFor();
  await page.getByRole('link',{name:/Restricted TV/}).waitFor();
  await page.getByRole('button',{name:'Allow',exact:true}).click();await page.getByRole('heading',{name:'Allow reconnection to an existing device',exact:true}).waitFor();
  assert.equal(writes.length,0);await page.locator('#modal-form').getByRole('button',{name:'Cancel',exact:true}).click();assert.equal(writes.length,0);
  await page.getByRole('button',{name:'Allow',exact:true}).click();
  await page.getByLabel('Existing device to reconnect — no new record will be created',{exact:true}).selectOption(device);
  await page.getByLabel('Confirm this is the same physical device',{exact:true}).selectOption('yes');
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
  await page.locator('#modal-form').getByRole('button',{name:'Allow reconnection',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('#toast')?.textContent.includes('Allowed.'));
  assert.deepEqual(writes,[{action:'allow',device_id:device,expected_revision:revision,confirm:true}]);
  console.log('PASS reseller selects existing device with revision; allow creates no UI provision request; mobile fits');
  page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'Reject',exact:true}).click();
  await page.waitForResponse(r=>r.url().endsWith('/api/recovery-holds/'+id)&&r.request().method()==='POST');
  assert.deepEqual(writes[1],{action:'reject',expected_revision:revision,confirm:true});assert.deepEqual(errors,[]);
  console.log('PASS reseller reject is explicit and scoped; restricted devices remain visible');
  await context.close();
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
