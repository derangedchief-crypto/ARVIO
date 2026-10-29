'use strict';
(async()=>{
  const $=id=>document.getElementById(id),match=location.pathname.match(/^\/renew\/([a-f0-9]{64})$/),demo=location.pathname.match(/^\/renew\/demo\/([a-f0-9]{32})$/);
  async function post(path,body,csrf){const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json',...(csrf?{'X-CSRF-Token':csrf}:{})},body:JSON.stringify(body)});const data=await r.json();if(!r.ok)throw new Error(data.error||'The request could not be completed.');return data;}
  function paymentUrl(value){const u=new URL(value,location.origin);if(u.protocol!=='https:'&&u.origin!==location.origin)throw new Error('Invalid payment destination.');if(u.origin!==location.origin&&!['checkout.rampex.io','rampex.io','pay.rampex.io'].includes(u.hostname))throw new Error('Unexpected payment destination.');return u.href;}
  function showOrder(order,simulated){
    $('checkout').hidden=true;$('order').hidden=false;$('demo').hidden=!simulated;$('reference').textContent='Order reference: '+order.id;
    const paid=order.payment_state==='paid',done=order.fulfilment_state==='fulfilled';
    $('order-title').textContent=done?(simulated?'Demo renewal completed':'Renewal completed'):paid?'Payment received':'Renewal order';
    $('order-state').textContent=done?(simulated?'No real line was changed.':'Your TV will restore access after verifying your account is active.'):paid?'Your renewal is processing. Do not pay again.':order.payment_state==='awaiting'?'Continue your existing payment below.':'This order needs verification. Contact your provider; do not create another payment.';
    $('continue').hidden=!order.payment_url;if(order.payment_url){$('continue').href=paymentUrl(order.payment_url);$('continue').textContent=simulated?'Open demo checkout':'Continue existing payment';}
  }
  try{
    if(demo){
      $('demo').hidden=false;$('message').textContent='This is a controlled payment simulation, not a payment provider.';$('simulator').hidden=false;
      $('simulate').onclick=async()=>{try{const r=await fetch('/api/me');const me=await r.json();if(!r.ok)throw new Error('Sign into the control center as the authorized test operator first.');$('simulate').disabled=true;await post('/api/renewals/orders/'+demo[1]+'/simulate',{},me.csrf);if(me.user.role==='owner')await post('/api/renewals/process-demo',{},me.csrf);$('message').textContent='Verified demo notification recorded. Return to the original renewal tab or TV to view processing status.';}catch(e){$('error').textContent=e.message;$('simulate').disabled=false;}};
      return;
    }
    if(!match)throw new Error('Invalid checkout link.');const token=match[1];
    const info=await post('/api/public-renewals/info',{session_token:token});$('seller').textContent=info.reseller;$('demo').hidden=!info.simulated;
    const target=document.createElement('p');target.textContent=info.target_label;target.className='quiet';$('seller').after(target);
    if(info.order)showOrder(info.order,info.simulated);
    else if(info.renewal){
      const p=info.renewal;$('message').textContent='Renew your current package. Review the price before continuing; your package will not change.';$('checkout').hidden=false;
      $('package-name').textContent=p.name;
      const exponent=p.currency_exponent??2;
      $('details').textContent=p.duration+' '+p.period+' · '+p.max_connections+' connection(s) · '+(p.amount_minor/10**exponent).toFixed(exponent)+' '+p.currency;
      $('checkout').onsubmit=async e=>{e.preventDefault();$('error').textContent='';$('pay').disabled=true;try{const result=await post('/api/public-renewals/checkout',{session_token:token,price_id:p.id,price_revision:p.revision,package_fingerprint:p.package_fingerprint,email:$('email').value});showOrder(result.order,info.simulated);if(result.order.payment_url)location.assign(paymentUrl(result.order.payment_url));}catch(error){$('error').textContent=error.message;$('pay').disabled=false;}};
    }else $('message').textContent='Renewal is unavailable or this quote expired. Please return to your TV or contact your provider.';
    let checks=0;const timer=setInterval(async()=>{if(document.hidden||++checks>180){if(checks>180)clearInterval(timer);return;}try{const r=await post('/api/public-renewals/status',{session_token:token});if(r.order){showOrder(r.order,r.simulated);if(r.order.fulfilment_state==='fulfilled')clearInterval(timer);}}catch(error){clearInterval(timer);$('error').textContent=error.message;}},10000);
  }catch(error){$('message').textContent='Unable to open this renewal.';$('error').textContent=error.message;}
})();
