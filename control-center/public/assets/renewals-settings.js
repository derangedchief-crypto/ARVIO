'use strict';
window.Renewals={async render(epoch){
  const main=$('main');main.replaceChildren(heading('Customer renewals','Reseller-branded apps only. Payment verification and line renewal are separate steps.',[button('Refresh',route)]));
  let tenant=toolWorkspace();workspacePicker(main,'Payment workspace',tenant,async id=>{await chooseWorkspace(id);route();},{activeOnly:true});
  const box=panel('Rampex connection');main.append(box);
  try{
    const data=await api('/api/renewals/settings?tenant_id='+encodeURIComponent(tenant));if(epoch!==state.epoch)return;
    const connection=data.connection;box.append(el('p',connection?`${connection.business_name} · ${connection.merchant_id} · ${connection.mode==='mock'?'DEMO ONLY':'Live merchant'}`:'No merchant connected. Each reseller uses its own account.'));
    box.append(el('p','API key and webhook secret are write-only and encrypted. Replacing a connection preserves its old orders.','scope-label'));
    if(connection){box.append(el('p','Configure this webhook URL in this merchant’s Rampex settings:'),el('code',connection.webhook_url,'brand-download'));}
    box.append(button(connection?'Replace merchant connection':'Connect Rampex',()=>modal('Rampex connection · '+(state.tenants.find(t=>t.id===tenant)?.name||'Your workspace'),[
      {key:'mode',label:'Mode',value:data.simulation_allowed?'mock':'live',options:data.simulation_allowed?[['mock','DEMO — isolated simulator'],['live','Live — requires separate operator approval']]:[['live','Live — requires operator approval']]},
      {key:'api_key',label:'Rampex API key',type:'password',max:512,hint:data.simulation_allowed?'Use a synthetic mock_ key for the simulator. A real development-labelled Rampex key can still use real money.':'Never put merchant keys in the TV app.'},
      {key:'webhook_secret',label:'Webhook secret',type:'password',max:512}
    ],v=>api('/api/renewals/connection',{...v,tenant_id:tenant}))));
    if(state.user.role==='owner')box.append(button('Rollout policy',()=>modal('Renewal payment rollout',[{key:'enabled',label:'Payments',value:data.policy.enabled?'yes':'no',options:[['no','Off'],['yes','On']]},{key:'testing_only',label:'Eligible branded apps',value:data.policy.testing_only?'yes':'no',options:[['yes','Testing branded apps only'],['no','Testing and production branded apps']]}],v=>api('/api/renewals/policy',{enabled:v.enabled==='yes',testing_only:v.testing_only==='yes'}))));
    const prices=panel('Package pricing',el('p','Packages, terms, connections and credit costs come from your provider’s panel. Set only the money price customers pay. Each customer renews their current package—no package changes.','scope-label'));main.append(prices);
    function priceForm(pkg,old){
      modal(pkg.name+' — customer price',[
        {key:'amount',label:'Customer renewal price',value:old?(old.amount_minor/100).toFixed(2):'',hint:'This is the customer price, not the reseller panel-credit cost.'},
        {key:'currency',label:'Currency',value:old?.currency||'USD',options:['USD','CAD','EUR','GBP','AUD'].map(c=>[c,c])},
        {key:'enabled',label:'Available for checkout',value:old?.enabled===false?'no':'yes',options:[['yes','Yes'],['no','No']]}
      ],v=>api('/api/renewals/prices',{...v,tenant_id:tenant,id:old?.id||'',expected_revision:old?.revision||0,package_id:pkg.id,enabled:v.enabled==='yes'}));
    }
    try{
      const catalog=await api('/api/renewals/catalog?tenant_id='+encodeURIComponent(tenant));if(epoch!==state.epoch)return;
      prices.append(table(['Offered package','Panel credits','Customer price','Actions'],catalog.packages.map(pkg=>{
        const saved=data.prices.filter(p=>p.package_id===pkg.id),old=saved[0];
        const action=saved.length>1?el('span','Duplicate prices need owner review'):button(old?'Edit price':'Set price',()=>priceForm(pkg,old));
        return [cellName(pkg.name,`${pkg.duration} ${pkg.period} · ${pkg.max_connections} connection(s)`),pkg.credits??'—',old?(old.amount_minor/100).toFixed(2)+' '+old.currency+(old.enabled?'':' · off'):'Not set',action];
      })));
      if(!catalog.packages.length)prices.append(el('p','No offered paid packages are available. Contact your provider.'));
    }catch(error){prices.append(el('p','Offered packages unavailable: '+error.message,'error'));}
    const orders=await api('/api/renewals/orders?tenant_id='+encodeURIComponent(tenant));if(epoch!==state.epoch)return;
    main.append(panel('Recent renewal orders',el('p','Up to 50 recent orders. A paid order is never charged again when fulfilment needs repair.','scope-label'),table(['Order','Payment','Renewal','Actions'],orders.items.map(o=>{
      const actions=el('div',null,'actions');
      if(o.mode==='mock'&&orders.simulation_allowed)actions.append(button('Simulate payment',async()=>{await api('/api/renewals/orders/'+o.id+'/simulate',{});if(state.user.role==='owner')await api('/api/renewals/process-demo',{});route();}));
      if(o.payment_state==='paid'&&o.fulfilment_state!=='fulfilled')actions.append(button('Retry same renewal',async()=>{await api('/api/renewals/orders/'+o.id+'/retry',{});toast('Same order queued. No new charge.');route();}));
      return [cellName(o.id,(o.amount_minor/100).toFixed(2)+' '+o.currency),o.payment_state,o.fulfilment_state,actions];
    }))));
  }catch(error){if(epoch===state.epoch)box.replaceChildren(el('p',error.message,'error'),el('p','Select an active workspace with a verified panel reseller link. Your owner workspace is supported when linked. No payment or account change has been requested.','scope-label'));}
}};
