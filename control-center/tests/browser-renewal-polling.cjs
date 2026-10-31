// Real timed status polling against fake payment endpoints only. No charges.
const assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'/tmp/opencode/control-center-browser-tools/node_modules/playwright');
(async()=>{
  const base=process.env.CONTROL_PREVIEW_URL;assert(base&&['127.0.0.1','localhost'].includes(new URL(base).hostname));
  const browser=await chromium.launch({headless:true}),page=await browser.newPage({viewport:{width:360,height:800}});
  let consent=true,routeRequests=0;page.on('dialog',dialog=>consent?dialog.accept():dialog.dismiss());
  const token='a'.repeat(64),order={id:'b'.repeat(32),gateway:'rampex',country:null,payment_state:'awaiting',fulfilment_state:'not_paid',currency:'USD',amount_minor:1000,payment_url:'https://checkout.rampex.io/pay.php?invoice=synthetic-only'};
  let current={...order},canExpire=false,creates=0,holdNext=false,held=null,onHeld=null;
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  const info=()=>({reseller:'TEST provider',target_label:'Existing account ending 1234',country:'CA',gateway:'square',gateways:[{gateway:'square',sandbox:false},{gateway:'stripe',sandbox:false}],simulated:false,order:current,renewal:{id:'c'.repeat(32),package_id:7,package_fingerprint:'d'.repeat(64),name:'Current monthly package',revision:1,currency:'CAD',currency_exponent:2,amount_minor:1000,duration:1,period:'months',max_connections:2}});
  await page.route('**/api/public-renewals/**',async route=>{
    const request=route.request(),path=new URL(request.url()).pathname;assert.equal(request.method(),'POST');assert.equal(request.postDataJSON().session_token,token);
    let body,status=200;
    if(path.endsWith('/info'))body=info();
    else if(path.endsWith('/route')){
      routeRequests++;assert.equal(request.postDataJSON().cancel_previous,false);
      if(current&&!canExpire){status=409;body={error:'The provider has not confirmed that the old invoice expired unpaid. Do not create another payment.'};}
      else{current=null;body=info();}
    }else if(path.endsWith('/checkout')){creates++;throw new Error('No checkout should be created by these UI checks.');}
    else{
      body={schema_version:1,order:current,simulated:false};
      if(holdNext){holdNext=false;held={route,body};onHeld?.();return;}
    }
    await route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)});
  });
  const frames=()=>page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
  const nextPoll=()=>page.waitForResponse(r=>new URL(r.url()).pathname==='/api/public-renewals/status');
  try{
    await page.goto(base+'/renew/'+token);await page.locator('#route').waitFor({state:'visible'});
    const poll=nextPoll();await poll;await frames();
    assert(await page.locator('#route').isVisible());assert(await page.locator('#gateway').isVisible());assert(await page.locator('#continue').isHidden());
    assert((await page.locator('#order-state').innerText()).includes('earlier Rampex invoice'));
    console.log('PASS timed status poll preserves gateway controls and Canadian Rampex warning instead of restoring the old link');
    consent=false;await page.locator('#apply-route').click();await frames();assert.equal(routeRequests,0);assert.equal(creates,0);consent=true;
    console.log('PASS declining cancellation confirmation sends no route or payment request');
    await page.locator('#apply-route').click();await page.waitForFunction(()=>document.getElementById('error').textContent.includes('not confirmed'));
    assert.equal(creates,0);assert(await page.locator('#route').isVisible());assert(await page.locator('#continue').isHidden());
    console.log('PASS a failed switch keeps the existing order and does not silently create a charge');
    canExpire=true;await page.locator('#apply-route').click();await page.locator('#checkout').waitFor({state:'visible'});
    assert.equal(await page.locator('#gateway').inputValue(),'square');assert.equal(creates,0);
    console.log('PASS successful immediate switch opens the selected quote without submitting a payment');

    current={...order};canExpire=false;await page.reload();await page.locator('#route').waitFor({state:'visible'});
    current=null;await nextPoll();await page.locator('#checkout').waitFor({state:'visible'});
    assert(await page.locator('#order').isHidden());assert.equal(creates,0);
    console.log('PASS status poll automatically refreshes a retired invoice into the current quote');

    current={...order,gateway:'square',payment_url:'https://square.link/u/synthetic-only'};await page.reload();await page.locator('#route').waitFor({state:'visible'});
    current={...current,payment_state:'paid',fulfilment_state:'pending',payment_url:null};await nextPoll();await frames();
    assert(await page.locator('#route').isHidden());assert(await page.locator('#continue').isHidden());assert(await page.locator('#checkout').isHidden());
    console.log('PASS paid invoice polling hides route changes and prevents new checkout');

    current={...order,gateway:'square',void_state:'uncertain',payment_url:'https://square.link/u/synthetic-only'};await page.reload();await page.locator('#route').waitFor({state:'visible'});
    assert(await page.locator('#continue').isHidden());assert((await page.locator('#order-state').innerText()).includes('not been confirmed'));
    console.log('PASS uncertain cancellation hides the old payment link and keeps recheck controls');

    current={...order};canExpire=false;await page.reload();await page.locator('#route').waitFor({state:'visible'});
    const requestHeld=new Promise(resolve=>{onHeld=resolve;});holdNext=true;await requestHeld;
    canExpire=true;await page.locator('#apply-route').click();await page.locator('#checkout').waitFor({state:'visible'});
    await held.route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(held.body)});await frames();
    assert(await page.locator('#checkout').isVisible());assert(await page.locator('#order').isHidden());assert(await page.locator('#route').isVisible());
    assert.equal(creates,0);assert.deepEqual(errors,[]);assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    console.log('PASS stale in-flight polling cannot restore an abandoned gateway after a successful route change');
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
