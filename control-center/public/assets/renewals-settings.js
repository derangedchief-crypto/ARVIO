'use strict';
window.Renewals={async render(epoch){
  const main=$('main');main.replaceChildren(heading('Customer renewals','Reseller-branded apps only. Payment verification and line renewal are separate steps.',[button('Refresh',route)]));
  let tenant=toolWorkspace();workspacePicker(main,'Payment workspace',tenant,async id=>{await chooseWorkspace(id);route();},{activeOnly:true});
  const box=panel('Payment gateways');main.append(box);
  try{
    const data=await api('/api/renewals/settings?tenant_id='+encodeURIComponent(tenant));if(epoch!==state.epoch)return;
    const exponents=data.currency_exponents||{USD:2,CAD:2,EUR:2,GBP:2,AUD:2};
    const money=p=>(p.amount_minor/10**(p.currency_exponent??exponents[p.currency]??2)).toFixed(p.currency_exponent??exponents[p.currency]??2)+' '+p.currency;
    const connection=data.connection;
    if(data.catalogue_only)box.append(el('p','Catalogue-only connection: package reads are enabled; payments and renewal writes remain OFF. No Rampex key is needed to set package prices.','scope-label'));
    box.append(el('p','API key and webhook secret are write-only and encrypted. Replacing a connection preserves its old orders.','scope-label'));
    box.append(el('p','Each reseller connects their own accounts. Stripe and Square use direct card checkout, not a crypto onramp. Sandbox payments NEVER reactivate real lines. Country is confirmed by the customer before the TV QR; providers verify payment eligibility.','scope-label'));
    const names={stripe:'Stripe',square:'Square',rampex:'Rampex'};
    for(const gateway of ['stripe','square','rampex']){
      const c=(data.connections||[connection].filter(Boolean)).find(c=>(c.gateway||'rampex')===gateway),card=panel(names[gateway]+' connection');box.append(card);
      card.append(el('p',c?`${c.business_name} · ${c.merchant_id} · ${c.mode==='live'?'Live merchant':'TEST ONLY — no real renewal'}`:'Not connected.'));
      card.append(el('p',gateway==='square'?'Use your Square developer access token, an active card-enabled Location ID, and webhook signature key. Token permissions must allow merchant/location reads, order read/write and payment read/write. Subscribe to payment.created and payment.updated.':gateway==='stripe'?'Use your Stripe secret/restricted API key and this endpoint’s whsec_ webhook signing secret. Restricted keys need account read, Checkout Sessions read/write, and PaymentIntents/Charges read. Subscribe to checkout.session.completed, checkout.session.async_payment_succeeded and checkout.session.expired.':'In Rampex Settings, configure your payout wallet, create an API key and copy your Webhook Secret. Rampex routing is US-only; it is not offered for Canada.','scope-label'));
      if(c)card.append(el('p','Configure this exact webhook URL in '+names[gateway]+':'),el('code',c.webhook_url,'brand-download'),el('p',c.webhook_verified_at?'Webhook signature verified: '+c.webhook_verified_at+' UTC':'Webhook not yet seen. Finish provider webhook setup before testing payments.','scope-label'));
      if(c){
        const enabled=c.checkout_enabled!==false;
        card.append(el('p',enabled?'New checkouts: ENABLED':'New checkouts: DISABLED — connection values saved','scope-label'));
        if(!data.catalogue_only)card.append(button((enabled?'Disable ':'Enable ')+names[gateway]+' checkouts',()=>modal(names[gateway]+' checkout availability',[
          {key:'enabled',label:'New customer checkouts',value:enabled?'off':'on',options:[['off','Disabled — keep saved connection'],['on','Enabled']],hint:'Customers automatically move to the enabled country default without waiting for old invoice expiry. Saved keys, webhook URL and preferences remain. Old unpaid attempts are superseded locally, NOT cancelled externally: old links may still accept funds. Late money is held for manual reconciliation, never a second renewal. Replacing the connection does not re-enable it.'}
        ],v=>api('/api/renewals/gateway-availability',{tenant_id:tenant,gateway,connection_id:c.id,expected_revision:c.availability_revision??0,enabled:v.enabled==='on'}))));
      }
      if(!data.catalogue_only)card.append(button(c?'Replace '+names[gateway]+' connection':'Connect '+names[gateway],async()=>{try{
        const setup=await api('/api/renewals/connection-setup',{tenant_id:tenant,gateway});if(epoch!==state.epoch)return;
        modal(names[gateway]+' merchant connection',[
        {key:'webhook_url',label:'Provider webhook URL — configure this first',value:setup.webhook_url,readOnly:true,max:300,hint:'Create the webhook in the matching sandbox/live provider account using this exact URL. Then copy its signing secret below, save this connection, and send a test notification.'},
        {key:'mode',label:'Mode',value:gateway==='rampex'?(data.simulation_allowed?'mock':'live'):'sandbox',options:gateway==='rampex'?(data.simulation_allowed?[['mock','DEMO — isolated simulator'],['live','Live — real money']]:[['live','Live — real money']]):[['sandbox','Sandbox — no real account renewal'],['live','Live — real money']]},
        {key:'api_key',label:gateway==='square'?'Square access token':names[gateway]+' API key',type:'password',max:512,hint:'Write-only. Never put merchant credentials in the APK.'},
        ...(gateway==='square'?[{key:'location_id',label:'Square Location ID',max:180}]:[]),
        {key:'webhook_secret',label:gateway==='square'?'Webhook signature key':gateway==='rampex'?'Webhook secret':'Webhook signing secret',type:'password',max:512}
      ],v=>{const{webhook_url,...credentials}=v;return api('/api/renewals/connection',{...credentials,setup_id:setup.setup_id,tenant_id:tenant,gateway});});
      }catch(error){toast(error.message);}}));
    }
    const routing=panel('Country routing',el('p','Preferred gateway first, then approved alternatives. Only configured, eligible gateways matching the invoice currency are offered. Canada never falls back to the Rampex crypto/card route. An unresolved invoice cannot be duplicated through another gateway.','scope-label'));main.append(routing);
    for(const country of ['US','CA']){
      const rule=data.routing?.[country]||{gateways:country==='CA'?['stripe','square']:['stripe','square','rampex'],revision:0};
      routing.append(el('p',(country==='CA'?'Canada':'United States')+': '+(rule.gateways.map(g=>names[g]).join(' → ')||'Disabled')));
      if(!data.catalogue_only)routing.append(button('Edit '+(country==='CA'?'Canada':'USA')+' gateways',()=>modal('Payment gateways · '+country,[
        {key:'preferred',label:'Preferred gateway',value:rule.gateways[0]||'none',options:[['none','Disabled'],...Object.keys(names).filter(g=>country!=='CA'||g!=='rampex').map(g=>[g,names[g]])]},
        {key:'second',label:'First alternative',value:rule.gateways[1]||'none',options:[['none','None'],...Object.keys(names).filter(g=>country!=='CA'||g!=='rampex').map(g=>[g,names[g]])]},
        {key:'third',label:'Second alternative',value:rule.gateways[2]||'none',options:[['none','None'],...Object.keys(names).filter(g=>country!=='CA'||g!=='rampex').map(g=>[g,names[g]])]}
      ],v=>api('/api/renewals/routing',{tenant_id:tenant,country,expected_revision:rule.revision,gateways:v.preferred!=='none'?[...new Set([v.preferred,v.second,v.third].filter(g=>g!=='none'))]:[]}))));
    }
    if(state.user.role==='owner'&&!data.catalogue_only)box.append(button('Rollout policy',()=>modal('Renewal payment rollout',[{key:'enabled',label:'Payments',value:data.policy.enabled?'yes':'no',options:[['no','Off'],['yes','On']]},{key:'testing_only',label:'Eligible branded apps',value:data.policy.testing_only?'yes':'no',options:[['yes','Testing branded apps only'],['no','Testing and production branded apps']]}],v=>api('/api/renewals/policy',{enabled:v.enabled==='yes',testing_only:v.testing_only==='yes'}))));
    const prices=panel('Package pricing',el('p','Packages, terms, connections and credit costs come from your provider’s panel. Set only the money price customers pay. Each customer renews their current package—no package changes. Set country-specific prices when needed: Square uses its location currency; direct Stripe currently supports USD and CAD. No currency conversion is guessed.','scope-label'));main.append(prices);
    function priceForm(pkg,old){
      modal(pkg.name+' — customer price',[
        {key:'amount',label:'Customer renewal price',value:old?(old.amount_minor/10**(old.currency_exponent??exponents[old.currency]??2)).toFixed(old.currency_exponent??exponents[old.currency]??2):'',hint:'This is the customer money price, not the panel credit cost. Use the currency’s decimal precision.'},
        {key:'currency',label:'Currency',value:old?.currency||'USD',options:Object.keys(exponents).map(c=>[c,c])},
        {key:'country',label:'Payment country',value:old?.country||'default',options:[['default','Default price'],['US','United States'],['CA','Canada']]},
        {key:'enabled',label:'Available for checkout',value:old?.enabled===false?'no':'yes',options:[['yes','Yes'],['no','No']]}
      ],v=>api('/api/renewals/prices',{...v,country:v.country==='default'?'':v.country,tenant_id:tenant,id:old?.id||'',expected_revision:old?.revision||0,package_id:pkg.id,enabled:v.enabled==='yes'}));
    }
    try{
       const catalog=await api('/api/renewals/catalog?tenant_id='+encodeURIComponent(tenant));if(epoch!==state.epoch)return;
       const conversion=data.trial_conversion||{enabled:false,package_id:null,revision:0},conversionPlan=catalog.packages.find(p=>p.id===conversion.package_id);
       const conversions=panel('Trial conversions',el('p','Only verified expired trials use this default paid plan. Existing paid subscriptions always renew their current package. Missing or unavailable conversion defaults cannot create a checkout. Trial creation settings are separate.','scope-label'),el('p',conversion.enabled?(conversionPlan?'Default paid plan: '+conversionPlan.name:'Configured paid plan is no longer offered — conversions unavailable'):'Trial conversions are disabled.'));
       main.append(conversions);
       if(!data.catalogue_only)conversions.append(button('Set trial conversion plan',()=>modal('Default paid plan for trial conversions',[
         {key:'enabled',label:'Paid trial conversions',value:conversion.enabled?'yes':'no',options:[['no','Disabled'],['yes','Enabled']]},
         {key:'package_id',label:'Default paid conversion plan',value:String(conversion.package_id||''),options:[['','Choose a paid plan'],...catalog.packages.map(p=>[String(p.id),p.name+' · '+p.duration+' '+p.period+' · '+p.max_connections+' connection(s)'])],hint:'Only this reseller-selected paid plan is offered to expired trials. Set its customer prices below. Requires the trial-conversion renewal worker update.'}
       ],v=>api('/api/renewals/trial-conversion',{tenant_id:tenant,enabled:v.enabled==='yes',package_id:v.package_id?Number(v.package_id):null,expected_revision:conversion.revision}))));
      prices.append(table(['Offered package','Panel credits','Customer price','Actions'],catalog.packages.map(pkg=>{
        const saved=data.prices.filter(p=>p.package_id===pkg.id),old=saved.find(p=>!p.country),actions=el('div',null,'actions');
        actions.append(button(old?'Edit default price':'Set price',()=>priceForm(pkg,old)));
        for(const country of ['US','CA']){const price=saved.find(p=>p.country===country);actions.append(button((price?'Edit ':'Set ')+country+' price',()=>priceForm(pkg,price||{country,revision:0,currency:country==='CA'?'CAD':'USD',amount_minor:0})));}
        return [cellName(pkg.name,`${pkg.duration} ${pkg.period} · ${pkg.max_connections} connection(s)`),pkg.credits??'—',saved.length?saved.map(p=>(p.country||'Default')+': '+money(p)+(p.enabled?'':' · off')).join(' / '):'Not set',actions];
      })));
      if(!catalog.packages.length)prices.append(el('p','No offered paid packages are available. Contact your provider.'));
    }catch(error){prices.append(el('p','Offered packages unavailable: '+error.message,'error'));}
    const orders=await api('/api/renewals/orders?tenant_id='+encodeURIComponent(tenant));if(epoch!==state.epoch)return;
    main.append(panel('Recent renewal orders',el('p','Up to 50 recent orders. A paid order is never charged again when fulfilment needs repair.','scope-label'),table(['Order','Payment','Renewal','Actions'],orders.items.map(o=>{
      const actions=el('div',null,'actions');
      if(o.mode==='mock'&&orders.simulation_allowed)actions.append(button('Simulate payment',async()=>{await api('/api/renewals/orders/'+o.id+'/simulate',{});if(state.user.role==='owner')await api('/api/renewals/process-demo',{});route();}));
        if(o.payment_state==='paid'&&o.fulfilment_state!=='fulfilled'&&!Number(o.checkout_superseded))actions.append(button('Retry same renewal',async()=>{await api('/api/renewals/orders/'+o.id+'/retry',{});toast('Same order queued. No new charge.');route();}));
        if(['review','verification_pending'].includes(o.payment_state)&&o.fulfilment_state!=='fulfilled'&&!Number(o.checkout_superseded))actions.append(button('Recheck same payment',async()=>{await api('/api/renewals/orders/'+o.id+'/retry',{});toast('Original payment evidence queued for verification. No new charge.');route();}));
       return [cellName(o.id,money(o)+(Number(o.checkout_superseded)?' · Superseded — old gateway disabled; reconcile late funds':'')),o.payment_state,o.fulfilment_state,actions];
    }))));
  }catch(error){if(epoch===state.epoch)box.replaceChildren(el('p',error.message,'error'),el('p','Select an active workspace with a verified panel reseller link. Your owner workspace is supported when linked. No payment or account change has been requested.','scope-label'));}
}};
