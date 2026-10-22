'use strict';

const $ = id => document.getElementById(id);
const state = { user: null, csrf: '', tenants: [], epoch: 0 };
const requestKeys = new Map();
let toastTimer;
let formCounter = 0;
function el(tag, text, className = '') { const n = document.createElement(tag); if (text !== null && text !== undefined) n.textContent = String(text); if (className) n.className = className; return n; }
function button(text, action, className = '') { const n = el('button', text, className); n.type = 'button'; n.addEventListener('click', action); return n; }
function toast(message) { $('toast').textContent = message; $('toast').hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => { $('toast').hidden = true; }, 6000); }
function badge(value) { return el('span', String(value).replaceAll('_', ' '), 'badge ' + String(value).replace(/[^a-z_]/g, '')); }
function date(value) { return value ? new Date(value.replace(' ', 'T') + 'Z').toLocaleString() : 'Never'; }
function uuid() { return crypto.randomUUID ? crypto.randomUUID() : Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join(''); }
async function api(path, body, extra = {}) {
  const options = { method: body === undefined ? 'GET' : 'POST', credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json', ...extra } };
  if (body !== undefined) { options.headers['Content-Type'] = 'application/json'; options.headers['X-CSRF-Token'] = state.csrf; options.body = JSON.stringify(body); }
  const response = await fetch(path, options);
  let data;
  try { data = await response.json(); } catch { throw new Error('Server response was not JSON. Check the PHP/Nginx configuration.'); }
  if (!response.ok) {
    if (response.status === 401 && path !== '/api/login') signedOut();
    const error = new Error((data.error || 'Request failed.') + (data.request_id ? ' Request ID: ' + data.request_id : '')); error.status = response.status; throw error;
  }
  return data;
}
function panel(title, ...children) { const n = el('section', null, 'panel'); if (title) n.append(el('h2', title)); n.append(...children); return n; }
function heading(title, description, actions = []) { const h = el('div', null, 'page-heading'); const left = el('div'); left.append(el('h1', title), el('p', description)); const right = el('div', null, 'actions'); right.append(...actions); h.append(left, right); return h; }
function cellName(title, subtitle) { const n = el('div'); n.append(el('span', title, 'cell-name')); if (subtitle) n.append(el('span', subtitle, 'cell-sub')); return n; }
function table(headers, rows) {
  const wrap = el('div', null, 'table-scroll'); const t = el('table'); const head = el('thead'); const hr = el('tr'); headers.forEach(h => hr.append(el('th', h))); head.append(hr); t.append(head);
  const body = el('tbody'); rows.forEach(cells => { const row = el('tr'); cells.forEach(value => { const td = el('td'); td.append(value instanceof Node ? value : document.createTextNode(String(value ?? '—'))); row.append(td); }); body.append(row); });
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
    if (field.type === 'password') { input.autocomplete = field.key === 'current_password' ? 'current-password' : 'new-password'; input.minLength = 14; input.maxLength = 72; }
    else if (input.tagName !== 'SELECT') input.maxLength = field.max || (field.type === 'textarea' ? 2000 : 120);
    if (field.type === 'number') { input.min = 1; input.max = 100000; input.step = 1; }
    if (field.options) for (const [value, name] of field.options) { const option = el('option', name); option.value = value; input.append(option); }
    input.required = field.required !== false; if (field.value !== undefined && field.value !== null) input.value = field.value;
    form.append(label, input); if (field.hint) form.append(el('p', field.hint, 'hint'));
  }
}
function formValues(form, schema) { const values = {}; for (const f of schema) values[f.key] = f.type === 'number' ? Number(form.elements.namedItem(f.key).value) : form.elements.namedItem(f.key).value; return values; }
function modal(title, schema, save, label = 'Save') {
  $('modal-title').textContent = title; const form = $('modal-form'); form.replaceChildren(); fields(form, schema); const error = el('p', '', 'error'); const actions = el('div', null, 'form-actions'); const submit = el('button', label, 'primary'); submit.type = 'submit'; actions.append(button('Cancel', () => $('modal').close()), submit); form.append(error, actions);
  form.onsubmit = async event => { event.preventDefault(); submit.disabled = true; error.textContent = ''; try { await save(formValues(form, schema)); $('modal').close(); await route(); } catch (e) { error.textContent = e.message; } finally { submit.disabled = false; } };
  $('modal').showModal();
}
function signedOut() { state.user = null; state.csrf = ''; state.tenants = []; state.epoch++; requestKeys.clear(); if ($('modal').open) $('modal').close(); $('modal-form').replaceChildren(); $('main').replaceChildren(); $('navigation').replaceChildren(); $('account-name').textContent = ''; $('workspace-name').textContent = ''; $('shell').hidden = true; $('entry').hidden = false; loginForm(); }
function entryCard(title, description) { const card = el('div', null, 'entry-card'); card.append(el('span', 'E', 'brand-mark'), el('h1', title), el('p', description, 'subtle')); $('entry').replaceChildren(card); return card; }
function loginForm() {
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
  const links = [['overview', 'Overview'], ['devices', 'Devices'], ['customers', 'Customers'], ...(state.user.role === 'owner' ? [['resellers', 'Resellers'], ['enrollments', 'Enrollments']] : []), ['audit', 'Activity log']];
  links.forEach(([key, text]) => { const link = el('a', text); link.href = '#' + key; link.dataset.page = key; nav.append(link); });
  await route();
}
function tenantField() { return { key: 'tenant_id', label: 'Workspace / reseller', options: state.tenants.filter(t => t.status === 'active').map(t => [t.id, t.name]), hint: 'First page of workspaces. For others, search Resellers and choose Add customer on their row.' }; }
function customerForm(customer, tenant = null) {
  const schema = [...(!customer && !tenant && state.user.role === 'owner' ? [tenantField()] : []), { key: 'name', label: 'Customer name', value: customer?.name }, { key: 'external_reference', label: 'Account reference (optional)', value: customer?.external_reference, required: false, max: 128, hint: 'A customer/billing reference—not a provider password.' }, { key: 'notes', label: 'Private notes (optional)', type: 'textarea', value: customer?.notes, required: false }];
  modal(customer ? 'Edit customer' : 'Add customer' + (tenant ? ' · ' + tenant.name : ''), schema, async values => { if (tenant) values.tenant_id = tenant.id; await api(customer ? '/api/customers/' + customer.id : '/api/customers', values); toast('Customer saved.'); });
}
function deviceForm(customer) { modal('Provision device record', [{ key: 'label', label: 'Device nickname' }, { key: 'notes', label: 'Private notes (optional)', type: 'textarea', required: false, hint: 'This creates a pending record only. It does not automatically pair an existing app.' }], async values => { await api('/api/devices', { ...values, customer_id: customer.id }); toast('Device record created; secure app enrollment is still pending.'); }); }
function resellerForm() { modal('Create reseller', [{ key: 'company', label: 'Company / workspace name' }, { key: 'panel_username', label: 'Existing panel reseller username', required: false, max: 150, hint: 'Exact panel login username. Verified server-side and permanently linked by ID. Leave blank only for a workspace without automatic enrollment.' }, { key: 'name', label: 'Contact name' }, { key: 'email', label: 'Reseller email', type: 'email', max: 254 }, { key: 'password', label: 'Initial password', type: 'password', hint: 'Share securely. The reseller can change this after signing in.' }, { key: 'device_limit', label: 'Device allocation', type: 'number', value: 100 }], async values => { await api('/api/resellers', values); state.tenants = (await api('/api/tenants')).items; toast('Reseller workspace created.'); }); }
function connectionBadge(device) { const n = el('div'); n.append(badge(device.presence)); if (device.is_test) n.append(el('span', 'TEST', 'test-label')); return n; }
function devicesTable(items) { return table(['Device', 'Customer / reseller', 'Policy', 'Connection', 'Last check-in', 'Version', ''], items.map(d => [cellName(d.label, d.id), cellName(d.customer_name, d.tenant_name), badge(d.policy), connectionBadge(d), date(d.last_seen_at), d.app_version || '—', button('View →', () => { location.hash = 'device/' + d.id; })])); }
async function overview(epoch) {
  const [stats, devices] = await Promise.all([api('/api/overview'), api('/api/devices')]); if (epoch !== state.epoch) return;
  const main = $('main'); main.replaceChildren(heading('Operations overview', 'Your customers and devices, in one workspace.', [button('Refresh', route)]));
  main.append(el('p', 'Management-enabled apps enroll automatically when their verified panel reseller is linked. Unmatched requests appear under Enrollments for review. Older app builds are not automatically imported. Local simulators remain labelled TEST.', 'notice'));
  const cards = el('div', null, 'stats'); [['Managed devices', stats.total, `${stats.not_enrolled} awaiting enrollment`], ['Recently connected', stats.connected, 'App check-ins, not device power state'], ['Customers', stats.customers, 'Visible to your account only'], ['Queued / in progress', stats.pending_commands, 'Delivery is not confirmation of execution']].forEach(([label, count, note]) => { const card = el('div', null, 'stat'); card.append(el('div', label, 'stat-label'), el('strong', count), el('span', note)); cards.append(card); });
  main.append(cards, panel('Recent devices', devicesTable(devices.items)), panel('One update source of truth', el('p', 'Every customer and reseller app uses the platform owner’s update channel. Resellers cannot upload APKs, change update URLs or disable mandatory updates.', 'subtle')));
}
async function records(resource, epoch) {
  const main = $('main'); main.replaceChildren(heading(resource === 'devices' ? 'Devices' : 'Customers', 'Search, manage and inspect your assigned records.', resource === 'customers' ? [button('Add customer', () => customerForm(), 'primary')] : []));
  const box = panel(null); const filters = el('form', null, 'filters'); const search = el('input'); search.placeholder = resource === 'devices' ? 'Search device, model, customer or ID…' : 'Search customer or reference…'; search.setAttribute('aria-label', 'Search');
  filters.append(search, button('Search', () => load(1))); filters.onsubmit = e => { e.preventDefault(); load(1); }; const resultBox = el('div'); box.append(filters, resultBox); main.append(box);
  async function load(page = 1) {
    try {
      const data = await api(`/api/${resource}?page=${page}&search=${encodeURIComponent(search.value)}`); if (epoch !== state.epoch) return;
      const content = resource === 'devices' ? devicesTable(data.items) : table(['Customer', 'Workspace', 'Reference', 'Actions'], data.items.map(c => { const actions = el('div', null, 'actions'); actions.append(button('Edit', () => customerForm(c)), button('Add device', () => deviceForm(c))); return [cellName(c.name, c.notes), c.tenant_name, c.external_reference || '—', actions]; }));
      resultBox.replaceChildren(content, pagination(data, load));
    } catch (e) { toast(e.message); }
  }
  await load();
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
        const actions = el('div', null, 'actions'); const add = button('Add customer', () => customerForm(null, t)); add.disabled = t.status !== 'active'; actions.append(add);
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
async function queue(device, kind, payload = {}) {
  if (['clear_cache', 'restart_app'].includes(kind) && !confirm('Request this app action? It may interrupt playback and can only run when the app is connected. Clear cache must preserve login, favourites and saved guides.')) return;
  const fingerprint = JSON.stringify([device.id, kind, payload]); const key = requestKeys.get(fingerprint) || uuid(); requestKeys.set(fingerprint, key);
  try { await api(`/api/devices/${device.id}/commands`, { kind, payload }, { 'Idempotency-Key': key }); requestKeys.delete(fingerprint); toast('Action queued—not yet executed.'); await route(); }
  catch (error) { if (error.status && error.status < 500) requestKeys.delete(fingerprint); throw error; }
}
async function devicePage(id, epoch) {
  const d = await api('/api/devices/' + id); if (epoch !== state.epoch) return;
  const main = $('main'); main.replaceChildren(); const back = el('a', '← All devices', 'back-link'); back.href = '#devices'; main.append(back, heading(d.label, d.customer_name + ' · ' + d.tenant_name, [button('Refresh', route), badge(d.presence)]));
  if (d.is_test) main.append(el('p', 'LOCAL TEST SIMULATOR — not a real customer device.', 'notice warning'));
  if (!d.enrolled_at) main.append(el('p', 'Reserved device record awaiting approved enrollment. It is not an online app connection. The owner can attach a verified enrollment request using this record ID.', 'notice'));
  const tabs = el('div', null, 'tabs'); const body = el('div'); main.append(tabs, body);
  const names = ['Overview', 'Playlists', 'Settings', 'Categories', 'Favourites', 'Notifications', 'Logs', 'Commands'];
  for (const name of names) { const b = button(name, () => select(name)); b.dataset.tab = name; tabs.append(b); }
  async function select(name) {
    tabs.querySelectorAll('button').forEach(b => b.classList.toggle('active', b.dataset.tab === name)); body.replaceChildren();
    if (name === 'Overview') {
      const actions = el('div', null, 'actions'); const kinds = [['refresh_playlist', 'Refresh playlist'], ['reload_epg', 'Reload EPG'], ['request_checkin', 'Request check-in'], ['clear_cache', 'Clear expendable cache'], ['restart_app', 'Restart app']];
      kinds.forEach(([kind, label]) => { const b = button(label, () => queue(d, kind).catch(e => toast(e.message))); b.disabled = d.policy !== 'active' || !d.capabilities.includes(kind); b.title = b.disabled ? 'Requires an enrolled app advertising this capability.' : 'Queues an expiring action; it cannot wake an offline app.'; actions.append(b); });
      body.append(panel('Quick actions', actions, el('p', 'Commands are queued until check-in. Restarting/reopening remains subject to Android/OEM policy.', 'hint')));
      const columns = el('div', null, 'columns'); columns.append(panel('Device information', detailRows([['Installation record', d.id], ['Model', d.model], ['OS version', d.os_version], ['App version', d.app_version], ['Storage free / total', d.storage_total === null ? null : `${(d.storage_free / 1073741824).toFixed(1)} / ${(d.storage_total / 1073741824).toFixed(1)} GB`], ['Capabilities', d.capabilities.join(', ') || 'None reported']])), panel('Connection & policy', detailRows([['Policy', d.policy], ['Connection', d.presence.replaceAll('_', ' ')], ['First seen / provisioned', date(d.created_at)], ['Last app check-in', date(d.last_seen_at)], ['Observed IP', d.last_ip]]))); body.append(columns);
      const policyActions = el('div', null, 'actions'); policyActions.append(button('Edit notes', () => modal('Device details', [{ key: 'label', label: 'Nickname', value: d.label }, { key: 'notes', label: 'Private notes', type: 'textarea', value: d.notes, required: false }], async values => { await api('/api/devices/' + d.id, values); toast('Device details saved.'); })));
      const policies = d.policy === 'removed' ? [] : [...(d.policy === 'active' ? [['suspended', 'Suspend app']] : (d.policy !== 'blocked' || state.user.role === 'owner' ? [['active', 'Reactivate']] : [])), ...(state.user.role === 'owner' ? [['blocked', 'Block'], ['removed', 'Remove device']] : [])];
      policies.forEach(([policy, label]) => policyActions.append(button(label, async () => { if (!confirm(`${label}? This changes service policy, not the whole Android device. It applies to connected management clients and does not erase app data.`)) return; try { await api('/api/devices/' + d.id, { policy }); toast('Policy saved.'); await route(); } catch (e) { toast(e.message); } }, policy === 'active' ? '' : 'danger')));
      body.append(panel('Private notes & management policy', el('p', d.notes || 'No notes yet.', 'notes'), policyActions));
    } else if (name === 'Commands') {
      const p = panel('Command history'); body.append(p);
      const load = async (page = 1) => { const result = await api(`/api/devices/${id}/commands?page=${page}`); if (epoch !== state.epoch || !tabs.querySelector('[data-tab="Commands"]').classList.contains('active')) return; p.replaceChildren(el('h2', 'Command history'), table(['Action', 'Status', 'Attempts', 'Requested', 'Result'], result.items.map(c => [c.kind.replaceAll('_', ' '), badge(c.status), c.attempts, date(c.created_at), c.result?.summary || '—'])), pagination(result, load)); };
      try { await load(); } catch (e) { toast(e.message); }
    } else if (name === 'Notifications' && d.capabilities.includes('show_message') && d.policy === 'active') {
      body.append(panel('Send a customer message', el('p', 'Visible app message, delivered on the next check-in. It cannot override mandatory updates or maintenance.', 'subtle'), button('Compose message', () => modal('Message device', [{ key: 'message', label: 'Message', type: 'textarea', max: 1000 }, { key: 'priority', label: 'Priority', options: [['normal', 'Normal'], ['urgent', 'Urgent']] }], values => queue(d, 'show_message', values), 'Queue message'), 'primary')));
    } else body.append(panel(name, el('p', 'Not available in the backend foundation yet. Secure app integration and a supported capability are required; no live data has been fetched.', 'subtle')));
  }
  await select('Overview');
}
async function audit(epoch) {
  const main = $('main'); main.replaceChildren(heading('Activity log', 'Who changed what—without passwords, tokens or provider URLs.')); const box = panel(null); main.append(box);
  const load = async (page = 1) => { const result = await api('/api/audit?page=' + page); if (epoch !== state.epoch) return; box.replaceChildren(table(['Time', 'Action', 'Record', 'Details'], result.items.map(r => [date(r.created_at), r.action, el('span', r.resource_id || '—', 'mono'), r.metadata])), pagination(result, load)); };
  await load();
}
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
  const main = $('main'); main.replaceChildren(heading('Device enrollments', 'Linked verified panel accounts enroll automatically. Review unmatched requests and ownership exceptions here.'));
  const system = await api('/api/system/status'); if (epoch !== state.epoch) return;
  if (system.pending_migrations.length) {
    main.append(panel('Database upgrade required', el('p', 'This release adds enrollment tables without replacing existing accounts, devices or configuration. Back up the database and private storage before upgrading.', 'subtle'),
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
    el('p', settings.enabled ? 'New enrollment is enabled. Devices from verified linked panel resellers are assigned automatically. Unmatched identities and ownership conflicts require review.' : 'New enrollment is paused. Configure the worker bridge before enabling it.', 'notice'), actions));
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
  if (!state.user) return; const epoch = ++state.epoch; const hash = location.hash.slice(1) || 'overview'; const section = hash.startsWith('device/') ? 'devices' : hash;
  document.querySelectorAll('#navigation a').forEach(a => a.classList.toggle('active', a.dataset.page === section));
  try {
    if (hash === 'overview') await overview(epoch);
    else if (hash === 'devices' || hash === 'customers') await records(hash, epoch);
    else if (hash === 'resellers' && state.user.role === 'owner') await resellers(epoch);
    else if (hash === 'audit') await audit(epoch);
    else if (hash === 'enrollments' && state.user.role === 'owner') await enrollments(epoch);
    else if (/^device\/[a-f0-9]{32}$/.test(hash)) await devicePage(hash.split('/')[1], epoch);
    else location.hash = 'overview';
  } catch (e) { if (epoch === state.epoch) { $('main').replaceChildren(panel('Unable to load this view', el('p', e.message, 'error'), button('Retry', route))); } }
}
$('modal-close').addEventListener('click', () => $('modal').close());
$('modal').addEventListener('close', () => { $('modal-form').replaceChildren(); $('modal-form').onsubmit = null; });
$('logout-button').addEventListener('click', async () => { try { await api('/api/logout', {}); signedOut(); } catch (e) { toast(e.message); } });
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
