const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'/tmp/opencode/control-center-browser-tools/node_modules/playwright');
(async()=>{
  const base=process.env.CONTROL_PREVIEW_URL;assert(['127.0.0.1','localhost'].includes(new URL(base).hostname));const credentials=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));assert.equal(credentials.email,'owner@preview.test');const output=path.resolve(__dirname,'../test-results');fs.mkdirSync(output,{recursive:true});const browser=await chromium.launch({headless:true});const context=await browser.newContext({viewport:{width:390,height:1000}});const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
  try {
    await page.route(/\/api\/tenants$/,async route=>{const response=await route.fetch();const data=await response.json();data.items=data.items.filter(t=>t.kind!=='owner'&&t.id!==credentials.brand_tenant_id);await route.fulfill({response,json:data});});
    await page.goto(base);await page.getByLabel('Email address').fill(credentials.email);await page.getByLabel('Password',{exact:true}).fill(credentials.password);await page.getByRole('button',{name:'Sign in',exact:true}).click();await page.getByRole('heading',{name:'Dashboard',exact:true}).waitFor();
    const space=await page.evaluate(async()=>{const space=(await api('/api/workspace-context')).workspace;const a=await api('/api/app-brand/assets',{image:'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSuoAAAAASUVORK5CYII='});await api('/api/app-brand',{name:'TEST Owner TV',loading_asset:a.id,logo_asset:a.id,icon_asset:a.id});return space;});
    await require('./owner-workspace-browser.cjs')(page,credentials,space,output,name=>console.log('PASS '+name));assert.deepEqual(errors,[]);console.log('PASS focused owner browser suite has no uncaught JavaScript errors');
  } catch(error){await page.screenshot({path:path.join(output,'owner-browser-failure.png'),fullPage:true}).catch(()=>{});console.error(error);process.exitCode=1;}
  finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
