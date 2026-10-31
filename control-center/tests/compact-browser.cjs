const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {execFileSync}=require('node:child_process');
module.exports=async function compactBrowser(browser,base,credentials,output,done){
  const current=await browser.newContext({viewport:{width:1440,height:1000}}),page=await current.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
  async function login(p,baseline=false){await p.goto(base);await p.getByLabel('Email address').fill(credentials.email);await p.getByLabel('Password',{exact:true}).fill(credentials.password);await p.getByRole('button',{name:'Sign in',exact:true}).click();await p.getByRole('heading',{name:baseline?'Operations overview':'Dashboard',exact:true}).waitFor();}
  async function metrics(p){return p.evaluate(()=>({heading:parseFloat(getComputedStyle(document.querySelector('.page-heading h1')).fontSize),panelPadding:parseFloat(getComputedStyle(document.querySelector('.panel')).paddingLeft),sidebar:parseFloat(getComputedStyle(document.querySelector('.sidebar')).width)}));}
  let before=null;
  try{
    if(process.env.UI_BASELINE_REF){
      assert.match(process.env.UI_BASELINE_REF,/^[a-f0-9]{7,40}$/);const old=await browser.newContext({viewport:{width:1440,height:1000}});const p=await old.newPage();
      await old.route('**/assets/compact.css*',r=>r.abort());await old.route(/\/assets\/[a-z-]+\.js(?:\?.*)?$/,async r=>{const file=path.basename(new URL(r.request().url()).pathname);const source=execFileSync('git',['show',process.env.UI_BASELINE_REF+':control-center/public/assets/'+file],{cwd:path.resolve(__dirname,'../..')});await r.fulfill({status:200,contentType:'application/javascript',body:source});});
      try{await login(p,true);before=await metrics(p);await p.screenshot({path:path.join(output,'ui-before-desktop.png'),fullPage:true});await p.setViewportSize({width:390,height:950});await p.screenshot({path:path.join(output,'ui-before-mobile.png'),fullPage:true});}finally{await old.close();}
    }
    await login(page);const after=await metrics(page);assert(after.heading<=26);assert(after.panelPadding<=18);assert(after.sidebar<=240);assert.equal(await page.locator('.quick-links a').count(),0);assert.equal(await page.getByText('One update source of truth',{exact:true}).count(),0);await page.screenshot({path:path.join(output,'ui-after-desktop.png'),fullPage:true});
    if(before){assert(after.heading<before.heading);assert(after.panelPadding<before.panelPadding);assert(after.sidebar<before.sidebar);}
    fs.writeFileSync(path.join(output,'compact-metrics.json'),JSON.stringify({before,after},null,2));
    for(const width of [320,390,768,1440]){await page.setViewportSize({width,height:950});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);if(width<=900){await page.getByRole('button',{name:'Open navigation',exact:true}).click();const target=page.locator('#navigation a[href="#devices"]');assert((await target.boundingBox()).height>=44);await page.keyboard.press('Escape');}if(width===390){await page.evaluate(()=>document.activeElement?.blur());await page.screenshot({path:path.join(output,'ui-after-mobile.png'),fullPage:true});}}
    done('shared compact layout reduces heading/card/sidebar sizes and keeps320–1440px layouts and mobile tap targets usable');
    await page.setViewportSize({width:1440,height:1000});await page.evaluate(()=>{location.hash='library';});await page.getByRole('heading',{name:'Saved channel layouts',exact:true}).waitFor();await page.getByRole('button',{name:'Create channel layout',exact:true}).waitFor();assert.equal(await page.getByRole('link',{name:'Open template maker',exact:true}).count(),0);await page.screenshot({path:path.join(output,'compact-saved-setups-desktop.png'),fullPage:true});
    await page.evaluate(()=>{location.hash='branding';});await page.getByRole('heading',{name:'My app',exact:true}).waitFor();await page.locator('.brand-artwork-grid').waitFor({state:'attached'});const editor=page.locator('.brand-editor>summary');if(await editor.count())await editor.click();assert.equal(await page.locator('.brand-artwork-grid>.brand-image-row').count(),3);assert.equal(await page.getByRole('button',{name:'Save brand',exact:true}).isDisabled(),true);assert.equal(await page.locator('.brand-editor-fields>p.hint').count(),0);await page.screenshot({path:path.join(output,'compact-app-artwork-desktop.png'),fullPage:true});assert.deepEqual(errors,[]);done('app artwork and saved setups use task names and grouped fields without repeated setup paragraphs');
  }finally{await current.close();}
};
