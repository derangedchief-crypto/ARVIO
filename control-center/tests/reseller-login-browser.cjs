const {chromium}=require('/tmp/opencode/control-center-browser-tools/node_modules/playwright');
const fs=require('fs'),assert=require('assert/strict');
const credentials=JSON.parse(fs.readFileSync(process.argv[2]));
(async()=>{
  const browser=await chromium.launch();
  try{
    const page=await browser.newPage({viewport:{width:390,height:900}});
    await page.goto(process.env.CONTROL_PREVIEW_URL);
    await page.getByLabel('IPTV reseller username',{exact:true}).waitFor();
    assert.equal(await page.getByLabel('Email address',{exact:true}).count(),0);
    await page.route('**/api/login',async route=>{
      assert.deepEqual(route.request().postDataJSON(),{username:'ordinary.reseller',password:'fixture-password'});
      await route.fulfill({status:401,contentType:'application/json',body:JSON.stringify({error:'Username or password is incorrect.'})});
    });
    await page.getByLabel('IPTV reseller username',{exact:true}).fill('ordinary.reseller');
    await page.getByLabel('Password',{exact:true}).fill('fixture-password');
    await page.getByRole('button',{name:'Sign in',exact:true}).click();
    await page.getByText('Username or password is incorrect.',{exact:true}).waitFor();
    await page.getByRole('button',{name:'Owner / existing Hub login',exact:true}).click();
    assert.equal(await page.getByLabel('Password',{exact:true}).inputValue(),'');
    await page.getByRole('button',{name:'Reseller IPTV login',exact:true}).click();
    for(const width of [390,1440]){
      await page.setViewportSize({width,height:900});
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
      await page.screenshot({path:'/tmp/opencode/reseller-website-login-'+width+'.png',fullPage:true});
    }
    await page.unroute('**/api/login');
    await page.getByRole('button',{name:'Owner / existing Hub login',exact:true}).click();
    await page.getByLabel('Email address',{exact:true}).fill(credentials.email);
    await page.getByLabel('Password',{exact:true}).fill(credentials.password);
    await page.getByRole('button',{name:'Sign in',exact:true}).click();
    await page.locator('.dashboard-metrics').waitFor();
    console.log('PASS reseller username default, correct request, failed-login handling, cleared passwords, owner login and responsive layout');
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exit(1)});
