const assert=require('node:assert/strict');
module.exports=async function deviceAccessBrowser(browser,base,credentials,output,done){
  const context=await browser.newContext({viewport:{width:390,height:950}}),page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
  try{
    await page.goto(base);await page.getByLabel('Email address').fill(credentials.email);await page.getByLabel('Password',{exact:true}).fill(credentials.password);await page.getByRole('button',{name:'Sign in',exact:true}).click();await page.getByRole('heading',{name:'Dashboard',exact:true}).waitFor();
    const candidate=await page.evaluate(async()=>{const own=(await api('/api/workspace-context')).workspace.id;const rows=(await api('/api/devices')).items;return rows.find(d=>d.tenant_id!==own&&!d.reseller_detached&&d.policy!=='removed');});assert(candidate,'Fixture needs one reseller-assigned device');
    const before=await page.evaluate(async id=>({device:await api('/api/devices/'+id),commands:(await api('/api/devices/'+id+'/commands')).total}),candidate.id);
    await page.evaluate(id=>{location.hash='device/'+id;},candidate.id);await page.getByRole('heading',{name:'Device access',exact:true}).waitFor();
    const keep=page.getByRole('button',{name:'Remove from reseller — keep app working',exact:true}),block=page.getByRole('button',{name:'Remove device — block app',exact:true});await keep.waitFor();await block.waitFor();assert.equal(await keep.evaluate(n=>!!n.closest('details')),false);assert.equal(await block.evaluate(n=>!!n.closest('details')),false);
    for(const button of [keep,block]){assert((await button.boundingBox()).height>=44);page.once('dialog',d=>d.dismiss());await button.click();}
    const after=await page.evaluate(async id=>({device:await api('/api/devices/'+id),commands:(await api('/api/devices/'+id+'/commands')).total}),candidate.id);assert.equal(after.device.policy,before.device.policy);assert.equal(after.device.tenant_id,before.device.tenant_id);assert.equal(after.device.reseller_detached,before.device.reseller_detached);assert.equal(after.commands,before.commands);assert.deepEqual(errors,[]);
    await page.screenshot({path:output+'/visible-device-removal-mobile.png',fullPage:true});done('owner removal choices are visible on Overview, clearly distinguish keeping/blocking app access, and cancelled confirmations mutate nothing');
  }finally{await context.close();}
};
