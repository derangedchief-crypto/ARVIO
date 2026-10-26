(function(root){
  'use strict';
  const drafts=new Map();
  const terms=p=>`${p.duration} ${p.period} · ${p.max_connections} connection(s) · ${p.credits} credits`;
  async function render(epoch,hint){
    if(root.TrialSelfService){const model=await api('/api/trial-setup/model');if(state.epoch!==epoch||!state.user)return;if(model.global)return root.TrialSelfService.render(epoch,hint);}
    const valid=()=>state.epoch===epoch&&!!state.user,main=$('main');
    main.replaceChildren(heading('Trials & contacts','Allow trials, choose a package, and manage follow-up contacts.'));
    await root.TrialWorkspace.globalControls(main,epoch);if(!valid())return;
    if(hint){await chooseWorkspace(hint);if(!valid())return;}
    const tenant=state.user.role==='owner'?toolWorkspace():state.user.tenant_id;
    if(state.user.role==='owner')workspacePicker(main,'Trial workspace',tenant,()=>{if(hint)location.hash='trials';else route();});workspaceTitle(tenant);
    if(!tenant){main.append(el('p','Choose a workspace first.','hint'));return;}
    let setup;try{setup=await api('/api/trial-setup?tenant_id='+tenant);}catch(e){if(valid())main.append(panel('Trial settings unavailable',el('p',e.message,'error'),button('Reload status',()=>route())));return;}if(!valid())return;
    const status=panel(setup.status);status.setAttribute('aria-label','Trial status');
    if(setup.blockers.length){const list=el('ul');for(const reason of setup.blockers)list.append(el('li',reason));status.append(list);}
    else status.append(el('p',setup.active?'Eligible customers can request the selected trial in your app.':!setup.global_enabled?'The admin has paused new trials. Your app setting is saved.':'Choose your package and switch trials on below.','hint'));
    status.append(button('Reload status',()=>route(),'quiet'));main.append(status);
    let d=drafts.get(tenant);if(!d||(!d.adminDirty&&!d.appDirty)){d={allowed:!!setup.admin?.allowed,ids:[...(setup.admin?.allowed_plan_ids||[])],daily:setup.admin?.daily_limit??50,plan:String(setup.settings.plan_id??''),enabled:setup.settings.enabled,adminDirty:false,appDirty:false};drafts.set(tenant,d);}
    const feedback=el('p',null,'error');feedback.hidden=true;feedback.setAttribute('role','alert');main.append(feedback);
    main.append(button('Discard edits',()=>{drafts.delete(tenant);route();},'quiet'));
    async function save(path,body,area,after){feedback.hidden=true;area.inert=true;try{await api(path,body);if(valid()){after();toast('Trial settings saved. No trial account was created.');await route();}}catch(e){if(valid()){feedback.textContent=e.message;feedback.hidden=false;feedback.scrollIntoView({block:'nearest'});}}finally{area.inert=false;}}
    if(setup.admin){
      const access=panel('Trial access',el('p',setup.owner_unlimited?'Your owner app has unlimited eligible trials per day.':'Choose which packages this reseller can offer. Saving does not create trial accounts.','hint'));
      if(!setup.owner_unlimited){const allow=el('input');allow.type='checkbox';allow.setAttribute('role','switch');allow.setAttribute('aria-label','Allow trials for this reseller');allow.checked=d.allowed;allow.onchange=()=>{d.allowed=allow.checked;d.adminDirty=true;};const row=el('label',null,'trial-option');row.append(allow,el('span','Allow trials'));access.append(row);}
      for(const p of setup.admin.packages){const check=el('input');check.type='checkbox';check.checked=d.ids.includes(p.id);check.setAttribute('aria-label','Allow '+p.name);check.onchange=()=>{d.ids=check.checked?[...new Set([...d.ids,p.id])]:d.ids.filter(id=>id!==p.id);d.adminDirty=true;};const row=el('label',null,'trial-option');row.append(check,cellName(p.name,terms(p)));access.append(row);}
      if(!setup.admin.packages.length)access.append(el('p','No current trial packages are available. Check the status above, then reload.','hint'));
      let daily=null;if(!setup.owner_unlimited){daily=el('input');daily.type='number';daily.min='1';daily.max='1000';daily.step='1';daily.value=d.daily;daily.setAttribute('aria-label','Trials per day');daily.oninput=()=>{d.daily=daily.value;d.adminDirty=true;};const label=el('label','Trials per day');label.append(daily);access.append(label);}
      access.append(button('Save trial access',async()=>{const limit=setup.owner_unlimited?50:Number(daily.value);if(d.allowed&&(!Number.isSafeInteger(limit)||limit<1||limit>1000)){feedback.textContent='Enter a whole-number daily limit from 1 to 1000.';feedback.hidden=false;return;}const fingerprints={};for(const p of setup.admin.packages)if(d.ids.includes(p.id))fingerprints[p.id]=p.fingerprint;
        await save('/api/trial-setup/admin',{tenant_id:tenant,expected_revision:setup.admin.revision,allowed:setup.owner_unlimited?true:d.allowed,allowed_plan_ids:d.ids,plan_fingerprints:fingerprints,daily_limit:limit},access,()=>{d.adminDirty=false;if(!d.appDirty)drafts.delete(tenant);});
      },'primary'));main.append(access);
    }
    const app=panel(setup.workspace_kind==='owner'?'Owner app trials':'App trials');
    const choose=el('select');choose.setAttribute('aria-label','Trial package');const blank=el('option','Choose an approved package');blank.value='';choose.append(blank);for(const p of setup.plans){const option=el('option',p.name+' · '+terms(p));option.value=p.id;choose.append(option);}choose.value=d.plan;choose.onchange=()=>{d.plan=choose.value;d.appDirty=true;};const planLabel=el('label','Trial package');planLabel.append(choose);app.append(planLabel);
    const show=el('input');show.type='checkbox';show.setAttribute('role','switch');show.setAttribute('aria-label','Show trial button in this app');show.checked=d.enabled;show.disabled=!setup.admin_allowed&&!d.enabled;show.onchange=()=>{d.enabled=show.checked;d.appDirty=true;};const showLabel=el('label',null,'trial-option');showLabel.append(show,el('span','Show trial button in this app'));app.append(showLabel);
    app.append(el('p',setup.owner_unlimited?'Unlimited eligible trials per day.':`Admin daily limit: ${setup.daily_limit??'not set'}.`,'hint'),el('p','Customers share their first name and phone number with this reseller for setup and follow-up. No SMS verification. Existing accounts and viewing are unchanged when trials are switched off.','hint'));
    if(!setup.global_enabled)app.append(el('p','Paused by admin. You can save your preference, but new trials remain paused.','notice'));
    app.append(button('Save trial settings',async()=>{
      if(d.adminDirty){feedback.textContent='Save trial access above before saving app settings.';feedback.hidden=false;return;}
      const body={tenant_id:tenant,expected_default_revision:setup.settings.default_revision,expected_offer_revision:setup.settings.offer_revision,enabled:d.enabled,confirmed_public_mode:d.enabled};
      if(d.enabled||(!setup.settings.enabled&&String(setup.settings.plan_id??'')!==d.plan)){const p=setup.plans.find(p=>p.id===Number(d.plan));if(!p){feedback.textContent='Choose an approved trial package first.';feedback.hidden=false;return;}body.plan_id=p.id;body.plan_fingerprint=p.fingerprint;}
      await save('/api/trial-setup/app',body,app,()=>{drafts.delete(tenant);});
    },'primary'));main.append(app);
    await root.TrialWorkspace.renderContacts(main,tenant,valid,()=>setup.worker_configured);
  }
  root.SimpleTrialSetup={render,isDirty:()=>[...drafts.values()].some(d=>d.adminDirty||d.appDirty)||!!root.TrialSelfService?.isDirty(),clear:()=>{drafts.clear();root.TrialSelfService?.clear();}};
})(window);
