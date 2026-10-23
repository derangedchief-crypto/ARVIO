'use strict';

const $ = id => document.getElementById(id);
const state = { user: null, csrf: '', tenants: [], epoch: 0, deviceTab: null, layoutEditors: new Map(), favouritesSelection: new Map(), sourceEditors: new Map(), preferenceEditors: new Map() };
const requestKeys = new Map();
let toastTimer;
let formCounter = 0;
const iconPaths={overview:'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',devices:'M4 4h16v12H4z M8 20h8 M12 16v4',customers:'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M9 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M22 21v-2a4 4 0 0 0-3-3.87 M16 3.13a4 4 0 0 1 0 7.75',presets:'M4 7h16 M4 17h16 M8 4v6 M16 14v6',resellers:'M3 21h18 M5 21V7l7-4 7 4v14 M9 9h1 M14 9h1 M9 13h1 M14 13h1 M10 21v-4h4v4',enrollments:'M12 3l8 4v5c0 5-8 9-8 9s-8-4-8-9V7z M8 12l3 3 5-6',audit:'M8 3h8v4H8z M8 5H5v16h14V5h-3 M8 11h8 M8 15h8',sun:'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M12 2v2 M12 20v2 M2 12h2 M20 12h2 M5 5l1.5 1.5 M17.5 17.5L19 19 M5 19l1.5-1.5 M17.5 6.5L19 5',moon:'M21 13a9 9 0 0 1-10-10 9 9 0 1 0 10 10',menu:'M4 6h16 M4 12h16 M4 18h16',close:'M6 6l12 12 M18 6 6 18',key:'M14 4a6 6 0 1 1-3 11L5 21H2v-3l7-7a6 6 0 0 1 5-7z M16 8h.01',logout:'M9 4H4v16h5 M14 8l4 4-4 4 M8 12h12'};
function icon(name){const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.classList.add('icon');svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('aria-hidden','true');svg.setAttribute('focusable','false');const path=document.createElementNS(svg.namespaceURI,'path');path.setAttribute('d',iconPaths[name]||iconPaths.overview);svg.append(path);return svg;}
iconPaths.library='M4 3h6v18H4z M14 3h6v18h-6z M4 7h6 M14 7h6';iconPaths.more='M5 12h.01 M12 12h.01 M19 12h.01';
iconPaths.layouts='M8 6h12 M8 12h12 M8 18h12 M3 6h1 M3 12h1 M3 18h1';
iconPaths.profiles='M5 4h14v16H5z M9 8h6 M9 12h6 M9 16h3';
iconPaths.defaults='M12 3v4 M12 17v4 M3 12h4 M17 12h4 M8 12l3 3 5-6';
iconPaths.editor='M14 4h6v6 M20 4l-9 9 M10 5H4v15h15v-6';
iconPaths.refresh='M20 7v5h-5 M4 17v-5h5 M6 7a7 7 0 0 1 12-1l2 6 M4 12l2 6a7 7 0 0 0 12-1';
const drawerMedia=window.matchMedia('(max-width:900px)');let navOpen=false;
function setNavOpen(open,restoreFocus=true){navOpen=!!open&&drawerMedia.matches&&!$('shell').hidden;document.body.classList.toggle('nav-open',navOpen);$('nav-toggle').setAttribute('aria-expanded',String(navOpen));$('nav-toggle').setAttribute('aria-label',navOpen?'Close navigation':'Open navigation');$('nav-scrim').hidden=!navOpen;$('workspace').inert=navOpen;$('sidebar').inert=drawerMedia.matches&&!navOpen;if(drawerMedia.matches){$('sidebar').setAttribute('aria-hidden',String(!navOpen));if(navOpen){$('sidebar').setAttribute('role','dialog');$('sidebar').setAttribute('aria-modal','true');$('nav-close').focus();}else{$('sidebar').removeAttribute('role');$('sidebar').removeAttribute('aria-modal');if(restoreFocus&&!$('shell').hidden)$('nav-toggle').focus();}}else{$('sidebar').removeAttribute('aria-hidden');$('sidebar').removeAttribute('role');$('sidebar').removeAttribute('aria-modal');}}
function applyTheme(theme){document.documentElement.dataset.theme=theme;const next=theme==='dark'?'light':'dark';$('theme-toggle').replaceChildren(icon(next==='light'?'sun':'moon'),el('span',next==='light'?'Light':'Dark','theme-text'));$('theme-toggle').setAttribute('aria-label','Switch to '+next+' theme');document.querySelector('meta[name="theme-color"]').setAttribute('content',theme==='light'?'#f6f7fa':'#111724');}
let savedTheme='light';try{const stored=localStorage.getItem('extreme-control-theme');if(['light','dark'].includes(stored))savedTheme=stored;}catch{}applyTheme(savedTheme);
$('theme-toggle').addEventListener('click',()=>{const next=document.documentElement.dataset.theme==='light'?'dark':'light';applyTheme(next);try{localStorage.setItem('extreme-control-theme',next);}catch{}});
$('nav-toggle').replaceChildren(icon('menu'));$('nav-close').replaceChildren(icon('close'));$('password-button').prepend(icon('key'));$('logout-button').prepend(icon('logout'));
$('nav-toggle').addEventListener('click',()=>setNavOpen(!navOpen));$('nav-close').addEventListener('click',()=>setNavOpen(false));$('nav-scrim').addEventListener('click',()=>setNavOpen(false));drawerMedia.addEventListener('change',()=>setNavOpen(false,false));setNavOpen(false,false);
$('sidebar').addEventListener('click',event=>{if(event.target.closest('a')){const wasOpen=navOpen;setNavOpen(false,false);if(wasOpen)$('main').focus({preventScroll:true});}});
document.querySelector('.skip-link').addEventListener('click',event=>{event.preventDefault();$('main').focus();$('main').scrollIntoView({block:'start'});});
document.addEventListener('keydown',event=>{if(!navOpen)return;if(event.key==='Escape'){event.preventDefault();setNavOpen(false);}else if(event.key==='Tab'){const focusable=[...$('sidebar').querySelectorAll('a,button')].filter(n=>!n.disabled&&n.getClientRects().length);const first=focusable[0],last=focusable.at(-1);if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus();}}});
function el(tag, text, className = '') { const n = document.createElement(tag); if (text !== null && text !== undefined) n.textContent = String(text); if (className) n.className = className; return n; }
function button(text, action, className = '') { const n = el('button', text, className); n.type = 'button'; n.addEventListener('click', event=>{try{Promise.resolve(action.call(n,event)).catch(error=>toast(error.message||'The action could not complete.'));}catch(error){toast(error.message||'The action could not complete.');}}); return n; }
function toast(message) { $('toast').textContent = message; $('toast').hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => { $('toast').hidden = true; }, 6000); }
function badge(value) { return el('span', String(value).replaceAll('_', ' '), 'badge ' + String(value).replace(/[^a-z_]/g, '')); }
function date(value) { return value ? new Date(value.replace(' ', 'T') + 'Z').toLocaleString() : 'Never'; }
function uuid() { return crypto.randomUUID ? crypto.randomUUID() : Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join(''); }
async function api(path, body, extra = {}) {
  const sessionUser = state.user?.id;
  const options = { method: body === undefined ? 'GET' : 'POST', credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json', ...extra } };
  if (body !== undefined) { options.headers['Content-Type'] = 'application/json'; options.headers['X-CSRF-Token'] = state.csrf; options.body = JSON.stringify(body); }
  const response = await fetch(path, options);
  let data;
  try { data = await response.json(); } catch { throw new Error('Server response was not JSON. Check the PHP/Nginx configuration.'); }
  if (!response.ok) {
    if (response.status === 401 && path !== '/api/login' && sessionUser === state.user?.id) signedOut();
    const error = new Error((data.error || 'Request failed.') + (data.request_id ? ' Request ID: ' + data.request_id : '')); error.status = response.status; throw error;
  }
  return data;
}
function panel(title, ...children) { const n = el('section', null, 'panel'); if (title) n.append(el('h2', title)); n.append(...children); return n; }
function heading(title, description, actions = []) { const h = el('div', null, 'page-heading'); const left = el('div'); left.append(el('h1', title), el('p', description)); const right = el('div', null, 'actions'); right.append(...actions); h.append(left, right); return h; }
function cellName(title, subtitle) { const n = el('div'); n.append(el('span', title, 'cell-name')); if (subtitle) n.append(el('span', subtitle, 'cell-sub')); return n; }
function table(headers, rows) {
  const wrap = el('div', null, 'table-scroll');const cards=['Device','Line username / customer','Workspace','Action','Settings profile','Position','Layout','Profile','Device / recipe'].includes(headers[0]);if(cards)wrap.classList.add('mobile-records');wrap.tabIndex=0;wrap.setAttribute('role','region');wrap.setAttribute('aria-label','Data table: '+headers.filter(Boolean).join(', '));const t = el('table');t.setAttribute('role','table');const head = el('thead'); const hr = el('tr'); headers.forEach(h => {const th=el('th',h);th.scope='col';hr.append(th);}); head.append(hr); t.append(head);
  const body = el('tbody'); rows.forEach(cells => { const row = el('tr');row.setAttribute('role','row'); cells.forEach((value,index) => { const td = el('td');td.setAttribute('role','cell');td.dataset.label=headers[index]||'Actions'; td.append(value instanceof Node ? value : document.createTextNode(String(value ?? '—'))); row.append(td); }); body.append(row); });
  if (!rows.length) { const row = el('tr'); const td = el('td', 'No records yet.', 'empty'); td.colSpan = headers.length; row.append(td); body.append(row); }
  t.append(body); wrap.append(t); return wrap;
}
function pagination(result, load) { const n = el('div', null, 'pagination'); const previous = button('← Previous', () => load(result.page - 1)); previous.disabled = result.page <= 1; const next = button('Next →', () => load(result.page + 1)); next.disabled = result.page >= result.pages; n.append(el('span', `${result.total} records · Page ${result.page} of ${result.pages}`), previous, next); return n; }
function fields(form, schema) {
  const prefix = 'control-form-' + (++formCounter) + '-';
  for (const field of schema) {
    const label = el('label', field.label); label.htmlFor = prefix + field.key;
    const input = document.createElement(field.type === 'textarea' ? 'textarea' : field.options ? 'select' : 'input'); input.id = label.htmlFor; input.name = field.key;
    if (input.tagName === 'INPUT') input.type = field.type || 'text';
    if (field.type === 'password') { input.autocomplete = field.key === 'current_password' ? 'current-password' : 'new-password'; input.minLength = field.min ?? 14; input.maxLength = field.max ?? 72; }
    else if (input.tagName !== 'SELECT') input.maxLength = field.max || (field.type === 'textarea' ? 2000 : 120);
    if (field.type === 'number') { input.min = 1; input.max = 100000; input.step = 1; }
    if (field.options) for (const [value, name] of field.options) { const option = el('option', name); option.value = value; input.append(option); }
    input.required = field.required !== false; if (field.value !== undefined && field.value !== null) input.value = field.value;
    form.append(label, input); if (field.hint) form.append(el('p', field.hint, 'hint'));
  }
}
function formValues(form, schema) { const values = {}; for (const f of schema) values[f.key] = f.type === 'number' ? Number(form.elements.namedItem(f.key).value) : form.elements.namedItem(f.key).value; return values; }
function closeModal(){ $('modal-form').querySelectorAll('input[type="password"],textarea[name="guides"]').forEach(input=>{input.value='';});$('modal').close(); }
function modal(title, schema, save, label = 'Save') {
  $('modal-title').textContent = title; const form = $('modal-form'); form.replaceChildren(); fields(form, schema); const error = el('p', '', 'error'); const actions = el('div', null, 'form-actions'); const submit = el('button', label, 'primary'); submit.type = 'submit'; actions.append(button('Cancel', closeModal), submit); form.append(error, actions);
  const turn=formCounter,openedEpoch=state.epoch,openedUser=state.user?.id;
  form.onsubmit = async event => { event.preventDefault(); submit.disabled = true; error.textContent = ''; try { await save(formValues(form, schema)); if(turn!==formCounter||!$('modal').open||state.user?.id!==openedUser)return;closeModal();if(state.epoch!==openedEpoch)return;if(window.DeviceWorkspace?.isActive())await DeviceWorkspace.refresh();else await route(); } catch (e) { if(turn===formCounter&&$('modal').open)error.textContent = e.message;else if(state.user?.id===openedUser)toast(e.message); } finally { submit.disabled = false; } };
  $('modal').showModal();
}
$('modal').addEventListener('close',()=>{$('modal-form').querySelectorAll('input[type="password"], textarea[name="guides"]').forEach(input=>{input.value='';});});
function signedOut() { window.TrialWorkspace?.clear();window.AppBranding?.clear();window.DeviceWorkspace?.clear();window.WorkspaceLibrary?.clear();state.user = null; state.csrf = ''; state.tenants = []; state.deviceTab = null; state.layoutEditors.clear(); state.favouritesSelection.clear(); state.sourceEditors.clear(); state.preferenceEditors.clear(); state.epoch++; requestKeys.clear(); if ($('modal').open) $('modal').close(); $('modal-form').replaceChildren(); $('main').replaceChildren(); $('navigation').replaceChildren(); $('account-name').textContent = ''; $('workspace-name').textContent = ''; $('shell').hidden = true; $('entry').hidden = false; loginForm(); }
function entryCard(title, description) { const card = el('div', null, 'entry-card'); card.append(el('span', 'E', 'brand-mark'), el('h1', title), el('p', description, 'subtle')); $('entry').replaceChildren(card); return card; }
function loginForm() {
  setNavOpen(false,false);
  const card = entryCard('Welcome back', 'Sign in to your owner or reseller workspace.'); const form = el('form');
  const schema = [{ key: 'email', label: 'Email address', type: 'email', max: 254 }, { key: 'password', label: 'Password', type: 'password' }]; fields(form, schema); form.elements.password.autocomplete = 'current-password'; form.elements.password.removeAttribute('minlength');
  const error = el('p', '', 'error'); const submit = el('button', 'Sign in', 'primary'); submit.type = 'submit'; form.append(error, submit); card.append(form);
  form.onsubmit = async e => { e.preventDefault(); submit.disabled = true; try { const session = await api('/api/login', formValues(form, schema)); state.user = session.user; state.csrf = session.csrf; form.reset(); await enter(); } catch (e) { error.textContent = e.message; } finally { submit.disabled = false; } };
}
function setupForm() {
  const card = entryCard('Create your owner account', 'First installation only. There are no default passwords.');
  card.append(el('p', 'Find storage/setup.key in aaPanel File Manager, outside the public/ directory. Never share it or put it in a URL.', 'notice'));
  const schema = [{ key: 'setup_key', label: 'Private server setup key', type: 'password' }, { key: 'name', label: 'Your name' }, { key: 'email', label: 'Owner email', type: 'email', max: 254 }, { key: 'password', label: 'Owner password', type: 'password', hint: 'At least 14 characters. Save it in your password manager.' }];
  const form = el('form'); fields(form, schema); form.elements.setup_key.minLength = 64; const error = el('p', '', 'error'); const submit = el('button', 'Initialize control center', 'primary'); submit.type = 'submit'; form.append(error, submit); card.append(form);
  form.onsubmit = async e => { e.preventDefault(); submit.disabled = true; try { await api('/api/setup', formValues(form, schema)); form.reset(); loginForm(); toast('Owner account created. Sign in to continue.'); } catch (e) { error.textContent = e.message; } finally { submit.disabled = false; } };
}
async function enter() {
  state.tenants = (await api('/api/tenants')).items;
  $('entry').replaceChildren(); $('entry').hidden = true; $('shell').hidden = false; $('account-name').textContent = state.user.display_name;
  $('workspace-name').textContent = state.user.role === 'owner' ? 'Owner workspace · all resellers' : state.tenants[0]?.name || 'Reseller workspace';
  const nav = $('navigation'); nav.replaceChildren();
  const groups = [
    ['Overview', [['overview','Dashboard','overview']]],
    ['My clients', [['customers','Customers','customers'],['devices','Devices','devices'],['trials','Trials & contacts','profiles']]],
    ['Device setup', [['library','Layouts & playlists','layouts'],['library/settings','Settings profiles','presets'],['library/profiles','Device profiles','profiles'],['library/defaults','New-device defaults','defaults']]],
    ['Playlist editor', [['library/layout/new','Open template maker','editor']]],
    ...(state.user.role==='owner'?[['User management',[['resellers','Resellers & panel links','resellers'],['enrollments','Enrollments & upgrades','enrollments']]]]:[]),
    ['My app', [['branding','My app brand','profiles']]],
    ['Workspace', [['audit','Activity log','audit'],['more','More tools','more']]]
  ];
  groups.forEach(([title,links],index)=>{const group=el('section',null,'nav-group');const label=el('h2',title,'nav-group-title');label.id='nav-group-'+index;group.setAttribute('aria-labelledby',label.id);group.append(label);links.forEach(([key,text,glyph])=>{const link=el('a');link.append(icon(glyph),el('span',text));link.href='#'+key;link.dataset.page=key;group.append(link);});nav.append(group);});setNavOpen(false,false);
  await route();
}
function tenantField() { return { key: 'tenant_id', label: 'Workspace / reseller', options: state.tenants.filter(t => t.status === 'active').map(t => [t.id, t.name]), hint: 'First page of active workspaces. Customer/device enrollment is automatic after verified reseller linking.' }; }
function customerForm(customer, tenant = null) {
  const schema = [...(!customer && !tenant && state.user.role === 'owner' ? [tenantField()] : []), { key: 'name', label: 'Customer name', value: customer?.name }, { key: 'external_reference', label: 'Account reference (optional)', value: customer?.external_reference, required: false, max: 128, hint: 'A customer/billing reference—not a provider password.' }, { key: 'notes', label: 'Private notes (optional)', type: 'textarea', value: customer?.notes, required: false }];
  modal(customer ? 'Edit customer' : 'Add customer' + (tenant ? ' · ' + tenant.name : ''), schema, async values => { if (tenant) values.tenant_id = tenant.id; await api(customer ? '/api/customers/' + customer.id : '/api/customers', values); toast('Customer saved.'); });
}
function deviceForm(customer) { modal('Provision device record', [{ key: 'label', label: 'Device nickname' }, { key: 'notes', label: 'Private notes (optional)', type: 'textarea', required: false, hint: 'This creates a pending record only. It does not automatically pair an existing app.' }], async values => { await api('/api/devices', { ...values, customer_id: customer.id }); toast('Device record created; secure app enrollment is still pending.'); }); }
function resellerForm() { modal('Create reseller', [{ key: 'company', label: 'Company / workspace name' }, { key: 'panel_username', label: 'Existing panel reseller username', required: false, max: 150, hint: 'Exact panel login username. Verified server-side and permanently linked by ID. Leave blank only for a workspace without automatic enrollment.' }, { key: 'name', label: 'Contact name' }, { key: 'email', label: 'Reseller email', type: 'email', max: 254 }, { key: 'password', label: 'Initial password', type: 'password', hint: 'Share securely. The reseller can change this after signing in.' }, { key: 'device_limit', label: 'Device allocation', type: 'number', value: 100 }], async values => { await api('/api/resellers', values); state.tenants = (await api('/api/tenants')).items; toast('Reseller workspace created.'); }); }
function connectionBadge(device) { const n = el('div'); n.append(badge(device.presence)); if (device.is_test) n.append(el('span', 'TEST', 'test-label')); return n; }
function devicesTable(items) {
  const result=table(['Device','Customer','App access','Connection','Actions'],items.map(d=>{
    const device=el('div',null,'fleet-device');const glyph=icon('devices');device.append(glyph,cellName(d.label,[d.model,d.app_version].filter(Boolean).join(' · ')||'App details awaiting check-in'));
    const customer=el('div',null,'fleet-customer');customer.append(el('span',d.line_username||d.customer_name,'username-chip'),el('span',d.tenant_name,'cell-sub'));
    const connection=connectionBadge(d);connection.classList.add('fleet-connection');connection.title='Last app check-in: '+date(d.last_seen_at);connection.append(el('span',d.last_seen_at?'Seen '+date(d.last_seen_at):'Not checked in','cell-sub'));
    const actions=el('div',null,'fleet-actions');const refresh=button('',async()=>{if(!confirm('Request a playlist refresh on '+d.label+'? The app must be open. This queues a request; it does not confirm the refresh has completed.'))return;await queue(d,'refresh_playlist');},'quiet icon-button');refresh.append(icon('refresh'));refresh.setAttribute('aria-label','Refresh playlist for '+d.label);refresh.disabled=d.policy!=='active'||!d.enrolled_at||!d.capabilities.includes('refresh_playlist');refresh.title=refresh.disabled?'Requires active enrollment and a compatible app':'Request playlist refresh';
    actions.append(refresh,button('View →',()=>{location.hash='device/'+d.id;},'row-link'));return[device,customer,badge(d.policy),connection,actions];
  }));result.classList.add('device-records');result.querySelectorAll('tbody tr').forEach((row,i)=>{if(items[i])row.dataset.deviceId=items[i].id;});return result;
}
async function overview(epoch) {
  const [stats, devices] = await Promise.all([api('/api/overview'), api('/api/devices')]); if (epoch !== state.epoch) return;
  const main = $('main'); main.replaceChildren(heading('Operations overview', 'Your customers and devices, in one workspace.', [button('Refresh', route)]));
  const enrollmentNote=el('details',null,'context-note');enrollmentNote.append(el('summary','How enrollment and check-ins work'),el('p','Only devices from created, verified linked resellers enter this service. Unmatched resellers are quietly ignored: no pending device or customer is created. Existing owner-managed/unassigned inventory is retained. Local simulators remain labelled TEST.'));
  const cards = el('div', null, 'stats'); [['Managed devices', stats.total, `${stats.not_enrolled} awaiting enrollment`], ['Recently connected', stats.connected, 'App check-ins, not device power state'], ['Customers', stats.customers, 'Visible to your account only'], ['Queued / in progress', stats.pending_commands, 'Delivery is not confirmation of execution']].forEach(([label, count, note]) => { const card = el('div', null, 'stat'); card.append(el('div', label, 'stat-label'), el('strong', count), el('span', note)); cards.append(card); });
  const updateNote=el('section',null,'platform-note');const copy=el('div');copy.append(el('h2','One update source of truth'),el('p','Every customer and reseller app uses the platform owner’s update channel. Resellers cannot upload APKs, change update URLs or disable mandatory updates.'));updateNote.append(icon('enrollments'),copy);
  main.append(cards,enrollmentNote,panel('Recent devices', devicesTable(devices.items)),updateNote);
}
async function records(resource, epoch,customerId=null) {
  if(resource==='devices')return fleetPage(epoch,customerId);
  const main = $('main'); main.replaceChildren(heading(resource === 'devices' ? 'Devices' : 'Customers', resource === 'customers' ? 'Customers are created by verified enrollment. Line usernames are verified on app check-in; refresh after the 111 app reports in.' : 'Search, manage and inspect your assigned records.', [button('Refresh', route)]));
  const box = panel(null); const filters = el('form', null, 'filters'); const search = el('input'); search.placeholder = resource === 'devices' ? 'Search device, model, customer or ID…' : 'Search customer or reference…'; search.setAttribute('aria-label', 'Search');
  filters.append(search, button('Search', () => load(1))); filters.onsubmit = e => { e.preventDefault(); load(1); }; const resultBox = el('div'); box.append(filters, resultBox); main.append(box);
  async function load(page = 1) {
    try {
      const data = await api(`/api/${resource}?page=${page}&search=${encodeURIComponent(search.value)}${customerId?'&customer_id='+encodeURIComponent(customerId):''}`); if (epoch !== state.epoch) return;
       const content = resource === 'devices' ? devicesTable(data.items) : table(['Line username / customer', 'Workspace', 'Reference', 'Actions'], data.items.map(c => { const actions = el('div', null, 'actions'); actions.append(button('View devices',()=>{location.hash='devices/customer/'+c.id;}),button('Edit notes', () => customerForm(c))); const name = c.line_username || (/^Panel line [0-9]+$/.test(c.name) ? 'Username awaiting app check-in' : c.name); return [cellName(name, c.notes), c.tenant_name, c.external_reference || '—', actions]; }));
      resultBox.replaceChildren(content, pagination(data, load));
    } catch (e) { toast(e.message); }
  }
  await load();
}
async function fleetPage(epoch,customerId=null){
  const main=$('main');const count=el('p','Loading devices…','fleet-count');const head=heading('Devices','', [button('Refresh',()=>load())]);head.querySelector('p').replaceWith(count);main.replaceChildren(head);main.classList.add('fleet-page');
  const form=el('form',null,'fleet-filters');const search=el('input');search.type='search';search.placeholder='Search device, model or customer username…';search.setAttribute('aria-label','Search');const policy=el('select');policy.setAttribute('aria-label','App access filter');for(const[value,text]of [['','Current enrollments'],['active','Active'],['suspended','Suspended'],['blocked','Blocked'],['removed','Revoked enrollments']]){const o=el('option',text);o.value=value;policy.append(o);}
  const submit=el('button','Search');submit.type='submit';form.append(search,policy,submit);const results=el('div',null,'fleet-results');const note=el('p','Connection reflects app check-ins—not device power or stream health. Access changes and reseller removal are in the device’s Summary.','fleet-help');main.append(form,results,note);let page=1,serial=0;
  async function load(next=page){const turn=++serial;page=next;results.setAttribute('aria-busy','true');try{const data=await api(`/api/devices?page=${page}&search=${encodeURIComponent(search.value)}&policy=${policy.value}${customerId?'&customer_id='+encodeURIComponent(customerId):''}`);if(epoch!==state.epoch||turn!==serial)return;count.textContent=`${data.total} ${search.value||policy.value?'matching ':''}${data.total===1?'device':'devices'}${customerId?' for this customer':''}`;results.replaceChildren(devicesTable(data.items),pagination(data,load));}catch(e){if(epoch===state.epoch&&turn===serial)results.replaceChildren(panel('Could not load devices',el('p',e.message,'error'),button('Try again',()=>load())));}finally{if(turn===serial)results.removeAttribute('aria-busy');}}
  form.onsubmit=e=>{e.preventDefault();load(1);};policy.onchange=()=>load(1);await load(1);
}
async function resellers(epoch) {
  const main = $('main'); main.replaceChildren(heading('Reseller workspaces', 'Strictly isolated customer and device access.', [button('Create reseller', resellerForm, 'primary')]));
  const system = await api('/api/system/status'); if (epoch !== state.epoch) return;
  if (system.pending_migrations.length) { main.replaceChildren(heading('Database upgrade required', 'Open Enrollments and apply the database upgrade before linking panel resellers.')); return; }
  const links = (await api('/api/panel-resellers')).items; if (epoch !== state.epoch) return;
  const box = panel(null); const filters = el('form', null, 'filters'); const search = el('input'); search.placeholder = 'Search workspace name…'; search.setAttribute('aria-label', 'Search workspaces'); filters.append(search, button('Search', () => load(1))); filters.onsubmit = event => { event.preventDefault(); load(1); }; const results = el('div'); box.append(filters, results); main.append(box);
  async function load(page = 1) {
    try {
      const data = await api(`/api/tenants?page=${page}&search=${encodeURIComponent(search.value)}`); if (epoch !== state.epoch) return;
      const rows = data.items.map(t => {
        const linked = links.find(p => p.tenant_id === t.id);
        const actions = el('div', null, 'actions');
        if(linked&&t.status==='active')actions.append(button('Device defaults',()=>{location.hash='library/defaults/workspace/'+t.id;}));
        if(linked&&t.status==='active'&&t.kind==='reseller')actions.append(button('App brand',()=>{location.hash='branding/'+t.id;}));
        if(linked&&t.kind==='reseller')actions.append(button('Trials & contacts',()=>{location.hash='trials/'+t.id;}));
        if (!linked) actions.append(button('Link panel reseller', () => modal('Link existing panel reseller', [{ key: 'username', label: 'Exact panel login username', value: t.name, max: 150, hint: 'The worker must verify this active account. This permanent ID link enables automatic device/customer enrollment. Existing ownership is never silently transferred.' }], async values => { await api('/api/tenants/' + t.id + '/panel-link', values); toast('Panel reseller verified and linked. Pending apps will retry with fresh ownership proofs.'); })));
        if (t.kind !== 'owner') actions.append(button('Manage', () => modal('Reseller policy', [{ key: 'status', label: 'Status', value: t.status, options: [['active', 'Active'], ['suspended', 'Suspended — signs reseller out']] }, { key: 'device_limit', label: 'Device allocation', type: 'number', value: t.device_limit }], async values => { if (values.status === 'suspended' && !confirm('Suspend this reseller and cancel pending device actions? This does not wipe customer data.')) throw new Error('No changes made.'); await api('/api/tenants/' + t.id, values); toast('Reseller policy saved.'); })));
        return [cellName(t.name, linked ? `Panel: ${linked.username} · ID ${linked.panel_id}` : 'Panel not linked · manual review'), badge(t.status), `${t.devices_used} / ${t.device_limit}`, actions];
      });
      results.replaceChildren(table(['Workspace', 'Status', 'Allocation', 'Actions'], rows), pagination(data, load));
    } catch (error) { toast(error.message); }
  }
  await load();
}
function detailRows(entries) { const dl = el('dl', null, 'detail-grid'); entries.forEach(([name, value]) => dl.append(el('dt', name), el('dd', value ?? 'Not reported'))); return dl; }
const sortOptions = [['provider', 'Provider order'], ['number', 'Channel number'], ['name', 'Channel name (A–Z)']];
function presetForm(preset = null,tenantId=null) {
  modal(preset ? 'Edit settings preset' : 'Create settings preset', [
    ...(!preset && state.user.role === 'owner' ? [{...tenantField(),value:tenantId||undefined}] : []),
    { key: 'name', label: 'Preset name', max: 80, value: preset?.name || '' },
    { key: 'sort_order', label: 'Channel sorting', options: sortOptions, value: preset?.sort_order || 'provider', hint: 'This preset changes channel sorting only. It never changes playlists, credentials, playback engines or app updates.' }
  ], async values => { await api(preset ? '/api/settings-presets/' + preset.id : '/api/settings-presets', values); toast('Preset saved. Devices are unchanged until you explicitly apply it from their Settings tab.'); });
}
async function presetsPage(epoch) {
  const main = $('main'); main.replaceChildren(heading('Settings presets', 'Channel-sort presets and reusable playlist/category layout templates. Saving does not push to devices.', [button('Create preset', () => presetForm(), 'primary')]));
  const box = panel(null); const filters = el('form', null, 'filters'); const search = el('input'); search.placeholder = 'Search preset name…'; search.setAttribute('aria-label', 'Search presets'); filters.append(search, button('Search', () => load(1))); filters.onsubmit = e => { e.preventDefault(); load(1); }; const results = el('div'); box.append(filters, results); main.append(box);
  async function load(page = 1) {
    try {
      const data = await api(`/api/settings-presets?page=${page}&search=${encodeURIComponent(search.value)}`); if (epoch !== state.epoch) return;
      results.replaceChildren(table(['Preset', 'Workspace', 'Channel sorting', 'Updated', 'Actions'], data.items.map(p => {
        const a = el('div', null, 'actions'); a.append(button('Edit', () => presetForm(p)), button('Archive', async () => {
          if (!confirm('Archive this preset? Existing device settings and queued actions are unchanged.')) return;
          try { await api('/api/settings-presets/' + p.id + '/archive', {}); await load(page); } catch (e) { toast(e.message); }
        })); return [p.name, p.tenant_name, sortOptions.find(([key]) => key === p.sort_order)?.[1] || p.sort_order, date(p.updated_at), a];
      })), pagination(data, load));
    } catch (e) { toast(e.message); }
  }
  await load();
  if (epoch === state.epoch) await layoutTemplatesPanel(main, epoch);
  if (epoch === state.epoch) await preferenceProfilesPanel(main, epoch);
}
async function preferenceProfilesPanel(main,epoch,tenantId=null) {
  const box=panel('Broader settings profiles — explicit application only');main.append(box);
  box.append(el('p','Versioned workspace profiles manage only the listed allowlisted fields. Device/profile overrides win over the base. Saving or editing a profile never pushes changes automatically. Playback buffer/sorting/category controls remain in their dedicated device sections. Sources, credentials, entitlements, update URLs and decoder selection are not generic settings fields.','notice'));
  const search=el('input');search.placeholder='Search broader settings profiles…';search.setAttribute('aria-label','Search broader settings profiles');const result=el('div');box.append(search,button('Search settings profiles',()=>load()),result);
  let rules={};
  function form(profile=null) {
    const schema=[...(!profile&&state.user.role==='owner'?[{...tenantField(),value:tenantId||undefined}]:[]),{key:'name',label:'Settings profile name',max:80,value:profile?.name||''},...Object.entries(rules).map(([key,choices])=>({key,label:key.replaceAll('_',' '),required:false,value:profile?.values[key]||'',options:[['','Not managed by this base profile'],...choices.map(v=>[v,v])]}))];
    modal(profile?'Edit broader settings profile':'Create broader settings profile',schema,async v=>{const values={};for(const key of Object.keys(rules))if(v[key]!=='')values[key]=v[key];await api(profile?`/api/preference-profiles/${profile.id}`:'/api/preference-profiles',{name:v.name,values,...(profile?{expected_version:profile.version}:{...(v.tenant_id?{tenant_id:v.tenant_id}:{})})});},'Save profile — do not push');
  }
  box.prepend(button('Create broader settings profile',()=>form(),'primary'));
  async function load(page=1){try{const data=await api(`/api/preference-profiles?page=${page}&search=${encodeURIComponent(search.value)}${tenantId?'&tenant_id='+tenantId:''}`);if(epoch!==state.epoch)return;rules=data.rules;result.replaceChildren(table(['Settings profile','Workspace','Version','Fields','Actions'],data.items.map(p=>{const a=el('div',null,'actions');a.append(button('Edit profile',()=>form(p)),button('Archive profile',async()=>{if(confirm('Archive this profile? Existing device policy stays unchanged until explicitly replaced.')){try{await api(`/api/preference-profiles/${p.id}`,{name:p.name,values:p.values,expected_version:p.version,archived:true});await load(page);}catch(e){toast(e.message);}}}));return[p.name,p.tenant_name,p.version,Object.keys(p.values).length,a];})),pagination(data,load));}catch(e){result.replaceChildren(el('p',e.message+' Apply database upgrade011 first.','error'));}}
  await load();
}
async function preferencesPanel(d,parent,isCurrent,reload) {
  const box=panel('Effective settings profiles and device/profile overrides');parent.append(box);
  box.append(el('p','Each value shows its origin. Explicit device/profile overrides win over a base profile. Local changes are detected; a stale preview is rejected. Clearing management restores the underlying local/Cloud choice only where it has not been changed locally. Managed overlays do not propagate through Cloud to other devices. UI/audio observers may apply immediately; no forced player restart.','notice'));
  box.append(el('p','Audio/subtitle/player preferences use existing compatible/VOD player settings, not the embedded Live TV engine’s independent audio controls. Other fields follow existing Home/clock/UI settings reads. Saving confirms stored values, not that an active player has consumed them yet. Live TV pre-buffer has its own device-wide control above.','hint'));
  const read=button('Read expanded effective settings',()=>queue(d,'get_preferences').catch(e=>toast(e.message)));read.disabled=d.policy!=='active';box.append(read,button('Refresh expanded settings results',reload));
  let data,profiles;try{[data,profiles]=await Promise.all([api(`/api/devices/${d.id}/preferences`),api('/api/preference-profiles?tenant_id='+encodeURIComponent(d.tenant_id))]);}catch(e){box.append(el('p',e.message+' Apply database upgrade011 first.','error'));return;}if(!isCurrent())return;
  if(!data.snapshot){box.append(el('p','No expanded snapshot yet. Request one from the open app.','hint'));return;}
  const doc=data.snapshot.document;
  let editor=state.preferenceEditors.get(d.id);
  if(!editor||(!editor.dirty&&!editor.busy&&editor.snapshot!==data.snapshot.command_id)){editor={snapshot:data.snapshot.command_id,preset:doc.base_preset_id,overrides:{...doc.overrides},dirty:false,preview:null};state.preferenceEditors.set(d.id,editor);}
  box.append(el('p','Snapshot received: '+date(data.snapshot.created_at)+' · profile '+doc.profile_ref.slice(0,12),'subtle'));
  box.append(el('p',doc.base_preset_id?`Applied base reference ${doc.base_preset_id.slice(0,12)} · version ${doc.base_version}. Template edits do not update this device automatically.`:'No base profile currently applied.','hint'));
  if(editor.snapshot!==data.snapshot.command_id)box.append(el('p','A newer read is available. Discard this local draft to adopt it before previewing.','notice'));
  let previewButton=null;function markDirty(){editor.dirty=true;if(previewButton)previewButton.disabled=d.policy!=='active';}
  box.append(button('Discard settings draft / adopt latest read',()=>{if(!editor.dirty||confirm('Discard this local settings draft? No device settings will change.')){state.preferenceEditors.delete(d.id);reload();}}));
  const base=el('select');base.setAttribute('aria-label','Base settings profile');const options=[['','No base profile — underlying local values'],...profiles.items.map(p=>[p.id,`${p.name} · v${p.version}`])];if(editor.preset&&!options.some(([id])=>id===editor.preset))options.push([editor.preset,'Retired/unavailable base — choose an active profile or clear it']);for(const[value,label]of options){const o=el('option',label);o.value=value;base.append(o);}base.value=editor.preset;base.disabled=!!editor.preview||!!editor.busy;base.onchange=()=>{editor.preset=base.value;markDirty();};box.append(el('label','Base settings profile'),base);
  box.append(table(['Field (active profile)','Effective value','Origin','Explicit device/profile override'],Object.entries(data.rules).map(([key,choices])=>{const select=el('select');select.setAttribute('aria-label','Override '+key);for(const[value,label]of [['','No explicit override / reset'],...choices.map(v=>[v,v])]){const o=el('option',label);o.value=value;select.append(o);}select.value=editor.overrides[key]||'';select.disabled=!!editor.preview||!!editor.busy;select.onchange=()=>{if(select.value==='')delete editor.overrides[key];else editor.overrides[key]=select.value;markDirty();};return[key.replaceAll('_',' '),doc.values[key],String(doc.origins[key]).replaceAll('_',' '),select];})));
  const preview=button('Preview effective settings changes',async()=>{if(editor.busy)return;editor.busy=true;box.querySelectorAll('button,select').forEach(n=>{n.disabled=true;});try{const result=await api(`/api/devices/${d.id}/preferences/preview`,{snapshot_id:editor.snapshot,base_preset_id:editor.preset,overrides:editor.overrides});if(state.preferenceEditors.get(d.id)===editor)editor.preview=result;}catch(e){if(isCurrent())toast(e.message);}finally{editor.busy=false;if(isCurrent())await reload();}});preview.disabled=!!editor.preview||!!editor.busy||!editor.dirty||d.policy!=='active';previewButton=preview;box.append(preview);
  if(editor.preview){box.append(el('p',editor.preview.warning,'notice'),table(['Setting','Current','After apply','Origin'],editor.preview.changes.map(r=>[r.field,r.current,r.next,r.origin])));const apply=button('Push reviewed settings policy',async()=>{if(!confirm('Apply this complete reviewed base/override policy to the selected device/profile? Some UI/audio preferences can change immediately. Unrelated preferences, activation and update policy stay untouched.'))return;try{await queue(d,'apply_preferences',{draft_id:editor.preview.id});state.preferenceEditors.delete(d.id);await reload();}catch(e){toast(e.message);}},'primary');apply.disabled=d.policy!=='active';box.append(apply,button('Cancel settings preview',()=>{editor.preview=null;reload();}));}
}
async function supportPanel(d,parent,isCurrent,reload) {
  const box=panel('Private support history / customer-consented panel capture');parent.append(box);
  box.append(el('p','Request a redacted support bundle with up to 128 fixed-code events and app diagnostics. No raw logcat, URLs, headers or credentials. Panel captures require the customer’s one-time foreground consent within 20 seconds; only a dedicated safe support panel is captured—not the current page, TV video, account screens or other apps. Android updates must be idle.','notice'));
  for(const[kind,label]of [['get_support_bundle','Request redacted support bundle'],['capture_app_screen','Request consented support-panel image']]){const b=button(label,()=>{if(kind==='capture_app_screen'&&!confirm('Ask the customer to share one safe app support panel? They may decline; there is no hidden/full-device/video capture.'))return;queue(d,kind).catch(e=>toast(e.message));});b.disabled=d.policy!=='active'||!d.capabilities.includes(kind);box.append(b);}
  box.append(button('Refresh private support results',reload));
  try{const data=await api(`/api/devices/${d.id}/support`);if(!isCurrent())return;box.append(el('p',data.retention,'hint'),table(['Received','Artifact','Bytes','Expires','Private download'],data.items.map(r=>{const link=el('a',r.kind==='panel'?'Download consented panel PNG':'Download redacted JSON bundle');link.href=`/api/devices/${d.id}/support/${r.id}/download`;link.setAttribute('download','');return[date(r.created_at),r.kind,r.bytes,date(r.expires_at),link];})));}catch(e){if(isCurrent())box.append(el('p',e.message+' Apply database upgrade012 first.','error'));}
}
async function layoutTemplatesPanel(main, epoch) {
  const p = panel('Playlist/category layout templates', el('p', 'Organize a complete source in a device’s Categories draft editor, then Save as layout template. Templates match exact full category names, not device-specific IDs. Applying a template only edits the target draft until Push.', 'subtle'));
  p.append(button('Create from a device layout', () => { location.hash = 'devices'; toast('Open a device → Categories, organize its draft, then Save as layout template.'); }));
  const search = el('input'); search.placeholder = 'Search layout templates…'; search.setAttribute('aria-label', 'Search layout templates'); const result = el('div'); const filters = el('form', null, 'filters'); filters.append(search, button('Search layouts', () => load(1))); filters.onsubmit = e => { e.preventDefault(); load(1); }; p.append(filters, result); main.append(p);
  async function load(page = 1) {
    try {
      const data = await api(`/api/category-layout-templates?page=${page}&search=${encodeURIComponent(search.value)}`); if (epoch !== state.epoch) return;
      result.replaceChildren(table(['Layout', 'Workspace', 'Categories', 'Version', 'Actions'], data.items.map(t => {
        const a = el('div', null, 'actions');
        a.append(button('View', async () => { try { const full = await api('/api/category-layout-templates/' + t.id); modal(full.name, [], async () => {}, 'Close'); $('modal-form').prepend(el('p', `${full.entries.length} categories · version ${full.version}. First 50 shown.`, 'hint'), table(['Order', 'Category', 'Visibility'], full.entries.slice(0, 50).map((r, i) => [i + 1, r.name, r.hidden ? 'Hidden' : 'Visible']))); } catch (e) { toast(e.message); } }),
          button('Rename', () => modal('Rename layout template', [{ key: 'name', label: 'Layout name', value: t.name, max: 80 }], v => api('/api/category-layout-templates/' + t.id, { ...v, expected_version: t.version }))),
          button('Archive', async () => { if (!confirm('Archive this layout template? Devices and queued layouts are unchanged.')) return; try { await api('/api/category-layout-templates/' + t.id + '/archive', { expected_version: t.version }); await load(page); } catch (e) { toast(e.message); } }));
        if (state.user.role === 'owner') a.append(button('Copy to workspace', () => modal('Copy layout template', [tenantField(), { key: 'name', label: 'Layout name', value: t.name, max: 80 }], async v => { const source = await api('/api/category-layout-templates/' + t.id); await api('/api/category-layout-templates', { ...v, entries: source.entries }); toast('Independent copy saved; existing devices are unchanged.'); })));
        return [t.name, t.tenant_name, t.category_count, t.version, a];
      })), pagination(data, load));
    } catch (e) { result.replaceChildren(el('p', e.message + ' Apply any pending database upgrade under Enrollments.', 'error')); }
  }
  await load();
}
async function workspaceLayoutTemplates(tenantId) {
  const items = []; let page = 1, pages = 1;
  do { const data = await api(`/api/category-layout-templates?tenant_id=${encodeURIComponent(tenantId)}&page=${page}`); items.push(...data.items); pages = Math.min(4, data.pages); page++; } while (page <= pages);
  return items;
}
async function saveLayoutTemplate(d, draft) {
  const portable = LayoutDraft.template(draft);
  if (!portable.entries.length) { toast('No full, unredacted category names are eligible for a portable template. Your device draft is still usable.'); return; }
  if (portable.excluded.length && !confirm(`${portable.entries.length} categories will be saved. ${portable.excluded.length} shortened/redacted names cannot be matched safely and will be excluded from the template (not from the device draft). Continue?`)) return;
  const saved = await workspaceLayoutTemplates(d.tenant_id);
  modal('Save playlist/category layout template', [
    { key: 'template_id', label: 'Save destination', options: [['', 'New template'], ...saved.map(t => [t.id, t.name + ' · v' + t.version])], required: false },
    { key: 'name', label: 'Layout name', value: d.customer_name + ' layout', max: 80, hint: 'Saves the complete eligible order and visibility from this browser draft. Nothing is pushed to the app.' }
  ], async v => {
    const entries = LayoutDraft.template(draft).entries;
    if (v.template_id) { const t = saved.find(t => t.id === v.template_id); await api('/api/category-layout-templates/' + t.id, { name: v.name, entries, expected_version: t.version }); }
    else await api('/api/category-layout-templates', { tenant_id: d.tenant_id, name: v.name, entries });
    toast('Layout template saved. Device unchanged until Push.');
  });
}
async function applyLayoutTemplate(d, draft) {
  const saved = await workspaceLayoutTemplates(d.tenant_id);
  if (!saved.length) { toast('No layout templates in this workspace. Create one from an organized device draft first.'); return; }
  let loaded = null, generation = 0;
  modal('Preview layout template matches', [
    { key: 'template_id', label: 'Layout template', options: saved.map(t => [t.id, t.name]) },
    { key: 'visibility', label: 'Template options', options: [['yes', 'Order and matched-category visibility'], ['no', 'Order only — keep current visibility']] }
  ], async v => { if (!loaded || loaded.id !== v.template_id) throw new Error('Wait for the match preview.'); const p = LayoutDraft.applyTemplate(draft, loaded.entries, v.visibility === 'yes'); toast(`${p.matched.length} categories arranged locally. No device command sent—Push when ready.`); }, 'Apply to browser draft');
  const form = $('modal-form'), preview = el('div', null, 'notice'); form.prepend(preview);
  async function update() {
    const current = ++generation; preview.textContent = 'Loading match preview…';
    try {
      const t = await api('/api/category-layout-templates/' + form.elements.namedItem('template_id').value);
      if (current !== generation || !form.isConnected || !$('modal').open) return;
      loaded = t; const p = LayoutDraft.preview(draft, t.entries, form.elements.namedItem('visibility').value === 'yes');
      preview.replaceChildren(el('p', `${p.matched.length} exact matches · ${p.missing.length} template categories missing · ${p.unmatched.length} target-only/unmatchable categories kept.`),
        el('p', 'Matched categories move into template order. Target-only categories remain after them in their current order and retain visibility. No fuzzy matching, category creation or device-specific ID copying.', 'hint'));
      if (p.missing.length) preview.append(el('p', 'Missing (first 10): ' + p.missing.slice(0, 10).join(', '), 'hint'));
      if (p.ambiguous.length) preview.append(el('p', 'Ambiguous names detected—application will be rejected.', 'error'));
    } catch (e) { loaded = null; preview.textContent = e.message; }
  }
  form.elements.namedItem('template_id').addEventListener('change', update); form.elements.namedItem('visibility').addEventListener('change', update); await update();
}
async function layoutDraftPanel(d, body, isCurrent, goCommands) {
  let op = state.layoutEditors.get(d.id);
  if (!op) { op = { draft: null, readId: null, push: null, commands: [], message: '', available: null }; state.layoutEditors.set(d.id, op); }
  const panelNode = panel('Organize entire playlist/category layout'); body.append(panelNode);
  let timer = null, busy = false, localUi = null;
  const pending = () => op.commands.some(c => ['pending', 'delivered', 'running'].includes(c.status));
  const editingLocked = () => Boolean(op.push && !op.push.failed);
  async function enqueueRead(target = {}, key = uuid()) {
    const c = await api(`/api/devices/${d.id}/commands`, { kind: 'get_category_layout', payload: target }, { 'Idempotency-Key': key }); op.readId = c.id; op.message = 'Waiting for one complete layout read; the app must be open.'; return c;
  }
  async function refreshRead(target = {}) { try { await enqueueRead(target); await sync(); } catch (e) { toast(e.message); } }
  function ingest(data) {
    op.commands = data.commands;
    if (data.catalog && data.snapshot) {
      const draft = op.draft;
      if (!draft) op.draft = LayoutDraft.create(data.catalog, data.snapshot.command_id);
      else if (data.snapshot.command_id !== draft.snapshotId) {
        if (op.push && op.push.confirmId === data.snapshot.command_id) { op.draft = LayoutDraft.create(data.catalog, data.snapshot.command_id); op.push = null; op.available = null; op.message = 'Whole layout applied and confirmed by a fresh app read.'; }
        else if (!draft.dirty && !op.push) { op.draft = LayoutDraft.create(data.catalog, data.snapshot.command_id); op.available = null; }
        else op.available = data;
      }
    }
  }
  async function sync() {
    if (busy || !isCurrent()) return; busy = true;
    try {
      const watch = op.readId || op.push?.id;
      const query = new URLSearchParams({ metadata: '1', ...(watch ? { watch_id: watch } : {}), ...(op.readId ? { command_id: op.readId } : {}) });
      const data = await api(`/api/devices/${d.id}/category-layout?${query}`); if (!isCurrent()) return;
      op.commands = data.commands;
      if (op.readId) {
        const read = data.commands.find(c => c.id === op.readId);
        if (read && read.status === 'succeeded') {
          const full = await api(`/api/devices/${d.id}/category-layout?command_id=${op.readId}`); if (!isCurrent()) return; ingest(full);
          if (!full.catalog) { op.message = 'Read finished, but its cached snapshot was replaced. Read again; your draft is retained.'; if (op.push) { op.push.failed = true; op.draft.needsFresh = true; } }
          op.readId = null;
        } else if (read && ['failed', 'cancelled', 'expired'].includes(read.status)) { op.message = `${read.status}: ${read.result?.summary || 'Layout read did not complete'}. Draft retained.`; op.readId = null; if (op.push) { op.push.failed = true; op.draft.needsFresh = true; } }
      }
      if (op.push && !op.push.failed) {
        const pushed = data.commands.find(c => c.id === op.push.id);
        if (pushed?.status === 'succeeded' && !op.push.confirmId) {
          const c = await enqueueRead({ profile_ref: op.draft.base.profile_ref, source_ref: op.draft.base.source_ref }, 'confirm-' + op.push.id); op.push.confirmId = c.id; op.message = 'Whole layout applied; requesting one fresh read to confirm.';
        } else if (pushed && ['failed', 'cancelled', 'expired'].includes(pushed.status)) { op.push.failed = true; op.draft.needsFresh = true; op.message = `${pushed.status}: ${pushed.result?.summary || 'Push did not complete'}. Your draft is retained—read and explicitly rebase before another push.`; }
      }
      draw();
    } catch (e) { if (isCurrent()) { op.message = e.message; draw(); } }
    finally { busy = false; schedule(); }
  }
  function schedule() { clearTimeout(timer); if (isCurrent() && (pending() || op.readId || (op.push && !op.push.failed))) timer = setTimeout(sync, 3000); }
  async function push() {
    const draft = op.draft; if (!draft?.dirty || pending() || draft.needsFresh || op.available || !d.capabilities.includes('apply_category_layout')) return;
    if (!confirm(`Push the entire ${draft.items.length}-category order and visibility in ONE command? Other sources, favourites, credentials and guides are unchanged. Delivery still waits for the app’s check-in.`)) return;
    const payload = LayoutDraft.payload(draft), fingerprint = JSON.stringify([d.id, 'layout', payload]), key = requestKeys.get(fingerprint) || uuid(); requestKeys.set(fingerprint, key);
    try { const c = await api(`/api/devices/${d.id}/category-layout`, payload, { 'Idempotency-Key': key }); requestKeys.delete(fingerprint); op.push = { id: c.id, failed: false, confirmId: null }; op.message = 'ONE complete layout command queued; no category-by-category jobs.'; await sync(); }
    catch (e) { toast(e.message); }
  }
  function changed() { if (localUi) { localUi.status.textContent = `${op.draft.dirty ? 'UNSAVED DRAFT' : 'No local changes'} · ${op.draft.items.length} categories · ${op.draft.items.filter(r => r.hidden).length} hidden · ${op.draft.providerOrder ? 'Provider order' : 'Custom order'}`; localUi.push.disabled = !op.draft.dirty || pending() || editingLocked() || op.draft.needsFresh || Boolean(op.available) || d.policy !== 'active' || !d.capabilities.includes('apply_category_layout') || !op.draft.base.order_editable; localUi.table(); } }
  function draw() {
    if (!isCurrent()) return; panelNode.replaceChildren(el('h2', 'Organize entire playlist/category layout'));
    panelNode.append(el('p', 'All moves, drag/drop, positions and visibility changes below are LOCAL browser edits. Arrange everything first, then Push the entire layout once. Save a template before leaving/reloading this page to retain your work.', 'subtle'));
    const controls = el('div', null, 'actions'); const read = button('Read whole current profile layout', () => refreshRead()); read.disabled = d.policy !== 'active' || pending() || editingLocked(); controls.append(read, button('View layout commands', goCommands)); panelNode.append(controls);
    if (op.message) panelNode.append(el('p', op.message, 'notice'));
    const draft = op.draft;
    if (!draft) { panelNode.append(el('p', 'Request one complete cached source layout from the app first. There is no per-page native command wait after it arrives.', 'hint')); localUi = null; return; }
    if (op.available) {
      const fresh = op.available; const box = el('div', null, 'notice'); box.append(el('p', 'A newer snapshot is available. Your unsaved draft has NOT been overwritten.'));
      const rebase = button('Rebase draft onto fresh snapshot', () => { try { const diff = LayoutDraft.rebasePreview(draft, fresh.catalog); if (!confirm(`Rebase: ${diff.added.length} new and ${diff.removed.length} removed categories. Your edited order/visibility wins where changed; untouched visibility uses the fresh app state. Continue?`)) return; LayoutDraft.rebase(draft, fresh.catalog, fresh.snapshot.command_id); op.available = null; op.push = null; draw(); } catch (e) { toast(e.message); } }); rebase.disabled = editingLocked();
      const discard = button('Discard draft and load new snapshot', () => { if (!confirm('Discard local layout edits and load the new profile/source snapshot? Save a template first if needed.')) return; op.draft = LayoutDraft.create(fresh.catalog, fresh.snapshot.command_id); op.available = null; op.push = null; draw(); }); discard.disabled = editingLocked(); box.append(rebase, discard); panelNode.append(box);
    }
    const source = el('select'); source.setAttribute('aria-label', 'Layout source'); source.disabled = pending() || editingLocked();
    for (const s of draft.base.sources) { const option = el('option', `${s.label} · ${s.total} categories`); option.value = s.ref; option.selected = s.ref === draft.base.source_ref; source.append(option); }
    source.addEventListener('change', () => refreshRead({ profile_ref: draft.base.profile_ref, source_ref: source.value }));
    const refresh = button('Refresh source / get rebase snapshot', () => refreshRead({ profile_ref: draft.base.profile_ref, source_ref: draft.base.source_ref })); refresh.disabled = pending() || editingLocked() || d.policy !== 'active';
    const context = el('div', null, 'actions'); context.append(source, refresh); panelNode.append(context);
    const status = el('p', '', 'notice'); panelNode.append(status);
    const toolbar = el('div', null, 'actions'); const pushButton = button('Push entire layout once', push, 'primary');
    const apply = button('Preview / apply saved layout template', () => applyLayoutTemplate(d, draft).catch(e => toast(e.message))); apply.disabled = editingLocked();
    const discard = button('Discard local edits', () => { if (!confirm('Discard this browser draft? Device settings are unchanged.')) return; op.draft = LayoutDraft.create(draft.base, draft.snapshotId); op.draft.needsFresh = draft.needsFresh; op.push = null; draw(); }); discard.disabled = editingLocked();
    toolbar.append(pushButton, button('Save as layout template', () => saveLayoutTemplate(d, draft).catch(e => toast(e.message))), apply, discard);
    panelNode.append(toolbar);
    if (!draft.base.order_editable) panelNode.append(el('p', 'Legacy ordering requires a local app migration before batch push. The draft/template can still be prepared.', 'notice warning'));
    const search = el('input'); search.placeholder = 'Filter categories locally…'; search.value = draft.filter; search.setAttribute('aria-label', 'Filter layout categories');
    const bulk = el('div', null, 'actions'); const lock = editingLocked();
    function filtered() { const q = draft.filter.toLocaleLowerCase(); return draft.items.filter(r => r.label.toLocaleLowerCase().includes(q)); }
    function action(label, fn) { const b = button(label, () => { fn(); changed(); }); b.disabled = lock; bulk.append(b); return b; }
    action('Select all filtered categories', () => { filtered().forEach(r => draft.selected.add(r.ref)); }); action('Clear selection', () => draft.selected.clear());
    action('Hide selected', () => LayoutDraft.visibility(draft, draft.selected, true)); action('Show selected', () => LayoutDraft.visibility(draft, draft.selected, false));
    action('Selected to top', () => LayoutDraft.block(draft, draft.selected, 'top')); action('Selected to bottom', () => LayoutDraft.block(draft, draft.selected, 'bottom'));
    action('Use current provider order', () => LayoutDraft.provider(draft));
    panelNode.append(search, bulk); const area = el('div'); panelNode.append(area);
    function drawTable() {
      const rows = filtered(), pages = Math.max(1, Math.ceil(rows.length / 50)); draft.page = Math.min(draft.page, pages); const start = (draft.page - 1) * 50;
      const roster = table(['Select', 'Position', 'Category — drag row or enter position', 'Visible', 'Local moves'], rows.slice(start, start + 50).map(r => {
        const index = draft.items.findIndex(i => i.ref === r.ref); const checkbox = el('input'); checkbox.type = 'checkbox'; checkbox.checked = draft.selected.has(r.ref); checkbox.disabled = lock; checkbox.setAttribute('aria-label', 'Select ' + r.label); checkbox.addEventListener('change', () => { if (checkbox.checked) draft.selected.add(r.ref); else draft.selected.delete(r.ref); changed(); });
        const position = el('input'); position.type = 'number'; position.className = 'layout-position'; position.min = 1; position.max = draft.items.length; position.value = index + 1; position.disabled = lock; position.setAttribute('aria-label', 'Position of ' + r.label); position.addEventListener('change', () => { LayoutDraft.move(draft, r.ref, Number(position.value)); changed(); });
        const visible = el('input'); visible.type = 'checkbox'; visible.checked = !r.hidden; visible.disabled = lock; visible.setAttribute('aria-label', 'Visible ' + r.label); visible.addEventListener('change', () => { LayoutDraft.visibility(draft, [r.ref], !visible.checked); changed(); });
        const moves = el('div', null, 'actions'); for (const [label, target] of [['Up', index], ['Down', index + 2], ['Top', 1]]) { const b = button(label, () => { LayoutDraft.move(draft, r.ref, target); changed(); }); b.disabled = lock || target < 1 || target > draft.items.length || target === index + 1; moves.append(b); }
        return [checkbox, position, cellName(r.label, r.match_name === null ? 'Device-specific only — name is shortened/redacted' : 'Portable exact-name match'), visible, moves];
      }));
      roster.classList.add('layout-draft'); const trs = roster.querySelectorAll('tbody tr'); rows.slice(start, start + 50).forEach((row, i) => { const tr = trs[i]; if (!tr) return; tr.draggable = !lock; tr.dataset.categoryRef = row.ref;
        tr.addEventListener('dragstart', e => { e.dataTransfer.setData('text/plain', row.ref); e.dataTransfer.effectAllowed = 'move'; }); tr.addEventListener('dragover', e => { if (!lock) e.preventDefault(); }); tr.addEventListener('drop', e => { e.preventDefault(); if (lock) return; const ref = e.dataTransfer.getData('text/plain'), from = draft.items.findIndex(r => r.ref === ref), to = draft.items.findIndex(r => r.ref === row.ref); if (from < 0 || to < 0 || from === to) return; LayoutDraft.move(draft, ref, (from < to ? to - 1 : to) + 1); changed(); }); });
      const pager = el('div', null, 'pagination'), previous = button('Previous local page', () => { draft.page--; drawTable(); }), next = button('Next local page', () => { draft.page++; drawTable(); }); previous.disabled = draft.page === 1; next.disabled = draft.page === pages; pager.append(el('span', `${rows.length} filtered · ${draft.selected.size} selected · local page ${draft.page}/${pages}`), previous, next); area.replaceChildren(roster, pager);
    }
    search.addEventListener('input', () => { draft.filter = search.value; draft.page = 1; drawTable(); });
    localUi = { status, push: pushButton, table: drawTable }; changed();
  }
  try { const data = await api(`/api/devices/${d.id}/category-layout`); if (!isCurrent()) return; ingest(data); draw(); schedule(); } catch (e) { if (isCurrent()) panelNode.append(el('p', e.message + ' Apply the database upgrade under Enrollments first.', 'error')); }
}
async function queue(device, kind, payload = {}) {
  if (kind === 'clear_cache' && !confirm('Clear only disposable image and ordinary HTTP response caches? Login, profiles, favourites, saved guides, enrollment, update files and active playback buffers are preserved. Caches may refill immediately.')) return;
  if (kind === 'restart_app' && !confirm('Request a foreground app-screen reopen? This interrupts viewing but is NOT a process/device reboot or a guaranteed remote launch. The app must be resumed and updates idle. Retries never replay it; cooldown is five minutes.')) return;
  if(window.DeviceWorkspace?.isActive(device.id))return DeviceWorkspace.send(kind,payload);
  const fingerprint = JSON.stringify([device.id, kind, payload]); const key = requestKeys.get(fingerprint) || uuid(); requestKeys.set(fingerprint, key);
  try { await api(`/api/devices/${device.id}/commands`, { kind, payload }, { 'Idempotency-Key': key }); requestKeys.delete(fingerprint); toast('Action queued—not yet executed.'); await route(); }
  catch (error) { if (error.status && error.status < 500) requestKeys.delete(fingerprint); throw error; }
}
async function sourcesPanel(d, body, isCurrent, reload) {
  const box = panel('Playlist sources — local draft and one guarded push'); body.append(box);
  box.append(el('p', 'Edit the whole source set locally. URLs, portal MACs and credentials are write-only and never read back. The verified activation source stays first with unchanged account credentials. Additional M3U/Xtream/Stalker sources, names, enabled state, order and guides can be edited. Source changes can interrupt viewing and make saved favourites unavailable, but good backup/guide history is retained.', 'notice'));
  box.append(el('p','Keep at least one M3U/Xtream source enabled; the app’s legacy fallback is not changed. Use canonical M3U/xmltv URLs and URL-safe Xtream replacement credentials. The app refuses targets it would rewrite before any mutation. Profile source edits follow existing IPTV Cloud sync when enabled.','hint'));
  const actions = el('div', null, 'actions');
  const read=button('Read fresh sources from app', () => queue(d, 'get_sources').catch(e => toast(e.message)), 'primary');read.disabled=d.policy!=='active';
  actions.append(read, button('Refresh source results', reload), button('Refresh playlist',()=>queue(d,'refresh_playlist').catch(e=>toast(e.message))),button('Reload EPG',()=>queue(d,'reload_epg').catch(e=>toast(e.message)))); box.append(actions);
  let history; try { history = await api(`/api/devices/${d.id}/sources`); } catch(e) { box.append(el('p', e.message + ' Apply database upgrade010 first.', 'error')); return; } if (!isCurrent()) return;
  box.append(el('p', history.notice, 'hint'));
  if(history.commands?.length)box.append(table(['Source action','State','Result'],history.commands.map(c=>[c.kind.replaceAll('_',' '),badge(c.status),c.result?.summary||'Waiting for app — not yet applied'])));
  const latest = history.items.find(r => ['read', 'confirmation'].includes(r.reason)) || history.items[0];
  if (!latest) { box.append(el('p', 'No private source snapshot yet. Request a read while the app is open.', 'hint')); return; }
  let editor = state.sourceEditors.get(d.id);
  async function adopt() { const fresh = await api(`/api/devices/${d.id}/sources/${latest.id}`); if (!isCurrent()) return; editor = { id: latest.id, entries: fresh.entries, dirty: false, preview: null }; state.sourceEditors.set(d.id, editor); }
  if (!editor || (!editor.dirty && !editor.preview && !editor.busy && editor.id !== latest.id)) await adopt(); if (!isCurrent() || !editor) return;
  if (editor.id !== latest.id) box.append(el('p', 'A newer app read exists. Your local draft is retained; discard it explicitly to load the newer base.', 'notice'));
  box.append(button('Discard draft / load latest source read', async () => { if (!editor.dirty || confirm('Discard this local draft and secret replacements? Device configuration will not change.')) { state.sourceEditors.delete(d.id); await reload(); } }));
  const secretKeys = ['replace_url','replace_mac','replace_guides','xtream_host','xtream_username','xtream_password'];
  async function requestPreview(input){if(editor.busy)return;editor.busy=true;box.querySelectorAll('button').forEach(b=>{b.disabled=true;});try{const result=await api(`/api/devices/${d.id}/sources/preview`,input);if(state.sourceEditors.get(d.id)!==editor)return;editor.preview=result;for(const row of editor.entries)for(const key of secretKeys)delete row[key];}catch(e){if(isCurrent())toast(e.message);}finally{editor.busy=false;if(isCurrent())await reload();}}
  function clean(row) { const copy = { kind: row.kind, name: row.name, enabled: row.enabled }; if (row.ref) copy.ref = row.ref; for (const key of secretKeys) if (key in row) copy[key] = row[key]; return copy; }
  function edit(row = null, kind = 'm3u') {
    kind = row?.kind || kind;
    const schema = [{ key: 'name', label: 'Source name', value: row?.name, max: 80 }, { key: 'enabled', label: 'Enabled', value: row?.enabled === false ? 'no' : 'yes', options: [['yes','Enabled'],['no','Disabled']] }];
    if (!row?.canonical) schema.push({ key: 'url', label: kind === 'stalker' ? 'New/replacement portal URL (write-only)' : 'New/replacement M3U URL (write-only)', type: 'password', min: 0, max: 4096, required: false, hint: 'Blank preserves the existing value. For Xtream, use the host/username/password fields instead.' });
    if (kind === 'stalker') schema.push({ key: 'mac', label: 'New/replacement portal MAC (write-only)', type: 'password', min: 0, max: 17, required: false });
    if (kind === 'm3u') {
      if (!row?.canonical) schema.push(...[['host','Xtream host'],['username','Xtream username'],['password','Xtream password']].map(([key,label]) => ({ key, label: label + ' (write-only)', type:'password', min:0, max:key==='host'?4096:256, required:false })));
      schema.push({ key:'guide_action',label:'Guide URLs',options:[['keep','Keep existing guide preferences'],['replace','Replace guide URLs (empty clears only this source’s guide preference)']] }, {key:'guides',label:'Replacement guide URLs, one per line (write-only)',type:'textarea',max:41000,required:false});
    }
    modal(row ? 'Edit source in local draft' : 'Add source to local draft', schema, async v => {
      const next = { ...(row || {kind, canonical:false}), name:v.name, enabled:v.enabled==='yes' };
      if (v.url) next.replace_url = v.url;
      if (v.mac) next.replace_mac = v.mac;
      if (v.host || v.username || v.password) { if (!v.host || !v.username || !v.password || v.url) throw new Error('Supply all Xtream fields OR an M3U URL, not both.'); next.xtream_host=v.host; next.xtream_username=v.username; next.xtream_password=v.password; }
      if (v.guide_action==='replace') next.replace_guides=v.guides.split(/\r?\n/).map(s=>s.trim()).filter(Boolean);
      if (row) editor.entries[editor.entries.indexOf(row)] = next; else if(kind==='m3u'){const firstPortal=editor.entries.findIndex(r=>r.kind==='stalker');editor.entries.splice(firstPortal<0?editor.entries.length:firstPortal,0,next);} else editor.entries.push(next);
      editor.dirty=true; editor.preview=null;
    }, 'Save local source edit');
  }
  const rows = editor.entries.map((row,index) => {
    const a = el('div',null,'actions'); const locked=!!editor.preview||!!editor.busy;
    const change=button('Edit',()=>edit(row)); change.disabled=locked; a.append(change);
    for (const [label,delta] of [['Move up',-1],['Move down',1]]) { const move=button(label,()=>{const to=index+delta;[editor.entries[index],editor.entries[to]]=[editor.entries[to],editor.entries[index]];editor.dirty=true;reload();}); move.disabled=locked||row.canonical||index+delta<0||index+delta>=editor.entries.length||editor.entries[index+delta]?.canonical||editor.entries[index+delta]?.kind!==row.kind; a.append(move); }
    const remove=button('Remove from draft',()=>{if(confirm('Remove this source from the local draft? It will only change the app after a reviewed push.')){editor.entries.splice(index,1);editor.dirty=true;reload();}});remove.disabled=locked||row.canonical;a.append(remove);
    return [index+1,cellName(row.name,row.canonical?'Verified activation source — account protected':row.kind),row.enabled?'Enabled':'Disabled',secretKeys.some(k=>k in row)?'Replacement supplied (private)':'Existing secrets preserved',a];
  });
  box.append(table(['Order','Source','Status','Private values','Local actions'],rows));
  for (const kind of ['m3u','stalker']) { const add=button(kind==='m3u'?'Add M3U / Xtream source':'Add Stalker portal',()=>edit(null,kind));add.disabled=!!editor.preview||!!editor.busy;box.append(add); }
  const preview=button('Preview complete source replacement',()=>requestPreview({snapshot_id:editor.id,entries:editor.entries.map(clean)}));preview.disabled=!!editor.preview||!!editor.busy||!editor.dirty||d.policy!=='active';box.append(preview);
  if(editor.preview){box.append(el('p',editor.preview.warning,'notice'),table(['Preview source','Kind','Enabled','Identity'],editor.preview.entries.map(r=>[r.name,r.kind,r.enabled?'Yes':'No',r.identity_changed?'New/restored source identity — no guessed favourites migration':'Retained'])));
    const push=button('Push reviewed source replacement',async()=>{if(!confirm('REPLACE the complete selected profile source set? Removed/disabled sources can stop viewing. A private safety checkpoint must succeed before one atomic write. Existing guide/favourite history is retained; this is not a provider-health check.'))return;try{await queue(d,'apply_sources',{draft_id:editor.preview.id});editor.dirty=false;editor.preview=null;await reload();}catch(e){toast(e.message);}},'primary');push.disabled=d.policy!=='active'||!d.capabilities.includes('apply_sources');box.append(push,button('Cancel preview (re-enter secret replacements to edit again)',()=>{editor.preview=null;reload();}));
  }
  box.append(panel('Private source checkpoints / guarded rollback',table(['Captured','Reason','Protection','Action'],history.items.slice(0,20).map(r=>[date(r.created_at),r.reason,r.protected?'Initial protected':'Rolling',button('Preview rollback to this version',async()=>{if(editor.busy||!confirm('Preview rollback using this checkpoint? A fresh current source read is required; no device change happens yet.'))return;await requestPreview({snapshot_id:editor.id,rollback_id:r.id});})]))));
}
async function favouritesPanel(d, body, isCurrent, reload) {
  const box = panel('Favourites backups & recovery'); body.append(box);
  box.append(el('p', 'Good nonempty versions are saved automatically while the managed app is open. Clearing favourites updates live status only—it never replaces a good backup. Recovery requires a version captured BEFORE the mistake.', 'subtle'));
  const actions = el('div', null, 'actions'); const capture = button('Back up current favourites now', () => queue(d, 'backup_favourites').catch(e => toast(e.message)), 'primary'); capture.disabled = d.policy !== 'active' || !d.capabilities.includes('backup_favourites'); actions.append(capture, button('Refresh backups / results', reload)); box.append(actions);
  if (!d.capabilities.includes('backup_favourites')) box.append(el('p', 'Update the testing app to 111 and let it check in to enable automatic backups and recovery. Existing saved server versions stay available.', 'notice'));
  let data; try { data = await api(`/api/devices/${d.id}/favourites`); } catch (e) { box.append(el('p', e.message + ' Apply the database upgrade under Enrollments first.', 'error')); return; } if (!isCurrent()) return;
  if (data.live.length) box.append(panel('Last app-reported favourites state', table(['Saved app context', 'Channels', 'Groups', 'Reported'], data.live.map((r,index) => ['Reported context '+(index+1), r.channel_count, r.group_count, date(r.updated_at)]))));
  if (data.commands.length) box.append(table(['Recovery action', 'Status', 'Result'], data.commands.slice(0, 4).map(c => [c.kind.replaceAll('_', ' '), badge(c.status), c.result?.summary || 'Waiting for app check-in'])));
  const chosen = state.favouritesSelection.get(d.id) || data.items[0]?.id; if (chosen) state.favouritesSelection.set(d.id, chosen);
  box.append(table(['Saved version', 'Favourites', 'Protection', 'Recovery binding', 'Actions'], data.items.map(b => {
    const a = el('div', null, 'actions'); a.append(button('View / recover', () => { state.favouritesSelection.set(d.id, b.id); reload(); }), button('Label / protect', () => modal('Protect favourites backup', [
      { key: 'label', label: 'Backup label', max: 80, required: false, value: b.label },
      { key: 'protection', label: 'Protection', value: b.protected ? 'yes' : 'no', options: [['yes', 'Protected — never auto-pruned'], ['no', 'Rolling history — may be pruned']] }
    ], async v => { if (v.protection === 'no' && b.protected && !confirm('Unprotect this good version? It may later be pruned, but the app is not changed.')) throw new Error('No changes made.'); await api(`/api/devices/${d.id}/favourites/backups/${b.id}/protect`, { label: v.label, protected: v.protection === 'yes' }); })));
    return [cellName(b.label || 'Saved favourites', date(b.created_at)), `${b.channel_count} channels · ${b.group_count} groups`, badge(b.protected ? 'protected' : 'rolling'), cellName('Original app profile/source','Validated by the recovery preview'), a];
  })));
  if (!data.items.length) box.append(el('p', 'No nonempty backup has been captured yet. This service cannot reconstruct favourites already deleted before its first capture.', 'notice'));
  if (chosen) {
    let backup, previewData;
    try { [backup, previewData] = await Promise.all([api(`/api/devices/${d.id}/favourites/backups/${chosen}`), api(`/api/devices/${d.id}/favourites/backups/${chosen}/preview`)]); }
    catch (e) { state.favouritesSelection.delete(d.id); if (isCurrent()) box.append(el('p', e.message + ' Refresh and select an available saved version.', 'error')); return; }
    if (!isCurrent()) return;
    const recovery = panel('Selected version — preview before restoring', el('p', `${backup.label || 'Saved version'} · ${date(backup.created_at)} · ${backup.channel_count} channels / ${backup.group_count} groups`, 'subtle'));
    const technical=el('details',null,'advanced-details');technical.append(el('summary','Backup context details'),detailRows([['Saved profile binding',backup.profile_ref],['Saved source binding',backup.source_ref]]));recovery.append(technical);
    const preview = previewData.preview;
    const request = button('Preview recovery on the app', async () => { try { await api(`/api/devices/${d.id}/favourites/backups/${chosen}/preview`, {}, { 'Idempotency-Key': uuid() }); await reload(); } catch (e) { toast(e.message); } }); request.disabled = d.policy !== 'active' || !d.capabilities.includes('preview_favourites_restore'); recovery.append(request);
    const missing = new Set(preview?.preview.missing_refs || []);
    if (preview) {
      const p = preview.preview; recovery.append(el('p', `${p.matched_channels} channels + ${p.matched_groups} groups available · ${missing.size} missing · current ${p.current_channels} channels / ${p.current_groups} groups. Preview expires ${date(preview.expires_at)}.`, 'notice'));
      const mode = el('select'); mode.setAttribute('aria-label', 'Favourites recovery mode');
      for (const [value, label] of [['merge', 'Merge back — preserve current favourites'], ['replace', 'Replace current favourites with this complete backup']]) { const option = el('option', label); option.value = value; option.disabled = value === 'replace' && missing.size > 0; mode.append(option); }
      const restore = button('Push favourites recovery', async () => {
        const description = mode.value === 'replace' ? `REPLACE current favourites with this backup? A safety backup of current nonempty favourites is captured first.` : `Merge ${p.matched_channels + p.matched_groups} saved favourites back, preserving current ones? ${missing.size} unavailable entries will be skipped; the original backup stays intact.`;
        if (!confirm(description + ' Nothing is marked restored until the app confirms.')) return;
        try { await api(`/api/devices/${d.id}/favourites/backups/${chosen}/restore`, { preview_id: preview.command_id, mode: mode.value }, { 'Idempotency-Key': uuid() }); await reload(); } catch (e) { toast(e.message); }
      }, 'primary'); restore.disabled = d.policy !== 'active' || !d.capabilities.includes('restore_favourites') || p.matched_channels + p.matched_groups === 0;
      recovery.append(mode, restore, el('p', 'Restores target the same device/profile/source. Source changes, app-data resets, stale favourites or changed channel availability require a new valid preview—not fuzzy matching.', 'hint'));
    } else recovery.append(el('p', 'Request a fresh app preview to validate profile/source and identify missing channels. Merge is the safe default; partial Replace is refused.', 'hint'));
    const filter = el('input'); filter.placeholder = 'Filter saved favourites…'; filter.setAttribute('aria-label', 'Filter saved favourites'); const rows = el('div');
    function draw() { const visible = backup.entries.filter(e => e.label.toLocaleLowerCase().includes(filter.value.toLocaleLowerCase())); rows.replaceChildren(table(['Saved favourite', 'Kind', 'Preview'], visible.slice(0, 50).map(e => [e.label, e.kind, preview ? (missing.has(e.ref) ? 'Missing — skipped' : 'Available') : 'Not checked yet'])), el('p', `${visible.length} matching saved items; first 50 displayed.`, 'hint')); }
    filter.addEventListener('input', draw); recovery.append(filter, rows); draw(); box.append(recovery);
  }
  const recentRestore = data.commands.find(c => c.kind === 'restore_favourites' && c.status === 'succeeded' && c.finished_at && Date.now() - new Date(c.finished_at.replace(' ', 'T') + 'Z').getTime() < 120000);
  const waitingForConfirmation = recentRestore && data.live.every(r => r.updated_at <= recentRestore.finished_at);
  const hasPendingRecovery = data.commands.some(c => ['pending', 'delivered', 'running'].includes(c.status));
  if (hasPendingRecovery || waitingForConfirmation) {
    const signature = JSON.stringify(data);
    async function poll() {
      if (!isCurrent()) return;
      if (!hasPendingRecovery && recentRestore && Date.now() - new Date(recentRestore.finished_at.replace(' ', 'T') + 'Z').getTime() >= 120000) return;
      try { const next = await api(`/api/devices/${d.id}/favourites`); if (!isCurrent()) return; if (JSON.stringify(next) !== signature && !$('modal').open) { await reload(); return; } } catch (e) { if (isCurrent()) toast(e.message); }
      if (isCurrent()) setTimeout(poll, 3000);
    }
    setTimeout(poll, 3000);
  }
}
async function categoryPanel(d, body, isCurrent, goCommands) {
  const p = panel('Source-scoped categories'); body.append(p);
  p.append(el('p', 'Read the app’s cached Live TV categories for one active profile/source. Titles (up to 80 characters) and counts only—no playlist URLs or credentials. Titles may be redacted; row references distinguish them. This is a snapshot, not a live provider lookup.', 'subtle'));
  const commands = (await api(`/api/devices/${d.id}/commands`)).items; if (!isCurrent()) return;
  const pending = commands.some(c => ['get_categories', 'set_categories'].includes(c.kind) && ['pending', 'delivered', 'running'].includes(c.status));
  const canRead = d.policy === 'active' && !pending;
  const actions = el('div', null, 'actions');
  const read = button('Read current profile categories', () => queue(d, 'get_categories').catch(e => toast(e.message)), 'primary'); read.disabled = !canRead; actions.append(read, button('View category commands', goCommands)); p.append(actions);
  if (pending) p.append(el('p', 'A category request/change is queued or running. Leave the app open, then Refresh this view. Queued requests can be cancelled under Commands.', 'notice'));
  const result = commands.filter(c => c.kind === 'get_categories' && c.status === 'succeeded' && c.result?.catalog).sort((a, b) => Number(b.result.received_at_us || 0) - Number(a.result.received_at_us || 0) || String(b.finished_at).localeCompare(String(a.finished_at)))[0];
  if (!result) { p.append(el('p', 'No category snapshot in the latest command-history page. Request one from the app first. Nothing is guessed from other devices or sources.', 'hint')); return; }
  const catalog = result.result.catalog;
  if (commands.some(c => ['get_categories', 'set_categories'].includes(c.kind) && c.status === 'failed' && c.created_at >= result.created_at)) p.append(el('p', 'A category request/change failed since this snapshot. The previous snapshot is retained below—not treated as a successful fresh read. View Commands for the result or read again.', 'notice warning'));
  p.append(el('p', `Snapshot received: ${date(result.finished_at)} · Profile ${catalog.profile_ref.slice(0, 12)} · ${catalog.total} categories in this source`, 'hint'));
  const selector = el('select'); selector.setAttribute('aria-label', 'Category source'); selector.disabled = !canRead;
  for (const source of catalog.sources) { const o = el('option', `${source.label} · ${source.total} categories`); o.value = source.ref; o.selected = source.ref === catalog.source_ref; selector.append(o); }
  const sourcePayload = (page = 1, source = catalog.source_ref) => ({ profile_ref: catalog.profile_ref, source_ref: source, page });
  selector.addEventListener('change', () => queue(d, 'get_categories', sourcePayload(1, selector.value)).catch(e => toast(e.message)));
  const refresh = button('Refresh this source', () => queue(d, 'get_categories', sourcePayload(catalog.page)).catch(e => toast(e.message))); refresh.disabled = !canRead;
  const sourceActions = el('div', null, 'actions'); sourceActions.append(selector, refresh); p.append(sourceActions);
  const canWrite = canRead && d.capabilities.includes('set_categories');
  const change = (operation, ref) => queue(d, 'set_categories', { profile_ref: catalog.profile_ref, source_ref: catalog.source_ref, expected_revision: catalog.revision, operation, ...(ref ? { category_ref: ref } : {}) });
  const reset = button('Reset this source’s category order', async () => {
    if (!confirm('Reset custom category ordering for this source only? Hidden categories, favourites, other sources and app data stay intact.')) return;
    try { await change('reset_order'); } catch (e) { toast(e.message); }
  }); reset.disabled = !canWrite || !catalog.order_editable; p.append(reset);
  if (!catalog.order_editable) p.append(el('p', 'Legacy category ordering needs migration on the app before remote reordering. Visibility actions can still be used.', 'notice'));
  p.append(el('p', 'After a successful change, request a fresh snapshot to confirm it. A stale profile/source/catalog revision is rejected rather than overwriting newer preferences.', 'hint'));
  p.append(table(['Position', 'Category', 'Channels', 'Visibility', 'Actions'], catalog.items.map(item => {
    const a = el('div', null, 'actions');
    const visibility = button(item.hidden ? 'Show category' : 'Hide category', () => change(item.hidden ? 'show' : 'hide', item.ref).catch(e => toast(e.message))); visibility.disabled = !canWrite; a.append(visibility);
    for (const [operation, label, edge] of [['move_up', 'Move up', item.position === 1], ['move_down', 'Move down', item.position === catalog.total], ['move_top', 'Move to top', item.position === 1]]) {
      const b = button(label, () => change(operation, item.ref).catch(e => toast(e.message))); b.disabled = !canWrite || !catalog.order_editable || edge; a.append(b);
    }
    return [item.position, cellName(item.label, item.ref.slice(0, 8)), item.channels, badge(item.hidden ? 'hidden' : 'visible'), a];
  })));
  const pages = el('div', null, 'pagination'); const previous = button('Previous category page', () => queue(d, 'get_categories', sourcePayload(catalog.page - 1)).catch(e => toast(e.message))); previous.disabled = !canRead || catalog.page === 1;
  const next = button('Next category page', () => queue(d, 'get_categories', sourcePayload(catalog.page + 1)).catch(e => toast(e.message))); next.disabled = !canRead || catalog.page === catalog.pages;
  pages.append(el('span', `Page ${catalog.page} of ${catalog.pages}`), previous, next); p.append(pages);
}
async function devicePage(id, epoch) {
  if(window.DeviceWorkspace)return DeviceWorkspace.render(id,epoch);
  const d = await api('/api/devices/' + id); if (epoch !== state.epoch) return;
  const main = $('main'); main.replaceChildren(); const back = el('a', '← All devices', 'back-link'); back.href = '#devices'; main.append(back, heading(d.label, d.customer_name + ' · ' + d.tenant_name, [button('Refresh', route), badge(d.presence)]));
  if (d.is_test) main.append(el('p', 'LOCAL TEST SIMULATOR — not a real customer device.', 'notice warning'));
  if (d.reseller_detached) main.append(el('p', 'UNASSIGNED — owner-managed only. Former reseller access has been removed; the app is not blocked.', 'notice'));
  if (!d.enrolled_at) main.append(el('p', 'Reserved device record awaiting approved enrollment. It is not an online app connection. The owner can attach a verified enrollment request using this record ID.', 'notice'));
  const tabs = el('div', null, 'tabs'); const body = el('div'); main.append(tabs, body);
  const names = ['Overview', 'Playlists', 'Settings', 'Categories', 'Favourites', 'Notifications', 'Logs', 'Commands'];
  for (const name of names) { const b = button(name, () => select(name)); b.dataset.tab = name; tabs.append(b); }
  async function select(name) {
    state.deviceTab = { id, name };
    tabs.querySelectorAll('button').forEach(b => b.classList.toggle('active', b.dataset.tab === name)); body.replaceChildren();
    if (name === 'Overview') {
      const actions = el('div', null, 'actions'); const kinds = [['refresh_playlist', 'Refresh playlist'], ['reload_epg', 'Reload EPG'], ['request_checkin', 'Request check-in'], ['clear_cache', 'Clear expendable cache'], ['restart_app', 'Restart app']];
      kinds.forEach(([kind, label]) => { const b = button(label, () => queue(d, kind).catch(e => toast(e.message))); b.disabled = d.policy !== 'active' || !d.capabilities.includes(kind); b.title = b.disabled ? 'Requires an enrolled app advertising this capability.' : 'Queues an expiring action; it cannot wake an offline app.'; actions.append(b); });
      body.append(panel('Quick actions', actions, el('p', 'Commands are queued until check-in. Restarting/reopening remains subject to Android/OEM policy.', 'hint')));
      const columns = el('div', null, 'columns'); columns.append(panel('Device information', detailRows([['Installation record', d.id], ['Model', d.model], ['OS version', d.os_version], ['App version', d.app_version], ['Storage free / total', d.storage_total === null ? null : `${(d.storage_free / 1073741824).toFixed(1)} / ${(d.storage_total / 1073741824).toFixed(1)} GB`], ['Capabilities', d.capabilities.join(', ') || 'None reported']])), panel('Connection & policy', detailRows([['Policy', d.policy], ['Connection', d.presence.replaceAll('_', ' ')], ['First seen / provisioned', date(d.created_at)], ['Last app check-in', date(d.last_seen_at)], ['Observed IP', d.last_ip]]))); body.append(columns);
      const policyActions = el('div', null, 'actions'); policyActions.append(button('Edit notes', () => modal('Device details', [{ key: 'label', label: 'Nickname', value: d.label }, { key: 'notes', label: 'Private notes', type: 'textarea', value: d.notes, required: false }], async values => { await api('/api/devices/' + d.id, values); toast('Device details saved.'); })));
       const policies = d.policy === 'removed' ? [] : [...(d.policy === 'active' ? [['suspended', 'Suspend app']] : (d.policy !== 'blocked' || state.user.role === 'owner' ? [['active', 'Reactivate']] : [])), ...(state.user.role === 'owner' ? [['blocked', 'Block app'], ['removed', 'Revoke enrollment (locks app)']] : [])];
       if (state.user.role === 'owner' && !d.reseller_detached) policyActions.append(button('Remove from reseller — keep app working', async () => {
         if (!confirm('Remove this device from its reseller and move it to the owner-only Unassigned devices area? This clears management restrictions, cancels old queued commands and keeps the app login/data intact. It does NOT change the IPTV subscription in your panel.')) return;
         try { await api('/api/devices/' + d.id + '/detach-reseller', {}); toast('Reseller access removed. App remains active.'); await route(); } catch (e) { toast(e.message); }
       }));
      policies.forEach(([policy, label]) => policyActions.append(button(label, async () => { if (!confirm(`${label}? This changes service policy, not the whole Android device. It applies to connected management clients and does not erase app data.`)) return; try { await api('/api/devices/' + d.id, { policy }); toast('Policy saved.'); await route(); } catch (e) { toast(e.message); } }, policy === 'active' ? '' : 'danger')));
      body.append(panel('Private notes & management policy', el('p', d.notes || 'No notes yet.', 'notes'), policyActions));
    } else if (name === 'Commands') {
      const p = panel('Command history'); body.append(p);
      const load = async (page = 1) => { const result = await api(`/api/devices/${id}/commands?page=${page}`); if (epoch !== state.epoch || !tabs.querySelector('[data-tab="Commands"]').classList.contains('active')) return; p.replaceChildren(el('h2', 'Command history'), table(['Action', 'Status', 'Attempts', 'Requested', 'Result', 'Cancel queued action'], result.items.map(c => {
        const a = el('div');
        if (['pending', 'delivered'].includes(c.status) && (state.user.role === 'owner' || c.requested_by === state.user.id)) a.append(button('Cancel', async () => {
          if (!confirm('Cancel this queued action? An operation already running cannot be recalled.')) return;
          try { await api(`/api/devices/${id}/commands/${c.id}/cancel`, {}); await load(page); } catch (e) { toast(e.message); }
        }));
        return [c.kind.replaceAll('_', ' '), badge(c.status), c.attempts, date(c.created_at), c.result?.summary || '—', a];
      })), pagination(result, load)); };
      try { await load(); } catch (e) { toast(e.message); }
    } else if (name === 'Favourites') {
      await favouritesPanel(d, body, () => epoch === state.epoch && tabs.querySelector('[data-tab="Favourites"]').classList.contains('active'), () => select('Favourites'));
    } else if (name === 'Categories' && d.capabilities.includes('get_category_layout')) {
      await layoutDraftPanel(d, body, () => epoch === state.epoch && tabs.querySelector('[data-tab="Categories"]').classList.contains('active'), () => select('Commands'));
    } else if (name === 'Categories' && d.capabilities.includes('get_categories')) {
      body.append(el('p', 'This older app supports only per-command editing. Update to 110 testing for the complete draft editor and one-shot layout push.', 'notice'));
      try { await categoryPanel(d, body, () => epoch === state.epoch && tabs.querySelector('[data-tab="Categories"]').classList.contains('active'), () => select('Commands')); } catch (e) { toast(e.message); }
    } else if ((name === 'Settings' || name === 'Logs') && d.capabilities.includes(name === 'Settings' ? 'get_settings' : 'get_diagnostics')) {
      const kind = name === 'Settings' ? 'get_settings' : 'get_diagnostics';
      const p = panel(name === 'Settings' ? 'Read-only active-profile settings' : 'Redacted app diagnostics — no logs or screenshots'); body.append(p);
      if (name === 'Logs') p.append(el('p', 'Memory and network fields are app-reported observations, not a provider connection test. “Validated” means Android validated the active network; it does not prove a playlist or stream works. Unknown or permission-denied values are not healthy results. Older apps return basic diagnostics only. No extra permission is requested, and no addresses, Wi-Fi names, credentials or raw logs are collected.', 'notice'));
      const request = button('Request fresh snapshot', () => queue(d, kind).catch(e => toast(e.message)), 'primary'); request.disabled = d.policy !== 'active'; p.append(request, el('p', 'The app must be open to respond. This snapshot reports one active profile at execution time, not every profile. Refresh this tab after the next app check-in.', 'hint'));
      try {
        const commands = await api(`/api/devices/${id}/commands`); if (epoch !== state.epoch || !tabs.querySelector(`[data-tab="${name}"]`).classList.contains('active')) return;
        const latest = commands.items.filter(c => (kind === 'get_settings' ? ['get_settings', 'set_settings', 'set_live_buffer'].includes(c.kind) : c.kind === kind) && c.status === 'succeeded' && c.result?.snapshot).sort((a, b) => String(b.finished_at).localeCompare(String(a.finished_at)))[0];
        if (latest) p.append(el('p', 'Snapshot received: ' + date(latest.finished_at), 'subtle'), detailRows(Object.entries(latest.result.snapshot).map(([key, value]) => [key.replaceAll('_', ' '), String(value)])));
        else p.append(el('p', 'No successful snapshot in the latest command-history page. Nothing has been fetched until you request it.', 'subtle'));
        if (name === 'Settings' && d.capabilities.includes('set_live_buffer')) {
          const snapshot = latest?.result.snapshot;
          p.append(el('p', 'Live TV pre-buffer is a device-local setting: Off preserves current fast startup; 2, 5 or 10 seconds waits for more video before playback/recovery. Applies on the next standard live channel tune (including mpv fallback), not the currently playing stream, DRM, catch-up or movies. Larger values may increase startup/live delay; memory limits and provider behaviour can prevent reaching the target. It cannot fix sustained insufficient bandwidth or a bad provider.', 'notice'));
          if (snapshot?.live_buffer_revision) {
            const editBuffer = button('Adjust Live TV pre-buffer', () => modal('Device Live TV pre-buffer', [{ key: 'prebuffer_seconds', label: 'Pre-buffer amount', value: String(snapshot.live_prebuffer_seconds), options: [['0', 'Off — current fast-start default'], ['2', '2 seconds'], ['5', '5 seconds'], ['10', '10 seconds']], hint: 'Try 2 seconds first. This amount of video must be buffered; it is not a guaranteed wall-clock wait. Saving does not restart the current stream.' }], async v => {
              if (!confirm('Save this device’s Live TV pre-buffer for its next standard live channel tune? Other playback settings and the current stream stay unchanged.')) throw new Error('No buffer change queued.');
              await queue(d, 'set_live_buffer', { profile_ref: snapshot.profile_ref, expected_revision: snapshot.live_buffer_revision, prebuffer_seconds: v.prebuffer_seconds });
            }, 'Queue pre-buffer setting')); editBuffer.disabled = d.policy !== 'active'; p.append(editBuffer);
          } else p.append(el('p', 'Request a fresh settings snapshot from app112 before changing the playback buffer.', 'hint'));
        }
        if (name === 'Settings' && d.capabilities.includes('set_settings')) {
          const snapshot = latest?.result.snapshot;
          if (!snapshot?.settings_revision) { p.append(el('p', 'Request a fresh settings snapshot before editing. Updates target its profile and reject a changed local sort order.', 'hint')); }
          else {
            const applySort = sort_order => queue(d, 'set_settings', { profile_ref: snapshot.profile_ref, expected_revision: snapshot.settings_revision, sort_order });
            const edit = button('Change channel sorting', () => modal('Change device channel sorting', [{ key: 'sort_order', label: 'Channel sorting', options: sortOptions, value: snapshot.sort_order }], v => applySort(v.sort_order), 'Queue settings update')); edit.disabled = d.policy !== 'active'; p.append(edit);
            const saved = await api('/api/settings-presets?tenant_id=' + encodeURIComponent(d.tenant_id));
            if (epoch !== state.epoch || !tabs.querySelector('[data-tab="Settings"]').classList.contains('active')) return;
            if (saved.items.length) {
              const apply = button('Apply saved preset', () => modal('Apply channel-sorting preset', [{ key: 'preset_id', label: 'Preset', options: saved.items.map(s => [s.id, s.name]), hint: 'First 25 active presets for this workspace. Applying uses the latest displayed profile/revision; local changes cause a safe conflict.' }], v => applySort(saved.items.find(s => s.id === v.preset_id).sort_order), 'Queue preset application')); apply.disabled = d.policy !== 'active'; p.append(apply);
            }
          }
        }
        if(name==='Settings'&&d.capabilities.includes('get_preferences'))await preferencesPanel(d,p,()=>epoch===state.epoch&&tabs.querySelector('[data-tab="Settings"]').classList.contains('active'),()=>select('Settings'));
        if(name==='Logs'&&d.capabilities.includes('get_support_bundle'))await supportPanel(d,p,()=>epoch===state.epoch&&tabs.querySelector('[data-tab="Logs"]').classList.contains('active'),()=>select('Logs'));
      } catch (e) { toast(e.message); }
    } else if (name === 'Playlists' && d.capabilities.includes('get_sources')) {
      await sourcesPanel(d,body,()=>epoch===state.epoch && tabs.querySelector('[data-tab="Playlists"]').classList.contains('active'),()=>select('Playlists'));
    } else if (name === 'Playlists' && d.capabilities.includes('refresh_playlist')) {
      const actions = el('div', null, 'actions');
      for (const [kind, label] of [['refresh_playlist', 'Refresh playlist'], ['reload_epg', 'Request guide refresh']]) {
        const b = button(label, () => queue(d, kind).catch(e => toast(e.message))); b.disabled = d.policy !== 'active' || !d.capabilities.includes(kind); actions.append(b);
      }
      body.append(panel('Playlist / guide actions', el('p', 'Reload the current profile’s configured playlist or request its shared background guide refresh. Credentials and URLs are not displayed or changed. A requested guide job is not proof of a completed import.', 'subtle'), actions));
    } else if (name === 'Notifications' && d.capabilities.includes('show_message') && d.policy === 'active') {
      body.append(panel('Send a customer message', el('p', 'Visible app message, delivered on the next check-in. It cannot override mandatory updates or maintenance.', 'subtle'), button('Compose message', () => modal('Message device', [{ key: 'message', label: 'Message', type: 'textarea', max: 1000 }, { key: 'priority', label: 'Priority', options: [['normal', 'Normal'], ['urgent', 'Urgent']] }], values => queue(d, 'show_message', values), 'Queue message'), 'primary')));
    } else body.append(panel(name, el('p', 'Not available in the backend foundation yet. Secure app integration and a supported capability are required; no live data has been fetched.', 'subtle')));
  }
  await select(state.deviceTab?.id === id && names.includes(state.deviceTab.name) ? state.deviceTab.name : 'Overview');
}
async function audit(epoch) {
  const main = $('main'); main.replaceChildren(heading('Activity log', 'Who changed what—without passwords, tokens or provider URLs.')); const box = panel(null); main.append(box);
  const load = async (page = 1) => { const result = await api('/api/audit?page=' + page); if (epoch !== state.epoch) return; box.replaceChildren(table(['Time', 'Action', 'Record', 'Details'], result.items.map(r => [date(r.created_at), r.action, el('span', r.resource_id || '—', 'mono'), r.metadata])), pagination(result, load)); };
  await load();
}
async function morePage(epoch){const main=$('main');main.replaceChildren(heading('Workspace tools','Saved configurations, activity and administration—separate from customer playback controls.'));const links=[['Library','Layouts, settings profiles and new-device defaults','library'],['Activity log','See who requested each action and its result','audit'],...(state.user.role==='owner'?[['Resellers & panel links','Verified workspace admission and reseller accounts','resellers'],['Enrollments & website upgrades','Owner enrollment tools and pending database upgrades','enrollments']]:[])];const box=panel('Tools');for(const[title,description,hash]of links){const a=el('a',null,'workspace-tool-link');a.href='#'+hash;a.append(cellName(title,description),el('span','→'));box.append(a);}main.append(box);}
function downloadText(filename, content) {
  const url = URL.createObjectURL(new Blob([content], { type: 'text/plain;charset=utf-8' }));
  const a = el('a'); a.href = url; a.download = filename; document.body.append(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
}
async function approveEnrollment(request) {
  const initial = await api('/api/customers');
  if (!initial.items.length) { toast('Create a customer record first, then approve this enrollment.'); return; }
  const options = initial.items.map(c => [c.id, c.name + ' · ' + c.tenant_name]);
  modal('Approve verified device', [
    { key: 'customer_search', label: 'Find customer / account reference', required: false, hint: 'Search by customer name or your account reference. Ownership is assigned by you, not by the app.' },
    { key: 'customer_id', label: 'Assign to customer', options },
    { key: 'label', label: 'Device nickname', value: request.label },
    { key: 'device_id', label: 'Existing reserved device ID (optional)', required: false, max: 32, hint: 'Leave blank for a new device, or paste a matching pending record ID. Device allocations are enforced.' }
  ], async values => {
    delete values.customer_search;
    if (!values.device_id) delete values.device_id;
    await api('/api/enrollments/' + request.id + '/approve', values);
    toast('Approved. The app receives its own credential on its next check-in.');
  }, 'Approve device');
  const form = $('modal-form'); const input = form.elements.namedItem('customer_search'); const select = form.elements.namedItem('customer_id');
  let generation = 0; let timer;
  input.addEventListener('input', () => {
    clearTimeout(timer); const current = ++generation;
    timer = setTimeout(async () => {
      try {
        const result = await api('/api/customers?search=' + encodeURIComponent(input.value));
        if (current !== generation || !select.isConnected) return;
        select.replaceChildren();
        for (const c of result.items) { const option = el('option', c.name + ' · ' + c.tenant_name); option.value = c.id; select.append(option); }
        if (!result.items.length) { const option = el('option', 'No matching customers'); option.value = ''; select.append(option); }
      } catch (e) { toast(e.message); }
    }, 300);
  });
}
async function enrollments(epoch) {
  const main = $('main'); main.replaceChildren(heading('Device enrollments', 'Only created, verified linked resellers are admitted. This list contains admitted ownership/allocation exceptions—not unmatched reseller devices.'));
  const system = await api('/api/system/status'); if (epoch !== state.epoch) return;
  if (system.pending_migrations.length) {
    main.append(panel('Database upgrade required', el('p', 'This release applies versioned schema changes without replacing existing accounts, devices or configuration. Back up the database and private storage before upgrading.', 'subtle'),
      button('Apply database upgrade', async () => {
        if (!confirm('Have you backed up the control-center database, config.php and private storage? Apply the versioned database upgrade now?')) return;
        try { await api('/api/system/migrate', {}); toast('Database upgraded.'); await route(); } catch (e) { toast(e.message); }
      }, 'primary')));
    return;
  }
  const settings = await api('/api/enrollments/settings'); if (epoch !== state.epoch) return;
  const actions = el('div', null, 'actions');
  actions.append(button('Download worker bridge settings', async () => {
    try { const file = await api('/api/enrollments/bridge-configuration', {}); downloadText(file.filename, file.content); toast('Append these settings to the activation worker’s existing .env, then restart that worker after updating its code.'); }
    catch (e) { toast(e.message); }
  }), button(settings.enabled ? 'Pause new enrollment' : 'Enable new enrollment', async () => {
    if (!confirm(settings.enabled ? 'Pause new enrollment? Existing enrolled devices continue to check in.' : 'Have you updated the activation worker and added its downloaded bridge settings? Enable owner-reviewed enrollment?')) return;
    try { await api('/api/enrollments/settings', { enabled: !settings.enabled }); await route(); } catch (e) { toast(e.message); }
  }, settings.enabled ? '' : 'primary'));
  main.append(panel('Activation bridge', el('p', 'The existing activation worker verifies active customer logins and issues a short-lived assertion. The app also proves possession of its own device key. No signing secret is embedded in APKs, and resellers cannot claim unassigned accounts.', 'subtle'),
    el('p', settings.enabled ? 'New enrollment is enabled for created and linked resellers only. Unmatched resellers create no pending/device/customer record. Admitted ownership or allocation conflicts may require review.' : 'New enrollment is paused. Configure the worker bridge before enabling it.', 'notice'), actions));
  const box = panel('Requests'); const filters = el('form', null, 'filters'); const status = el('select'); status.setAttribute('aria-label', 'Enrollment status');
  for (const value of ['pending', 'approved', 'rejected']) { const option = el('option', value[0].toUpperCase() + value.slice(1)); option.value = value; status.append(option); }
  filters.append(status, button('Refresh requests', () => load(1))); filters.onsubmit = e => { e.preventDefault(); load(1); }; status.addEventListener('change', () => load(1));
  const results = el('div'); box.append(filters, results); main.append(box);
  async function load(page = 1) {
    try {
      const data = await api(`/api/enrollments?page=${page}&status=${status.value}`); if (epoch !== state.epoch) return;
      const rows = data.items.map(r => {
        const a = el('div', null, 'actions');
        if (r.status !== 'approved') {
          a.append(button('Approve / assign', () => approveEnrollment(r).catch(e => toast(e.message))));
          if (r.status === 'pending') a.append(button('Reject', async () => {
            if (!confirm('Reject this management enrollment request? This does not erase the app’s saved data or change its IPTV activation.')) return;
            try { await api('/api/enrollments/' + r.id + '/reject', {}); await load(page); } catch (e) { toast(e.message); }
          }, 'danger'));
        } else if (r.device_id) a.append(button('View device', () => { location.hash = 'device/' + r.device_id; }));
        return [cellName(r.label, r.installation_id), cellName(r.model || 'App-reported device', r.os_version), r.account_hint, cellName(r.app_version, r.app_package), badge(r.status), date(r.last_request_at), a];
      });
      const roster = table(['Device / installation', 'Reported details', 'Verified account', 'App build', 'Review', 'Last request', 'Actions'], rows);
      roster.classList.add('enrollment-table');
      results.replaceChildren(roster, pagination(data, load));
    } catch (e) { toast(e.message); }
  }
  await load();
}
async function route() {
  $('main').classList.remove('fleet-page');
  if (!state.user) return; const epoch = ++state.epoch; const hash = location.hash.slice(1) || 'overview'; const section = hash.startsWith('device/')||hash.startsWith('devices/') ? 'devices' : hash.startsWith('library')||hash==='presets'?'library':hash.startsWith('branding')?'branding':hash.startsWith('trials')?'trials':['resellers','enrollments','audit'].includes(hash)?'more':hash;
  const navKey=hash==='presets'?'library/settings':hash.startsWith('library/layout/')?'library/layout/new':hash.startsWith('library/layouts')?'library':hash.startsWith('library/')?hash.split('/').slice(0,2).join('/'):['resellers','enrollments','audit'].includes(hash)?hash:section;
  document.querySelectorAll('#navigation a').forEach(a => a.classList.toggle('active', a.dataset.page === navKey));
  document.querySelectorAll('#bottom-nav a').forEach(a=>{const key=a.getAttribute('href').slice(1);a.classList.toggle('active',key===section||(key==='more'&&section==='library'));});
  document.querySelectorAll('#navigation a').forEach(a=>{if(a.dataset.page===navKey)a.setAttribute('aria-current','page');else a.removeAttribute('aria-current');});const titles={overview:'Home',devices:'Devices',customers:'Customers',library:'Library',more:'Workspace tools',presets:'Library',resellers:'Resellers',enrollments:'Enrollments & upgrades',audit:'Activity log'};$('section-name').textContent=hash.startsWith('device/')?'Device details':titles[hash]||titles[section]||'Workspace';document.title=$('section-name').textContent+' · Extreme Control';
  try {
    if (hash === 'overview') await overview(epoch);
    else if (hash === 'devices' || hash === 'customers') await records(hash, epoch);
    else if (hash === 'resellers' && state.user.role === 'owner') await resellers(epoch);
    else if (hash === 'audit') await audit(epoch);
    else if (hash === 'presets'||hash.startsWith('library')) {if(window.WorkspaceLibrary)await WorkspaceLibrary.render(hash,epoch);else await presetsPage(epoch);}
    else if(hash==='more')await morePage(epoch);
    else if(hash==='branding'||/^branding\/[a-f0-9]{32}$/.test(hash)){document.title='My app brand · Extreme Control';await AppBranding.render(epoch,hash.split('/')[1]);}
    else if(hash==='trials'||/^trials\/[a-f0-9]{32}$/.test(hash)){document.title='Trials & contacts · Extreme Control';await TrialWorkspace.render(epoch,hash.split('/')[1]);}
    else if(/^devices\/customer\/[a-f0-9]{32}$/.test(hash))await records('devices',epoch,hash.split('/')[2]);
    else if (hash === 'enrollments' && state.user.role === 'owner') await enrollments(epoch);
    else if (/^device\/[a-f0-9]{32}$/.test(hash)) await devicePage(hash.split('/')[1], epoch);
    else location.hash = 'overview';
  } catch (e) { if (epoch === state.epoch) { $('main').replaceChildren(panel('Unable to load this view', el('p', e.message, 'error'), button('Retry', route))); } }
}
$('modal-close').addEventListener('click', closeModal);
$('modal').addEventListener('close', () => { $('modal-form').replaceChildren(); $('modal-form').onsubmit = null; });
function hasUnsentDrafts(){return [...state.layoutEditors.values()].some(o=>o.draft?.dirty)||[...state.sourceEditors.values(),...state.preferenceEditors.values()].some(o=>o.dirty)||window.DeviceWorkspace?.isDirty()||window.WorkspaceLibrary?.isDirty()||window.AppBranding?.isDirty()||window.TrialWorkspace?.isDirty();}
$('logout-button').addEventListener('click', async () => { if (hasUnsentDrafts() && !confirm('Discard unsent browser drafts and sign out? Save reusable layouts first if needed.')) return; try { await api('/api/logout', {}); signedOut(); } catch (e) { toast(e.message); } });
window.addEventListener('beforeunload', e => { if (hasUnsentDrafts()) { e.preventDefault(); e.returnValue = ''; } });
$('password-button').addEventListener('click', () => modal('Change your password', [{ key: 'current_password', label: 'Current password', type: 'password' }, { key: 'new_password', label: 'New password', type: 'password', hint: 'Changing your password signs out every session, including this one.' }], async values => { await api('/api/password', values); signedOut(); toast('Password changed. Sign in again.'); }, 'Change password'));
window.addEventListener('hashchange', route);
(async () => {
  try {
    const status = await api('/api/status');
    if (!status.configured) { const card = entryCard('Website files are ready', 'Enter your database password to finish configuration.'); card.append(el('p', 'Open config.php beside the public folder, enter your database password, save, and reload this page. No copying or renaming is needed.', 'notice')); return; }
    if (!status.installed) { setupForm(); return; }
    try { const session = await api('/api/me'); state.user = session.user; state.csrf = session.csrf; await enter(); } catch (e) { if (e.status === 401) loginForm(); else throw e; }
  } catch (e) { const card = entryCard('Connection needs attention', e.message); card.append(button('Retry connection', () => location.reload(), 'primary')); }
})();
