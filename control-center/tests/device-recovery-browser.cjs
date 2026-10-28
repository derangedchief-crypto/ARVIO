const assert=require('node:assert/strict');
module.exports=async function recoveryBrowser(browser,base,credentials,output,done){
  const context=await browser.newContext({viewport:{width:390,height:950}}),page=await context.newPage();
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  const signIn=async(p,email,password)=>{await p.goto(base);await p.getByLabel('Email address').fill(email);await p.getByLabel('Password',{exact:true}).fill(password);await p.getByRole('button',{name:'Sign in',exact:true}).click();await p.getByRole('heading',{name:'Dashboard',exact:true}).waitFor();};
  try{
    await signIn(page,credentials.email,credentials.password);
    const before=await page.evaluate(async id=>({window:await api('/api/device-list-window'),all:(await api('/api/devices?visibility=all')).total,device:await api('/api/devices/'+id)}),credentials.device_id);
    await page.evaluate(()=>{location.hash='devices';});await page.getByRole('heading',{name:'Devices',exact:true}).waitFor();
    const reset=page.getByRole('button',{name:'Start fresh check-in list',exact:true});await reset.waitFor();
    page.once('dialog',d=>d.dismiss());await reset.click();
    assert.equal((await page.evaluate(()=>api('/api/device-list-window'))).generation,before.window.generation);
    done('cancelled fresh-list confirmation leaves global visibility unchanged');
    page.once('dialog',d=>d.accept());await reset.click();
    await page.waitForFunction(()=>document.querySelector('.fleet-count')?.textContent.startsWith('0 devices'));
    const after=await page.evaluate(async id=>({window:await api('/api/device-list-window'),all:(await api('/api/devices?visibility=all')).total,device:await api('/api/devices/'+id)}),credentials.device_id);
    assert.equal(after.window.generation,before.window.generation+1);assert.equal(after.all,before.all);
    for(const key of ['id','label','notes','policy','tenant_id','customer_id'])assert.equal(after.device[key],before.device[key]);
    done('owner reset empties visible list across workspaces without removing retained records or changing policy');
    const status=await page.evaluate(async token=>{const r=await fetch('/api/device/check-in',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify({schema_version:1,app_version:'fresh-visible-fixture',capabilities:[]})});return r.status;},credentials.synthetic_device_token);
    assert.equal(status,200);await page.getByRole('button',{name:'Refresh',exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('.fleet-count')?.textContent.startsWith('1 device'));
    assert.equal(await page.locator(`[data-device-id="${credentials.device_id}"]`).count(),1);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    await page.screenshot({path:output+'/fresh-device-checkins-mobile.png',fullPage:true});
    done('existing bearer checks in and the SAME device returns once with no mobile overflow');
    await page.setViewportSize({width:1280,height:900});await page.getByLabel('Device list view').selectOption('all');
    await page.waitForFunction(()=>!document.querySelector('.fleet-results')?.hasAttribute('aria-busy'));
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    done('retained-record view stays available and desktop layout fits');
    const resellerContext=await browser.newContext({viewport:{width:390,height:950}}),reseller=await resellerContext.newPage();
    try{
      await signIn(reseller,credentials.brand_email,credentials.brand_password);
      await reseller.evaluate(()=>{location.hash='devices';});await reseller.getByRole('heading',{name:'Devices',exact:true}).waitFor();
      assert.equal(await reseller.getByRole('button',{name:'Start fresh check-in list',exact:true}).count(),0);
      const scoped=await reseller.evaluate(()=>api('/api/devices?visibility=all'));assert(scoped.items.every(d=>d.tenant_id===credentials.brand_tenant_id));
      await assert.rejects(()=>reseller.evaluate(()=>api('/api/device-list-window',{scope:'all_workspaces',confirm:true})));
      done('reseller can inspect only its own retained records and cannot reset all workspaces');
    }finally{await resellerContext.close();}
    assert.deepEqual(errors,[]);
  }finally{await context.close();}
};
