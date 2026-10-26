const assert=require('node:assert/strict');
module.exports=async function addonsBrowser(browser,base,credentials,output,done){
  const owner=await browser.newContext({viewport:{width:390,height:950}}),seller=await browser.newContext({viewport:{width:390,height:950}});const page=await owner.newPage(),reseller=await seller.newPage(),errors=[];for(const p of [page,reseller])p.on('pageerror',e=>errors.push(e.message));
  async function login(p,email,password){await p.goto(base);await p.getByLabel('Email address').fill(email);await p.getByLabel('Password',{exact:true}).fill(password);await p.getByRole('button',{name:'Sign in',exact:true}).click();await p.getByRole('heading',{name:'Dashboard',exact:true}).waitFor();}
  try {
    await login(page,credentials.email,credentials.password);const email='addon-browser-'+Date.now()+'@preview.test',password=credentials.password;
    const id=await page.evaluate(async ({email,password})=>{const t=await api('/api/resellers',{company:'TEST monthly addon',name:'TEST monthly addon',email,password});rememberWorkspace(t);state.toolWorkspace=t.id;location.hash='addons';return t.id;},{email,password});
    await page.getByRole('heading',{name:'Extra Playlists',exact:true}).waitFor();await page.getByText('Enforcement off',{exact:true}).waitFor();
    await page.getByRole('button',{name:'Generate key',exact:true}).click();const key=await page.locator('#generated-addon-key').inputValue();assert(/^EH-PL-[A-F0-9]{64}$/.test(key));
    const data=await page.evaluate(id=>api('/api/addons?tenant_id='+id),id);assert(!JSON.stringify(data).includes(key));assert(!('key_hash' in data.licence));
    await page.getByRole('button',{name:'Done — key saved',exact:true}).click();assert.equal(await page.locator('#generated-addon-key').count(),0);
    await login(reseller,email,password);await reseller.evaluate(()=>{location.hash='addons';});await reseller.getByRole('button',{name:'Enter licence key',exact:true}).click();await reseller.getByLabel('Licence key',{exact:true}).fill(key);await reseller.getByRole('button',{name:'Activate monthly add-on',exact:true}).click();await reseller.locator('#modal').waitFor({state:'hidden'});await reseller.locator('.badge.active').waitFor();
    assert.equal(await reseller.getByRole('button',{name:'Generate key',exact:true}).count(),0);assert.equal(await reseller.getByRole('button',{name:'Renew one month',exact:true}).count(),0);assert.equal(await reseller.getByRole('heading',{name:'Rollout',exact:true}).count(),0);
    await page.evaluate(()=>route());page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'Suspend',exact:true}).click();await page.locator('.badge.suspended').waitFor();await reseller.reload();await reseller.locator('.badge.suspended').waitFor();
    page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'Reactivate',exact:true}).click();await page.locator('.badge.active').waitFor();page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'Revoke',exact:true}).click();await page.locator('.badge.revoked').waitFor();await reseller.reload();await reseller.locator('.badge.revoked').waitFor();
    assert.equal(await reseller.getByRole('button',{name:'Enter licence key',exact:true}).count(),0);
    for(const width of [320,390,1440]){await page.setViewportSize({width,height:950});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);}
    await page.setViewportSize({width:390,height:950});await page.screenshot({path:output+'/addons-owner-mobile.png',fullPage:true});await reseller.screenshot({path:output+'/addons-reseller-mobile.png',fullPage:true});assert.deepEqual(errors,[]);
    done('monthly add-on screens issue keys once, redeem across the workspace, restrict reseller controls and show suspension/revocation without mobile overflow');
  } finally {await owner.close();await seller.close();}
};
