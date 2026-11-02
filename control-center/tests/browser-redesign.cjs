const assert=require('node:assert/strict');
const fs=require('node:fs');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'/tmp/opencode/control-center-browser-tools/node_modules/playwright');
(async()=>{
  const base=process.env.CONTROL_PREVIEW_URL;
  if(!['127.0.0.1','localhost'].includes(new URL(base).hostname))throw new Error('Local fixtures only');
  const credentials=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
  const output='/tmp/opencode/control-redesign';fs.mkdirSync(output,{recursive:true});
  const browser=await chromium.launch({headless:true});
  try{
    for(const role of ['reseller','owner']){
      const context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage(),errors=[];
      page.on('pageerror',e=>errors.push(e.message));
      await page.goto(base);await page.getByLabel('Email address').waitFor();
      assert.equal(await page.evaluate(()=>document.documentElement.dataset.theme),'dark');
      if(role==='reseller')await page.screenshot({path:output+'/sign-in.png',fullPage:true,animations:'disabled'});
      await page.getByLabel('Email address').fill(role==='owner'?credentials.email:credentials.brand_email);
      await page.getByLabel('Password',{exact:true}).fill(role==='owner'?credentials.password:credentials.brand_password);
      await page.getByRole('button',{name:'Sign in',exact:true}).click();
      async function ready(){await page.waitForFunction(()=>document.querySelector('#main')?.getAttribute('aria-busy')!== 'true'&&document.querySelector('#main .page-heading'));assert.equal(await page.getByRole('heading',{name:'Unable to load this view',exact:true}).count(),0);}
      await page.getByRole('heading',{name:'Dashboard',exact:true}).waitFor().catch(async error=>{await page.screenshot({path:output+'/failure.png',fullPage:true});console.error(await page.locator('body').innerText(),errors);throw error;});await ready();
      if(role==='reseller'){
        await page.evaluate(async()=>{const customer=await api('/api/customers',{name:'TEST · Morgan household',external_reference:'DEMO-001',notes:'Illustrative customer. Prefers evening support.'});await api('/api/devices',{customer_id:customer.id,label:'TEST · Living room TV',notes:'Local UI fixture; not enrolled.'});await route();});await ready();
      }
      assert.equal(await page.locator('#navigation a[href="#resellers"]').count(),role==='owner'?1:0);
      assert.equal(await page.locator('.sidebar').isVisible(),false);
      assert.equal(await page.locator('#top-nav').isVisible(),true);
      assert.equal(await page.locator('.metric-card').count(),4);
      assert.equal(await page.locator('.metric-strip a').count(),4);
      assert.equal(await page.locator('.analytics-grid svg').count(),2);
      await page.getByText('What this chart measures',{exact:true}).click();
      assert.equal(await page.locator('.chart-explanation tbody tr').count(),7);
      await page.getByText('What this chart measures',{exact:true}).click();
      await page.screenshot({path:output+'/'+role+'-dashboard.png',fullPage:true,animations:'disabled'});
      await page.setViewportSize({width:390,height:950});await page.screenshot({path:output+'/'+role+'-dashboard-mobile.png',fullPage:true,animations:'disabled'});await page.setViewportSize({width:1440,height:1000});
      const routes=['customers','devices','recovery-holds','renewals/payments','renewals/plans','renewals/gateways','renewals/settings','trials/contacts','trials','branding','library','addons','audit','more',...(role==='owner'?['resellers','enrollments']:[])];
      for(const route of routes){
        const epoch=await page.evaluate(hash=>{const previous=state.epoch;location.hash=hash;return previous;},route);await page.waitForFunction(previous=>state.epoch>previous&&document.querySelector('#main')?.getAttribute('aria-busy')!=='true',epoch);await ready();
        for(const width of [1440,390,320]){
          await page.setViewportSize({width,height:950});
          assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,role+' '+route+' overflows at '+width);
          if(width===390&&role==='reseller')await page.screenshot({path:output+'/'+route.replaceAll('/','-')+'-mobile.png',fullPage:true,animations:'disabled'});
        }
      }
      await page.setViewportSize({width:390,height:950});
      await page.locator('#bottom-nav a[href="#customers"]').click();await ready();
      const open=page.getByRole('button',{name:'Open customer',exact:true}).first();
      assert.equal(await open.count(),1,'Customer fixture must be present');{
        await open.click();await page.locator('.customer-profile').waitFor();await ready();
        assert.equal(await page.getByLabel('Device list view').inputValue(),'all');
        assert.equal(await page.locator('#bottom-nav a[href="#customers"]').getAttribute('aria-current'),'page');
        await page.getByRole('button',{name:'Edit customer',exact:true}).click();
        await page.getByRole('heading',{name:'Edit customer',exact:true}).waitFor();
        await page.locator('#modal-form').getByRole('button',{name:'Cancel',exact:true}).click();
        await page.screenshot({path:output+'/'+role+'-customer-mobile.png',fullPage:true,animations:'disabled'});
      }
      await page.locator('#bottom-nav a[href="#more"]').click();await ready();
      for(const href of ['devices','renewals','trials/contacts','branding','library'])assert.equal(await page.locator('#main a[href="#'+href+'"]').count(),1);
      await page.getByRole('button',{name:'Open navigation',exact:true}).click();assert.equal(await page.locator('#workspace').evaluate(e=>e.inert),true);await page.keyboard.press('Escape');assert.equal(await page.locator('#workspace').evaluate(e=>e.inert),false);
      await page.getByRole('button',{name:'Switch to light theme',exact:true}).click();await page.reload();await page.getByRole('heading',{name:'Workspace',exact:true}).waitFor();assert.equal(await page.evaluate(()=>document.documentElement.dataset.theme),'light');
      assert.deepEqual(errors,[]);await context.close();console.log('PASS '+role+' routes, customer workspace, theme persistence, drawer focus and 320–1440px layouts');
    }
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
