'use strict';
(async()=>{
  const $=id=>document.getElementById(id),match=location.pathname.match(/^\/renew\/([a-f0-9]{64})$/),demo=location.pathname.match(/^\/renew\/demo\/([a-f0-9]{32})$/);
  async function post(path,body,csrf){const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json',...(csrf?{'X-CSRF-Token':csrf}:{})},body:JSON.stringify(body)});const data=await r.json();if(!r.ok)throw new Error(data.error||'The request could not be completed.');return data;}
  function paymentUrl(value){const u=new URL(value,location.origin);if(u.username||u.password||(u.port&&u.port!=='443'&&u.origin!==location.origin))throw new Error('Invalid payment destination.');if(u.origin===location.origin){if(!/^\/renew\/demo\/[a-f0-9]{32}$/.test(u.pathname))throw new Error('Unexpected payment destination.');}else if(u.protocol!=='https:'||!['checkout.rampex.io','rampex.io','pay.rampex.io','checkout.stripe.com','square.link','checkout.square.site','sandbox.square.link','sandbox.checkout.square.site'].includes(u.hostname))throw new Error('Unexpected payment destination.');return u.href;}
  function showOrder(order,simulated,keepRoutes=false,country=null){
    const awaiting=order.payment_state==='awaiting';
    $('checkout').hidden=true;$('route').hidden=!(keepRoutes&&awaiting);$('order').hidden=false;$('demo').hidden=!simulated;$('reference').textContent='Order reference: '+order.id;
    const paid=order.payment_state==='paid',done=order.fulfilment_state==='fulfilled';
    $('order-title').textContent=done?(simulated?'Demo renewal completed':'Renewal completed'):paid?'Payment received':'Renewal order';
    $('order-state').textContent=done?(simulated?'No real line was changed.':'Your TV will restore access after verifying your account is active.'):paid?'Your renewal is processing. Do not pay again.':order.payment_state==='awaiting'?'Continue your existing payment below.':'This order needs verification. Contact your provider; do not create another payment.';
    $('continue').hidden=!order.payment_url;if(order.payment_url){$('continue').href=paymentUrl(order.payment_url);$('continue').textContent=simulated?'Open demo checkout':'Continue existing payment';}
    if(awaiting&&keepRoutes)$('apply-route').textContent='Switch to this payment option';
    if(order.void_state&&order.void_state!=='confirmed'){
      $('continue').hidden=true;
      $('order-state').textContent='Previous cancellation has not been confirmed. You can switch providers below immediately; the old invoice is retained for reconciliation. Do not pay the old link after switching.';
    }
    if(awaiting&&order.gateway_disabled){$('continue').hidden=true;$('order-state').textContent='Your provider disabled the old gateway. Use the configured enabled default below immediately; the old invoice is retained for reconciliation, without an expiry wait.';}
    if(awaiting&&country==='CA'&&order.gateway==='rampex'){
      $('continue').hidden=true;
      $('order-state').textContent='Your earlier Rampex invoice is retained for reconciliation. Choose Stripe or Square below and switch immediately, without waiting for the old invoice to expire. Do not pay the old link after switching.';
    }
  }
  try{
    if(demo){
      $('demo').hidden=false;$('message').textContent='This is a controlled payment simulation, not a payment provider.';$('simulator').hidden=false;
      $('simulate').onclick=async()=>{try{const r=await fetch('/api/me');const me=await r.json();if(!r.ok)throw new Error('Sign into the control center as the authorized test operator first.');$('simulate').disabled=true;await post('/api/renewals/orders/'+demo[1]+'/simulate',{},me.csrf);if(me.user.role==='owner')await post('/api/renewals/process-demo',{},me.csrf);$('message').textContent='Verified demo notification recorded. Return to the original renewal tab or TV to view processing status.';}catch(e){$('error').textContent=e.message;$('simulate').disabled=false;}};
      return;
    }
    if(!match)throw new Error('Invalid checkout link.');const token=match[1];
    let routeRevision=0,info=await post('/api/public-renewals/info',{session_token:token});$('seller').textContent='Payment Gateway';
    const target=document.createElement('p');target.textContent=info.target_label;target.className='quiet';$('seller').after(target);
    const names={stripe:'Stripe — card',square:'Square — card',rampex:'Rampex'};
    function render(data){
      info=data;$('demo').hidden=!info.simulated;$('order').hidden=true;$('checkout').hidden=true;$('route').hidden=true;
      const emailRequired=info.gateway==='rampex';$('email-field').hidden=!emailRequired;$('email').required=emailRequired;$('email').disabled=!emailRequired;if(!emailRequired)$('email').value='';
      if(info.order){showOrder(info.order,info.simulated,false,info.country);if(info.order.payment_state!=='awaiting')return;}
      $('route').hidden=false;$('country').value=info.country||'';
      const gateways=(info.gateways||[]).filter(g=>Object.hasOwn(names,g.gateway));$('gateway').replaceChildren();
      for(const g of gateways){const option=document.createElement('option');option.value=g.gateway;option.textContent=names[g.gateway]+(g.sandbox?' — TEST ONLY':'');$('gateway').append(option);}
      $('gateway').value=info.gateway||'';$('gateway-choice').hidden=!gateways.length;
      $('apply-route').textContent=info.order?'Switch to this payment option':'Use this payment option';
      if(info.order){$('message').textContent='Continue your existing payment attempt, or choose another provider below. Switching is immediate. Do not pay an old link after switching.';return;}
      if(!['US','CA'].includes(info.country)){$('message').textContent='Confirm USA or Canada before continuing. Your current package will not change.';return;}
      if(!info.renewal){$('message').textContent='Renewal is unavailable or this quote expired. Please return to your TV or contact your provider.';return;}
      const p=info.renewal,conversion=p.kind==='trial_conversion';$('package-context').textContent=conversion?'Paid plan for your expired trial':'Your current package';$('message').textContent=conversion?'Convert your expired trial to the paid plan selected by your provider. Review its price and terms. Your existing account and saved app settings are retained.':'Renew your current package. Review the price and payment gateway before continuing; your package will not change.';$('checkout').hidden=false;
      $('package-name').textContent=p.name;const exponent=p.currency_exponent??2;
      $('details').textContent=p.duration+' '+p.period+' · '+p.max_connections+' connection(s) · '+(p.amount_minor/10**exponent).toFixed(exponent)+' '+p.currency;
      $('pay').disabled=!gateways.some(g=>g.gateway===info.gateway);
      if($('pay').disabled)$('error').textContent='Choose an available gateway, or ask your provider to configure one for this country and currency.';
      $('checkout').onsubmit=async e=>{e.preventDefault();$('error').textContent='';$('pay').disabled=true;try{const result=await post('/api/public-renewals/checkout',{session_token:token,price_id:p.id,price_revision:p.revision,package_fingerprint:p.package_fingerprint,...(emailRequired?{email:$('email').value}:{})});info.order=result.order;showOrder(result.order,info.simulated,true,info.country);if(result.order.payment_url)location.assign(paymentUrl(result.order.payment_url));}catch(error){try{render(await post('/api/public-renewals/info',{session_token:token}));}catch{$('pay').disabled=false;}$('error').textContent=error.message;}};
    }
    async function changeRoute(gateway){if(info.order&&!confirm('Switch to this provider now? The old unpaid invoice will be left behind for reconciliation, not cancelled externally. Do not pay the old link after switching.'))return;const revision=++routeRevision;$('error').textContent='';$('pay').disabled=true;$('apply-route').disabled=true;try{const data=await post('/api/public-renewals/route',{session_token:token,country:$('country').value,...(gateway?{gateway}:{}),cancel_previous:false});if(revision===routeRevision)render(data);}catch(error){if(revision===routeRevision)$('error').textContent=error.message;}finally{if(revision===routeRevision)$('apply-route').disabled=false;}}
    $('country').onchange=()=>changeRoute(null);$('gateway').onchange=()=>changeRoute($('gateway').value);$('apply-route').onclick=()=>changeRoute($('gateway').value||null);render(info);
    let checks=0;const timer=setInterval(async()=>{if(document.hidden||++checks>180){if(checks>180)clearInterval(timer);return;}const revision=routeRevision;try{const r=await post('/api/public-renewals/status',{session_token:token});if(revision!==routeRevision)return;if(r.disabled_gateways?.includes(info.gateway)){const data=await post('/api/public-renewals/info',{session_token:token});if(revision===routeRevision)render(data);return;}if(r.order){info.order=r.order;showOrder(r.order,r.simulated,!$('route').hidden,$('country').value||info.country);if(r.order.fulfilment_state==='fulfilled')clearInterval(timer);}else if(info.order){const data=await post('/api/public-renewals/info',{session_token:token});if(revision===routeRevision)render(data);}}catch(error){if(revision===routeRevision){clearInterval(timer);$('error').textContent=error.message;}}},10000);
  }catch(error){$('message').textContent='Unable to open this renewal.';$('error').textContent=error.message;}
})();
