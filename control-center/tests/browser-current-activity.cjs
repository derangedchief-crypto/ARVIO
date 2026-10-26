const fs=require('node:fs');const assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'/tmp/opencode/control-center-browser-tools/node_modules/playwright');
(async()=>{
  const base=process.env.CONTROL_PREVIEW_URL;assert(['127.0.0.1','localhost'].includes(new URL(base).hostname));
  const credentials=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));const browser=await chromium.launch({headless:true});
  try{
    const page=await browser.newPage({viewport:{width:390,height:950}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.goto(base);await page.getByLabel('Email address').fill(credentials.email);await page.getByLabel('Password',{exact:true}).fill(credentials.password);await page.getByRole('button',{name:'Sign in',exact:true}).click();await page.getByRole('heading',{name:'Dashboard',exact:true}).waitFor();
    let mode='pending';const endpoint='/api/devices/'+credentials.device_id+'/sources';
    await page.route('**'+endpoint,async route=>{const response=await route.fetch(),data=await response.json();data.commands=Array.from({length:10},(_,i)=>({kind:'get_sources',status:'failed',result:{summary:'OBSOLETE LOG '+i}}));data.current_request=mode==='none'?null:{kind:'get_sources',status:mode,created_at:'2026-10-26 10:00:00',finished_at:mode==='succeeded'?'2026-10-26 10:01:00':null,result:mode==='succeeded'?{summary:'CURRENT confirmed result'}:null};data.active_count=mode==='pending'?3:0;await route.fulfill({response,json:data});});
    await page.evaluate(id=>{location.hash='device/'+id;},credentials.device_id);await page.getByRole('heading',{name:'Device access',exact:true}).waitFor();
    async function playlists(){await page.getByLabel('Device section',{exact:true}).selectOption('Playlists & guide');await page.getByRole('heading',{name:'Playlists',exact:true}).waitFor();}
    await playlists();const status=page.getByLabel('Current request status',{exact:true});await status.waitFor();assert.equal(await page.getByText(/OBSOLETE LOG/).count(),0);await status.getByText('3 active',{exact:true}).waitFor();
    await status.getByRole('button',{name:'View active requests',exact:true}).click();await page.getByRole('heading',{name:'Current requests',exact:true}).waitFor();
    mode='succeeded';await playlists();await status.getByText('CURRENT confirmed result',{exact:true}).waitFor();assert.equal(await page.getByText(/OBSOLETE LOG/).count(),0);
    await page.getByLabel('Device section',{exact:true}).selectOption('Summary');mode='none';await playlists();await page.waitForFunction(()=>!!document.querySelector('.current-request[hidden]'));assert.equal(await page.getByLabel('Current request status').count(),0);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);assert.deepEqual(errors,[]);
    await page.screenshot({path:__dirname+'/../test-results/current-playlists-mobile.png',fullPage:true});console.log('PASS current playlist status replaces old logs on mobile, links to active requests, and hides an empty status');
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
