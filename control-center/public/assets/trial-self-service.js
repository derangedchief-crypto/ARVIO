(function(root){
  'use strict';
  const drafts=new Map();let globalDraft=null;
  const terms=p=>`${p.duration} ${p.period} · ${p.max_connections} connection(s) · ${p.credits} credits`;
  async function render(epoch,hint){
    const valid=()=>state.epoch===epoch&&!!state.user,main=$('main'),contactsOnly=location.hash.endsWith('/contacts');main.replaceChildren(heading('Trials & contacts',''));
    const base='trials'+(hint?'/'+hint:'');const tabs=el('nav',null,'trial-sections');tabs.setAttribute('aria-label','Trial sections');for(const[label,hash,selected]of [['Trial settings',base,!contactsOnly],['Contacts',base+'/contacts',contactsOnly]]){const a=el('a',label);a.href='#'+hash;if(selected){a.className='active';a.setAttribute('aria-current','page');}tabs.append(a);}main.append(tabs);
    if(!contactsOnly)await root.TrialWorkspace.globalControls(main,epoch,true);if(!valid())return;
    if(state.user.role==='owner'&&!contactsOnly){
      let packages;try{packages=await api('/api/trial-setup/packages');}catch(e){if(valid())main.append(panel('Global packages unavailable',el('p',e.message,'error')));}if(!valid())return;
      if(packages){
        if(!globalDraft?.dirty)globalDraft={ids:[...packages.allowed_plan_ids],revision:packages.revision,dirty:false};const d=globalDraft;
        const box=panel('Global approved trial packages');
        for(const p of packages.packages){const check=el('input');check.type='checkbox';check.checked=d.ids.includes(p.id);check.setAttribute('aria-label','Globally approve '+p.name);check.onchange=()=>{d.ids=check.checked?[...new Set([...d.ids,p.id])]:d.ids.filter(id=>id!==p.id);d.dirty=true;};const row=el('label',null,'trial-option');row.append(check,cellName(p.name,terms(p)));box.append(row);}
        if(!packages.catalog_fresh)box.append(el('p','Current packages could not be checked. Reload shortly; the global pause switch still works.','notice'));
        const feedback=el('p',null,'error');feedback.hidden=true;feedback.setAttribute('role','alert');box.append(feedback);
        if(d.dirty&&d.revision!==packages.revision){feedback.textContent='Global approvals changed elsewhere. Discard package edits and review the latest settings.';feedback.hidden=false;}
        box.append(button('Save global packages',async()=>{const fp={};for(const p of packages.packages)if(d.ids.includes(p.id))fp[p.id]=p.fingerprint;box.inert=true;feedback.hidden=true;try{await api('/api/trial-setup/packages',{expected_revision:d.revision,allowed_plan_ids:d.ids,plan_fingerprints:fp});if(valid()){globalDraft=null;toast('Global packages saved. Resellers’ app switches were kept unchanged.');await route();}}catch(e){if(valid()){feedback.textContent=e.message;feedback.hidden=false;}}finally{box.inert=false;}},'primary'),button('Discard package edits',()=>{globalDraft=null;route();},'quiet'));main.append(box);
      }
    }
    if(hint){await chooseWorkspace(hint);if(!valid())return;}
    const tenant=state.user.role==='owner'?toolWorkspace():state.user.tenant_id;
    if(state.user.role==='owner')workspacePicker(main,'Trial workspace',tenant,()=>{if(hint)location.hash=contactsOnly?'trials/contacts':'trials';else route();});workspaceTitle(tenant);
    if(!tenant){main.append(el('p','Choose a workspace to view its app and contacts.','hint'));return;}
    if(contactsOnly){let worker=false;try{worker=(await api('/api/trial-policy?tenant_id='+tenant)).worker_configured;}catch{/* Contacts remain readable even if panel-policy setup is unavailable. */}if(valid())await root.TrialWorkspace.renderContacts(main,tenant,valid,()=>worker);return;}
    let setup;try{setup=await api('/api/trial-setup?tenant_id='+tenant);}catch(e){if(valid())main.append(panel('Trial settings unavailable',el('p',e.message,'error'),button('Reload status',()=>route())));return;}if(!valid())return;
    const status=panel(setup.status);status.setAttribute('aria-label','Trial status');if(setup.blockers.length){const ul=el('ul');for(const text of setup.blockers)ul.append(el('li',text));status.append(ul);}status.append(button('Reload status',()=>route(),'quiet'));main.append(status);
    const readOnly=state.user.role==='owner'&&setup.workspace_kind==='reseller';
    const app=panel(setup.workspace_kind==='owner'?'Owner app trials':'App trial settings');
    if(readOnly){const p=setup.plans.find(p=>p.id===setup.settings.plan_id);app.append(el('p','Managed by reseller','scope-label'),detailRows([['Package',p?.name||'Not selected'],['Daily limit',setup.settings.daily_limit],['App switch',setup.settings.enabled?'On':'Off']]));}
    else {
      let d=drafts.get(tenant);if(!d?.dirty){d={plan:String(setup.settings.plan_id??''),daily:setup.settings.daily_limit??50,enabled:setup.settings.enabled,defaultRevision:setup.settings.default_revision,offerRevision:setup.settings.offer_revision,dirty:false};drafts.set(tenant,d);}
      const choose=el('select');choose.setAttribute('aria-label','Trial package');const blank=el('option','Choose a globally approved package');blank.value='';choose.append(blank);for(const p of setup.plans){const option=el('option',p.name+' · '+terms(p));option.value=p.id;choose.append(option);}choose.value=d.plan;choose.onchange=()=>{d.plan=choose.value;d.dirty=true;};const label=el('label','Trial package');label.append(choose);app.append(label);
      let daily=null;if(setup.owner_unlimited)app.append(el('p','Unlimited eligible trials per day.','notice'));else{daily=el('input');daily.type='number';daily.min='1';daily.max=setup.daily_max;daily.step='1';daily.value=d.daily;daily.setAttribute('aria-label','My daily trial limit');daily.oninput=()=>{d.daily=daily.value;d.dirty=true;};const limit=el('label',`My daily trial limit (1–${setup.daily_max})`);limit.append(daily);app.append(limit);}
      const show=el('input');show.type='checkbox';show.setAttribute('role','switch');show.setAttribute('aria-label','Show trial button in this app');show.checked=d.enabled;show.onchange=()=>{d.enabled=show.checked;d.dirty=true;};const row=el('label',null,'trial-option');row.append(show,el('span','Show trial button in this app'));app.append(row);
      app.append(el('p','Signups collect first name and phone for follow-up. No SMS verification.','consent-note'));
      if(!setup.global_enabled)app.append(el('p','Trials are paused globally. Your chosen settings are retained.','notice'));
      const feedback=el('p',null,'error');feedback.hidden=true;feedback.setAttribute('role','alert');app.append(feedback);
      if(d.dirty&&(d.defaultRevision!==setup.settings.default_revision||d.offerRevision!==setup.settings.offer_revision)){feedback.textContent='App settings changed elsewhere. Discard app edits and review the latest settings.';feedback.hidden=false;}
      app.append(button('Save my trial settings',async()=>{feedback.hidden=true;const pause=setup.settings.enabled&&!d.enabled;const body={tenant_id:tenant,expected_default_revision:d.defaultRevision,expected_offer_revision:d.offerRevision,enabled:d.enabled,confirmed_public_mode:d.enabled};
        if(!pause&&(d.enabled||String(setup.settings.plan_id??'')!==d.plan||String(setup.settings.daily_limit??50)!==String(d.daily))){const p=setup.plans.find(p=>p.id===Number(d.plan));if(!p){feedback.textContent='Choose a globally approved trial package first.';feedback.hidden=false;return;}body.plan_id=p.id;body.plan_fingerprint=p.fingerprint;if(!setup.owner_unlimited){const n=Number(daily.value);if(!Number.isSafeInteger(n)||n<1||n>setup.daily_max){feedback.textContent=`Choose a whole-number daily limit from 1 to ${setup.daily_max}.`;feedback.hidden=false;return;}body.daily_limit=n;}}
        app.inert=true;try{await api('/api/trial-setup/app',body);if(valid()){drafts.delete(tenant);toast('Your trial settings were saved. No trial account was created.');await route();}}catch(e){if(valid()){feedback.textContent=e.message;feedback.hidden=false;}}finally{app.inert=false;}
      },'primary'),button('Discard app edits',()=>{drafts.delete(tenant);route();},'quiet'));
    }
    main.append(app);
  }
  root.TrialSelfService={render,isDirty:()=>!!globalDraft?.dirty||[...drafts.values()].some(d=>d.dirty),clear:()=>{drafts.clear();globalDraft=null;}};
})(window);
