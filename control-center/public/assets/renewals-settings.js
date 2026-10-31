'use strict';
window.Renewals={async render(epoch,section){
  const main=$('main'),tenant=toolWorkspace(),valid=()=>epoch===state.epoch&&!!state.user;
  main.replaceChildren(heading('Customer renewals','Payments, paid plans and gateway settings.',[button('Refresh',route)]));
  workspacePicker(main,'Payment workspace',tenant,async id=>{await chooseWorkspace(id);route();},{activeOnly:true});workspaceTitle(tenant);
  const loading=el('p','Loading payments…','hint');main.append(loading);
  try{
    const data=await api('/api/renewals/settings?tenant_id='+encodeURIComponent(tenant));if(!valid())return;
    const [catalogResult,orderResult]=await Promise.allSettled([api('/api/renewals/catalog?tenant_id='+encodeURIComponent(tenant)),api('/api/renewals/orders?tenant_id='+encodeURIComponent(tenant))]);if(!valid())return;loading.remove();
    const names={stripe:'Stripe',square:'Square',rampex:'Rampex'},connections=data.connections||[data.connection].filter(Boolean),exponents=data.currency_exponents||{USD:2,CAD:2};
    const money=p=>(p.amount_minor/10**(p.currency_exponent??exponents[p.currency]??2)).toFixed(p.currency_exponent??exponents[p.currency]??2)+' '+p.currency;
    const orders=orderResult.status==='fulfilled'?orderResult.value:null,catalog=catalogResult.status==='fulfilled'?catalogResult.value:null;
    const needsAttention=o=>(o.payment_state==='paid'&&o.fulfilment_state!=='fulfilled')||['review','verification_pending','link_uncertain'].includes(o.payment_state);
    const attention=orders?.items.filter(needsAttention)||[];
    const summary=el('div',null,'section-summary');summary.append(el('span',data.policy.enabled?(data.policy.testing_only?'Payments on · testing apps only':'Payments on · testing and production apps'):'New payments paused'),el('span',connections.filter(c=>c.checkout_enabled!==false).length+' enabled gateway(s)'));main.append(summary);
    const attentionBox=el('div');main.append(attentionBox);
    const tabs=sectionTabs(main,'Renewals:'+tenant,[['payments','Payments'],['plans','Plans & pricing'],['gateways','Gateways'],['settings','Settings']],section,id=>history.replaceState(null,'','#renewals/'+id));
    const payments=tabs.panes.get('payments'),plans=tabs.panes.get('plans'),gateways=tabs.panes.get('gateways'),settings=tabs.panes.get('settings');
    if(data.catalogue_only)attentionBox.append(el('p','Catalogue-only connection: payments and renewal writes remain OFF. Package prices can still be prepared.','notice warning'));
    if(!data.prices.some(p=>p.enabled))attentionBox.append(el('p','No active customer prices are configured. Set plan pricing before accepting payments.','notice warning'));
    const unverified=connections.filter(c=>c.checkout_enabled!==false&&!c.webhook_verified_at);
    if(unverified.length)attentionBox.append(el('p','Webhook not yet verified: '+unverified.map(c=>names[c.gateway]).join(', ')+'. Review gateway setup before accepting payments.','notice warning'));
    if(attention.length){const box=panel('Payments need attention',el('p',attention.length+' recent order(s) need payment verification, reconciliation or renewal review. Do not charge again.','hint'),button('Review payments',()=>{filter.value='attention';page=1;drawPayments();tabs.select('payments');}));box.classList.add('attention-strip');attentionBox.append(box);}
    // Daily operations are separate from configuration and remain available if
    // the catalogue is temporarily unavailable. No action is sent on tab change.
    let page=1;const search=el('input');search.type='search';search.placeholder='Search order reference…';search.setAttribute('aria-label','Search renewal orders');const filter=el('select');filter.setAttribute('aria-label','Payment status');
    for(const[value,label]of [['all','All recent payments'],['attention','Needs attention'],['paid','Payment received'],['awaiting','Awaiting payment']]){const o=el('option',label);o.value=value;filter.append(o);}
    const toolbar=el('div',null,'section-toolbar'),results=el('div');toolbar.append(search,filter);payments.append(toolbar,results);
    function drawPayments(){if(!orders){results.replaceChildren(panel('Payments could not load',el('p',orderResult.reason.message,'error'),button('Retry',route)));return;}
      const rows=orders.items.filter(o=>(!search.value||o.id.toLowerCase().includes(search.value.toLowerCase().trim()))&&(filter.value==='all'||filter.value==='attention'&&needsAttention(o)||o.payment_state===filter.value));page=Math.max(1,Math.min(page,Math.ceil(rows.length/10)||1));
      results.replaceChildren(table(['Order','Amount','Payment','Renewal','Actions'],rows.slice((page-1)*10,page*10).map(o=>{const a=el('div',null,'actions');
        if(o.mode==='mock'&&orders.simulation_allowed)a.append(button('Simulate payment',async()=>{await api('/api/renewals/orders/'+o.id+'/simulate',{});if(state.user.role==='owner')await api('/api/renewals/process-demo',{});route();}));
        if(o.payment_state==='paid'&&o.fulfilment_state!=='fulfilled'&&!Number(o.checkout_superseded))a.append(button('Retry same renewal',async()=>{await api('/api/renewals/orders/'+o.id+'/retry',{});toast('Same order queued. No new charge.');route();}));
        if(['review','verification_pending'].includes(o.payment_state)&&o.fulfilment_state!=='fulfilled'&&!Number(o.checkout_superseded))a.append(button('Recheck same payment',async()=>{await api('/api/renewals/orders/'+o.id+'/retry',{});toast('Original payment evidence queued. No new charge.');route();}));
        const ref=el('details');ref.append(el('summary',o.id.slice(0,8)+'…'),el('code',o.id));return[cellName('',null),money(o),badge(o.payment_state),cellName(o.fulfilment_state.replaceAll('_',' '),Number(o.checkout_superseded)?'Superseded · reconcile late funds':o.mode==='sandbox'||o.mode==='mock'?'TEST ONLY — no real account renewal':''),a].map((v,i)=>i===0?ref:v);
      })),pagination({page,pages:Math.max(1,Math.ceil(rows.length/10)),total:rows.length},p=>{page=p;drawPayments();}),el('p','Up to 50 recent orders. A paid order is never charged again to repair fulfilment.','hint'));
    }
    search.oninput=()=>{page=1;drawPayments();};filter.onchange=()=>{page=1;drawPayments();};drawPayments();
    const conversion=data.trial_conversion||{enabled:false,package_id:null,revision:0},selected=catalog?.packages.find(p=>p.id===conversion.package_id);
    const conversionBox=panel('Trial conversions',el('p',conversion.enabled?(selected?'Default paid plan: '+selected.name:catalog?'Configured plan is no longer offered — conversion unavailable':'Configured plan details could not load'):'Trial conversions are disabled.','hint'));
    if(conversion.enabled&&catalog&&!selected)attentionBox.append(el('p','The configured trial conversion plan is no longer offered. Review Plans & pricing; no replacement plan is chosen automatically.','notice warning'));
    if(conversion.enabled&&selected&&!data.prices.some(p=>p.package_id===conversion.package_id&&p.enabled))attentionBox.append(el('p','The trial conversion plan has no active customer price. Set its pricing before customers can convert.','notice warning'));
    if(conversion.enabled&&selected?.funded===false)attentionBox.append(el('p','The trial conversion plan needs reseller funding. No new conversion checkout is offered until funding is available.','notice warning'));
    conversionBox.append(helpDetails('How trial conversion differs','Only server-verified expired trials use this default paid plan. Existing paid subscriptions renew their current package. The free-trial creation package is configured separately in Trials & contacts.'));
    if(!data.catalogue_only&&catalog)conversionBox.append(button('Set trial conversion plan',()=>modal('Default paid plan for trial conversions',[
      {key:'enabled',label:'Paid trial conversions',value:conversion.enabled?'yes':'no',options:[['no','Disabled'],['yes','Enabled']]},
      {key:'package_id',label:'Default paid conversion plan',value:String(conversion.package_id||''),required:false,options:[['','Choose a paid plan'],...catalog.packages.map(p=>[String(p.id),p.name+' · '+p.duration+' '+p.period+' · '+p.max_connections+' connection(s)'])],hint:'Set the selected plan’s customer prices below. Requires the trial-conversion renewal worker.'}
    ],v=>api('/api/renewals/trial-conversion',{tenant_id:tenant,enabled:v.enabled==='yes',package_id:v.package_id?Number(v.package_id):null,expected_revision:conversion.revision}))));plans.append(conversionBox);
    function priceForm(pkg){const saved=data.prices.filter(p=>p.package_id===pkg.id);const initial=saved.find(p=>!p.country)||saved[0];const countries=[['default','Default price'],['US','United States'],['CA','Canada']];
      const row=country=>saved.find(p=>(p.country||'default')===country);
      modal(pkg.name+' — customer price',[
        {key:'country',label:'Payment country',value:initial?.country||'default',options:countries},
        {key:'amount',label:'Customer renewal price',value:initial?(initial.amount_minor/10**(initial.currency_exponent??exponents[initial.currency]??2)).toFixed(initial.currency_exponent??exponents[initial.currency]??2):''},
        {key:'currency',label:'Currency',value:initial?.currency||'USD',options:Object.keys(exponents).map(c=>[c,c])},
        {key:'enabled',label:'Available for checkout',value:initial?.enabled===false?'no':'yes',options:[['yes','Yes'],['no','No']]}
      ],v=>{const old=row(v.country);return api('/api/renewals/prices',{...v,country:v.country==='default'?'':v.country,tenant_id:tenant,id:old?.id||'',expected_revision:old?.revision||0,package_id:pkg.id,enabled:v.enabled==='yes'});});
      const form=$('modal-form');let previous=form.elements.country.value;const values=()=>[form.elements.amount.value,form.elements.currency.value,form.elements.enabled.value].join('|');let original=values();
      form.elements.country.onchange=e=>{if(values()!==original&&!confirm('Discard unsaved price edits and switch country? Each save updates only one country price.')){e.target.value=previous;return;}const old=row(e.target.value);form.elements.amount.value=old?(old.amount_minor/10**(old.currency_exponent??exponents[old.currency]??2)).toFixed(old.currency_exponent??exponents[old.currency]??2):'';form.elements.currency.value=old?.currency||(e.target.value==='CA'?'CAD':'USD');form.elements.enabled.value=old?.enabled===false?'no':'yes';previous=e.target.value;original=values();};
    }
    if(!catalog)plans.append(panel('Offered packages unavailable',el('p',catalogResult.reason.message,'error'),button('Retry',route)));
    else{const box=panel('Package pricing'),controls=el('div',null,'section-toolbar'),find=el('input'),show=el('select'),list=el('div');find.type='search';find.placeholder='Find a paid plan…';find.setAttribute('aria-label','Search paid plans');show.setAttribute('aria-label','Plan visibility');
      for(const[value,label]of [['configured','Configured plans'],['all','All offered plans'],['unconfigured','Not priced']]){const o=el('option',label);o.value=value;show.append(o);}show.value=data.prices.length?'configured':'all';let planPage=1;
      controls.append(find,show);box.append(controls,list,helpDetails('Package and currency rules','The panel defines packages, terms, connections and credit costs. Set customer prices only. Square uses its location currency; Stripe supports USD/CAD. Country-specific prices are saved independently; no currency conversion is guessed.'));plans.append(box);
      function drawPlans(){const rows=catalog.packages.filter(pkg=>{const configured=data.prices.some(p=>p.package_id===pkg.id);return(pkg.name+' '+pkg.id).toLowerCase().includes(find.value.toLowerCase().trim())&&(show.value==='all'||show.value==='configured'&&configured||show.value==='unconfigured'&&!configured);});planPage=Math.max(1,Math.min(planPage,Math.ceil(rows.length/10)||1));
        list.replaceChildren(table(['Offered package','Customer price','Actions'],rows.slice((planPage-1)*10,planPage*10).map(pkg=>{const saved=data.prices.filter(p=>p.package_id===pkg.id);return[cellName(pkg.name,`${pkg.duration} ${pkg.period} · ${pkg.max_connections} connection(s) · ${pkg.credits??'—'} panel credits`),saved.length?saved.map(p=>(p.country||'Default')+': '+money(p)+(p.enabled?'':' · off')).join(' / '):'Not set',button(saved.length?'Edit pricing':'Set price',()=>priceForm(pkg))];})),pagination({page:planPage,pages:Math.max(1,Math.ceil(rows.length/10)),total:rows.length},p=>{planPage=p;drawPlans();}));}
      find.oninput=()=>{planPage=1;drawPlans();};show.onchange=()=>{planPage=1;drawPlans();};drawPlans();
    }
    const gatewayBox=panel('Payment gateways');gateways.append(gatewayBox);
    for(const gateway of ['stripe','square','rampex']){const c=connections.find(c=>(c.gateway||'rampex')===gateway),row=el('div',null,'summary-row'),label=el('div');label.append(el('h3',names[gateway]),el('p',c?(c.checkout_enabled===false?'Disabled · saved connection retained':'Enabled')+' · '+(c.mode==='live'?'Live merchant':'TEST ONLY — no real renewal'):'Not connected'));row.append(label);gatewayBox.append(row);
      const guide=gateway==='square'?'Use a Square access token, active card-enabled Location ID and webhook signature key. Permissions: merchant/location reads, order read/write, payment read/write. Subscribe to payment.created and payment.updated.':gateway==='stripe'?'Use a Stripe API key and endpoint-specific whsec_ signing secret. Restricted keys need account read, Checkout Sessions read/write and PaymentIntents/Charges read. Subscribe to checkout.session.completed, checkout.session.async_payment_succeeded and checkout.session.expired.':'Configure your Rampex payout wallet, API key and webhook secret. Rampex is US-only; it is never offered for Canada.';
      const connect=async()=>{try{const setup=await api('/api/renewals/connection-setup',{tenant_id:tenant,gateway});if(!valid())return;modal(names[gateway]+' merchant connection',[
        {key:'webhook_url',label:'Provider webhook URL — configure this first',value:setup.webhook_url,readOnly:true,max:300,hint:'Create the matching sandbox/live provider webhook using this exact URL, then save the connection with its signing secret.'},
        {key:'mode',label:'Mode',value:gateway==='rampex'?(data.simulation_allowed?'mock':'live'):'sandbox',options:gateway==='rampex'?(data.simulation_allowed?[['mock','DEMO — isolated simulator'],['live','Live — real money']]:[['live','Live — real money']]):[['sandbox','Sandbox — no real account renewal'],['live','Live — real money']]},
        {key:'api_key',label:gateway==='square'?'Square access token':names[gateway]+' API key',type:'password',max:512,hint:'Write-only; saved secrets are never displayed.'},
        ...(gateway==='square'?[{key:'location_id',label:'Square Location ID',max:180}]:[]),{key:'webhook_secret',label:gateway==='square'?'Webhook signature key':gateway==='rampex'?'Webhook secret':'Webhook signing secret',type:'password',max:512}
      ],v=>{const{webhook_url,...credentials}=v;return api('/api/renewals/connection',{...credentials,setup_id:setup.setup_id,tenant_id:tenant,gateway});});}catch(e){toast(e.message);}};
      if(!c){if(!data.catalogue_only)row.append(button('Connect '+names[gateway],connect));gatewayBox.append(helpDetails(names[gateway]+' setup guide',guide));continue;}
      label.append(el('p',c.webhook_verified_at?'Webhook signature verified: '+c.webhook_verified_at+' UTC':'Webhook not yet verified — setup needs attention',c.webhook_verified_at?'scope-label':'error'));
      const details=disclosure('Manage '+names[gateway],el('p',c.business_name+' · '+c.merchant_id,'scope-label'),el('p',guide,'hint'),el('p','Configure this exact provider webhook URL:'),el('code',c.webhook_url,'brand-download'));
      if(!data.catalogue_only)details.append(button((c.checkout_enabled===false?'Enable ':'Disable ')+names[gateway]+' checkouts',()=>modal(names[gateway]+' checkout availability',[
        {key:'enabled',label:'New customer checkouts',value:c.checkout_enabled===false?'on':'off',options:[['off','Disabled — keep saved connection'],['on','Enabled']],hint:'Existing keys and routing stay saved. Customers fall back to the enabled country default immediately. Old external links are not cancelled; reconcile late funds without another automatic renewal.'}
      ],v=>api('/api/renewals/gateway-availability',{tenant_id:tenant,gateway,connection_id:c.id,expected_revision:c.availability_revision??0,enabled:v.enabled==='on'}))),button('Replace '+names[gateway]+' connection',connect));gatewayBox.append(details);
    }
    gateways.append(helpDetails('Connection privacy and payment verification','API keys and webhook secrets remain write-only and encrypted. Each reseller uses their own merchant. Stripe/Square use direct card checkout. Sandbox payments never reactivate real lines; a connected account alone is not proof that a payment was received.'));
    const routing=panel('Country routing');settings.append(routing);
    for(const country of ['US','CA']){const rule=data.routing?.[country]||{gateways:country==='CA'?['stripe','square']:['stripe','square','rampex'],revision:0};const row=el('div',null,'summary-row'),text=el('div');text.append(el('h3',country==='CA'?'Canada':'United States'),el('p',rule.gateways.map(g=>names[g]).join(' → ')||'Disabled'));row.append(text);
      if(!data.catalogue_only)row.append(button('Edit '+(country==='CA'?'Canada':'USA')+' gateways',()=>modal('Payment gateways · '+country,[
        {key:'preferred',label:'Preferred gateway',value:rule.gateways[0]||'none',options:[['none','Disabled'],...Object.keys(names).filter(g=>country!=='CA'||g!=='rampex').map(g=>[g,names[g]])]},
        {key:'second',label:'First alternative',value:rule.gateways[1]||'none',options:[['none','None'],...Object.keys(names).filter(g=>country!=='CA'||g!=='rampex').map(g=>[g,names[g]])]},
        {key:'third',label:'Second alternative',value:rule.gateways[2]||'none',options:[['none','None'],...Object.keys(names).filter(g=>country!=='CA'||g!=='rampex').map(g=>[g,names[g]])]}
      ],v=>api('/api/renewals/routing',{tenant_id:tenant,country,expected_revision:rule.revision,gateways:v.preferred!=='none'?[...new Set([v.preferred,v.second,v.third].filter(g=>g!=='none'))]:[]}))));routing.append(row);
    }
    settings.append(helpDetails('Routing and switching','Only enabled, configured providers matching the country, currency and app environment are offered. Canada never uses Rampex. Switching is immediate; older unpaid attempts remain for reconciliation. Old external links may still accept money.'));
    if(state.user.role==='owner'&&!data.catalogue_only){const advanced=disclosure('Owner rollout controls',el('p',data.policy.enabled?(data.policy.testing_only?'Payments enabled for testing apps only.':'Payments enabled for testing and production apps.'):'New payment checkout is paused.','hint'),button('Rollout policy',()=>modal('Renewal payment rollout',[
      {key:'enabled',label:'Payments',value:data.policy.enabled?'yes':'no',options:[['no','Off'],['yes','On']]},
      {key:'testing_only',label:'Eligible branded apps',value:data.policy.testing_only?'yes':'no',options:[['yes','Testing branded apps only'],['no','Testing and production branded apps']]}
    ],v=>api('/api/renewals/policy',{enabled:v.enabled==='yes',testing_only:v.testing_only==='yes'}))));settings.append(advanced);}
  }catch(e){if(valid()){loading.remove();main.append(panel('Renewal workspace unavailable',el('p',e.message,'error'),el('p','Select an active workspace with a verified panel reseller link. No payment or account change was requested.','hint'),button('Retry',route)));}}
}};
