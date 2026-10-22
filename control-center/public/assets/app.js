'use strict';

const $ = id => document.getElementById(id);
const state = { user: null, csrf: '', tenants: [], epoch: 0, deviceTab: null, layoutEditors: new Map(), favouritesSelection: new Map() };
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
function signedOut() { state.user = null; state.csrf = ''; state.tenants = []; state.deviceTab = null; state.layoutEditors.clear(); state.favouritesSelection.clear(); state.epoch++; requestKeys.clear(); if ($('modal').open) $('modal').close(); $('modal-form').replaceChildren(); $('main').replaceChildren(); $('navigation').replaceChildren(); $('account-name').textContent = ''; $('workspace-name').textContent = ''; $('shell').hidden = true; $('entry').hidden = false; loginForm(); }
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
  const links = [['overview', 'Overview'], ['devices', 'Devices'], ['customers', 'Customers'], ['presets', 'Settings presets'], ...(state.user.role === 'owner' ? [['resellers', 'Resellers'], ['enrollments', 'Enrollments']] : []), ['audit', 'Activity log']];
  links.forEach(([key, text]) => { const link = el('a', text); link.href = '#' + key; link.dataset.page = key; nav.append(link); });
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
function devicesTable(items) { return table(['Device', 'Customer / reseller', 'Policy', 'Connection', 'Last check-in', 'Version', ''], items.map(d => [cellName(d.label, d.id), cellName(d.customer_name, d.tenant_name), badge(d.policy), connectionBadge(d), date(d.last_seen_at), d.app_version || '—', button('View →', () => { location.hash = 'device/' + d.id; })])); }
async function overview(epoch) {
  const [stats, devices] = await Promise.all([api('/api/overview'), api('/api/devices')]); if (epoch !== state.epoch) return;
  const main = $('main'); main.replaceChildren(heading('Operations overview', 'Your customers and devices, in one workspace.', [button('Refresh', route)]));
  main.append(el('p', 'Only devices from created, verified linked resellers enter this service. Unmatched resellers are quietly ignored: no pending device or customer is created. Existing owner-managed/unassigned inventory is retained. Local simulators remain labelled TEST.', 'notice'));
  const cards = el('div', null, 'stats'); [['Managed devices', stats.total, `${stats.not_enrolled} awaiting enrollment`], ['Recently connected', stats.connected, 'App check-ins, not device power state'], ['Customers', stats.customers, 'Visible to your account only'], ['Queued / in progress', stats.pending_commands, 'Delivery is not confirmation of execution']].forEach(([label, count, note]) => { const card = el('div', null, 'stat'); card.append(el('div', label, 'stat-label'), el('strong', count), el('span', note)); cards.append(card); });
  main.append(cards, panel('Recent devices', devicesTable(devices.items)), panel('One update source of truth', el('p', 'Every customer and reseller app uses the platform owner’s update channel. Resellers cannot upload APKs, change update URLs or disable mandatory updates.', 'subtle')));
}
async function records(resource, epoch) {
  const main = $('main'); main.replaceChildren(heading(resource === 'devices' ? 'Devices' : 'Customers', resource === 'customers' ? 'Customers are created by verified enrollment. Line usernames are verified on app check-in; refresh after the 111 app reports in.' : 'Search, manage and inspect your assigned records.', [button('Refresh', route)]));
  const box = panel(null); const filters = el('form', null, 'filters'); const search = el('input'); search.placeholder = resource === 'devices' ? 'Search device, model, customer or ID…' : 'Search customer or reference…'; search.setAttribute('aria-label', 'Search');
  filters.append(search, button('Search', () => load(1))); filters.onsubmit = e => { e.preventDefault(); load(1); }; const resultBox = el('div'); box.append(filters, resultBox); main.append(box);
  async function load(page = 1) {
    try {
      const data = await api(`/api/${resource}?page=${page}&search=${encodeURIComponent(search.value)}`); if (epoch !== state.epoch) return;
       const content = resource === 'devices' ? devicesTable(data.items) : table(['Line username / customer', 'Workspace', 'Reference', 'Actions'], data.items.map(c => { const actions = el('div', null, 'actions'); actions.append(button('Edit', () => customerForm(c)), button('Add device', () => deviceForm(c))); const name = c.line_username || (/^Panel line [0-9]+$/.test(c.name) ? 'Username awaiting app check-in' : c.name); return [cellName(name, c.notes), c.tenant_name, c.external_reference || '—', actions]; }));
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
        const actions = el('div', null, 'actions');
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
function presetForm(preset = null) {
  modal(preset ? 'Edit settings preset' : 'Create settings preset', [
    ...(!preset && state.user.role === 'owner' ? [tenantField()] : []),
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
  if (['clear_cache', 'restart_app'].includes(kind) && !confirm('Request this app action? It may interrupt playback and can only run when the app is connected. Clear cache must preserve login, favourites and saved guides.')) return;
  const fingerprint = JSON.stringify([device.id, kind, payload]); const key = requestKeys.get(fingerprint) || uuid(); requestKeys.set(fingerprint, key);
  try { await api(`/api/devices/${device.id}/commands`, { kind, payload }, { 'Idempotency-Key': key }); requestKeys.delete(fingerprint); toast('Action queued—not yet executed.'); await route(); }
  catch (error) { if (error.status && error.status < 500) requestKeys.delete(fingerprint); throw error; }
}
async function favouritesPanel(d, body, isCurrent, reload) {
  const box = panel('Favourites backups & recovery'); body.append(box);
  box.append(el('p', 'Good nonempty versions are saved automatically while the managed app is open. Clearing favourites updates live status only—it never replaces a good backup. Recovery requires a version captured BEFORE the mistake.', 'subtle'));
  const actions = el('div', null, 'actions'); const capture = button('Back up current favourites now', () => queue(d, 'backup_favourites').catch(e => toast(e.message)), 'primary'); capture.disabled = d.policy !== 'active' || !d.capabilities.includes('backup_favourites'); actions.append(capture, button('Refresh backups / results', reload)); box.append(actions);
  if (!d.capabilities.includes('backup_favourites')) box.append(el('p', 'Update the testing app to 111 and let it check in to enable automatic backups and recovery. Existing saved server versions stay available.', 'notice'));
  let data; try { data = await api(`/api/devices/${d.id}/favourites`); } catch (e) { box.append(el('p', e.message + ' Apply the database upgrade under Enrollments first.', 'error')); return; } if (!isCurrent()) return;
  if (data.live.length) box.append(panel('Last app-reported favourites state', table(['Profile', 'Channels', 'Groups', 'Reported'], data.live.map(r => [r.profile_ref.slice(0, 12), r.channel_count, r.group_count, date(r.updated_at)]))));
  if (data.commands.length) box.append(table(['Recovery action', 'Status', 'Result'], data.commands.slice(0, 4).map(c => [c.kind.replaceAll('_', ' '), badge(c.status), c.result?.summary || 'Waiting for app check-in'])));
  const chosen = state.favouritesSelection.get(d.id) || data.items[0]?.id; if (chosen) state.favouritesSelection.set(d.id, chosen);
  box.append(table(['Saved version', 'Favourites', 'Protection', 'Profile/source', 'Actions'], data.items.map(b => {
    const a = el('div', null, 'actions'); a.append(button('View / recover', () => { state.favouritesSelection.set(d.id, b.id); reload(); }), button('Label / protect', () => modal('Protect favourites backup', [
      { key: 'label', label: 'Backup label', max: 80, required: false, value: b.label },
      { key: 'protection', label: 'Protection', value: b.protected ? 'yes' : 'no', options: [['yes', 'Protected — never auto-pruned'], ['no', 'Rolling history — may be pruned']] }
    ], async v => { if (v.protection === 'no' && b.protected && !confirm('Unprotect this good version? It may later be pruned, but the app is not changed.')) throw new Error('No changes made.'); await api(`/api/devices/${d.id}/favourites/backups/${b.id}/protect`, { label: v.label, protected: v.protection === 'yes' }); })));
    return [cellName(b.label || 'Saved favourites', date(b.created_at)), `${b.channel_count} channels · ${b.group_count} groups`, badge(b.protected ? 'protected' : 'rolling'), cellName(b.profile_ref.slice(0, 12), b.source_ref.slice(0, 12)), a];
  })));
  if (!data.items.length) box.append(el('p', 'No nonempty backup has been captured yet. This service cannot reconstruct favourites already deleted before its first capture.', 'notice'));
  if (chosen) {
    let backup, previewData;
    try { [backup, previewData] = await Promise.all([api(`/api/devices/${d.id}/favourites/backups/${chosen}`), api(`/api/devices/${d.id}/favourites/backups/${chosen}/preview`)]); }
    catch (e) { state.favouritesSelection.delete(d.id); if (isCurrent()) box.append(el('p', e.message + ' Refresh and select an available saved version.', 'error')); return; }
    if (!isCurrent()) return;
    const recovery = panel('Selected version — preview before restoring', el('p', `${backup.label || 'Saved version'} · ${date(backup.created_at)} · ${backup.channel_count} channels / ${backup.group_count} groups`, 'subtle'));
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
      const p = panel(name === 'Settings' ? 'Read-only active-profile settings' : 'Basic app diagnostics — no logs or screenshots'); body.append(p);
      const request = button('Request fresh snapshot', () => queue(d, kind).catch(e => toast(e.message)), 'primary'); request.disabled = d.policy !== 'active'; p.append(request, el('p', 'The app must be open to respond. This snapshot reports one active profile at execution time, not every profile. Refresh this tab after the next app check-in.', 'hint'));
      try {
        const commands = await api(`/api/devices/${id}/commands`); if (epoch !== state.epoch || !tabs.querySelector(`[data-tab="${name}"]`).classList.contains('active')) return;
        const latest = commands.items.filter(c => (kind === 'get_settings' ? ['get_settings', 'set_settings'].includes(c.kind) : c.kind === kind) && c.status === 'succeeded' && c.result?.snapshot).sort((a, b) => String(b.finished_at).localeCompare(String(a.finished_at)))[0];
        if (latest) p.append(el('p', 'Snapshot received: ' + date(latest.finished_at), 'subtle'), detailRows(Object.entries(latest.result.snapshot).map(([key, value]) => [key.replaceAll('_', ' '), String(value)])));
        else p.append(el('p', 'No successful snapshot in the latest command-history page. Nothing has been fetched until you request it.', 'subtle'));
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
      } catch (e) { toast(e.message); }
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
  if (!state.user) return; const epoch = ++state.epoch; const hash = location.hash.slice(1) || 'overview'; const section = hash.startsWith('device/') ? 'devices' : hash;
  document.querySelectorAll('#navigation a').forEach(a => a.classList.toggle('active', a.dataset.page === section));
  try {
    if (hash === 'overview') await overview(epoch);
    else if (hash === 'devices' || hash === 'customers') await records(hash, epoch);
    else if (hash === 'resellers' && state.user.role === 'owner') await resellers(epoch);
    else if (hash === 'audit') await audit(epoch);
    else if (hash === 'presets') await presetsPage(epoch);
    else if (hash === 'enrollments' && state.user.role === 'owner') await enrollments(epoch);
    else if (/^device\/[a-f0-9]{32}$/.test(hash)) await devicePage(hash.split('/')[1], epoch);
    else location.hash = 'overview';
  } catch (e) { if (epoch === state.epoch) { $('main').replaceChildren(panel('Unable to load this view', el('p', e.message, 'error'), button('Retry', route))); } }
}
$('modal-close').addEventListener('click', () => $('modal').close());
$('modal').addEventListener('close', () => { $('modal-form').replaceChildren(); $('modal-form').onsubmit = null; });
$('logout-button').addEventListener('click', async () => { if ([...state.layoutEditors.values()].some(o => o.draft?.dirty) && !confirm('Discard unsaved browser layout drafts and sign out? Save them as templates first if needed.')) return; try { await api('/api/logout', {}); signedOut(); } catch (e) { toast(e.message); } });
window.addEventListener('beforeunload', e => { if ([...state.layoutEditors.values()].some(o => o.draft?.dirty)) { e.preventDefault(); e.returnValue = ''; } });
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
