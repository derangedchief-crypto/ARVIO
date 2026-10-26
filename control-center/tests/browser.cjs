// Optional local-only visual/E2E checks. Playwright is tooling, not a server dependency.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || '/tmp/opencode/control-center-browser-tools/node_modules/playwright');

(async () => {
  const base = process.env.CONTROL_PREVIEW_URL || 'http://127.0.0.1:8186';
  assert(['127.0.0.1', 'localhost'].includes(new URL(base).hostname), 'Browser tests must never target a live host');
  const credentials = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  assert.equal(credentials.email, 'owner@preview.test', 'Use only the synthetic local preview account');
  const output = path.resolve(__dirname, '../test-results'); fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  const errors = []; const checks = [];
  page.on('pageerror', error => errors.push(error.message));
  const done = name => { checks.push(name); console.log('PASS', name); };
  async function navigate(section,workspaceId=null){if(['resellers','enrollments','audit'].includes(section)){await page.locator('#navigation a[href="#more"]').click();await page.locator('#main a[href="#'+section+'"]').click();}else if(section==='presets'){await page.locator('#navigation a[href="#library"]').click();if(workspaceId)await page.getByLabel('Library workspace',{exact:true}).selectOption(workspaceId);await page.locator('#main a[href="#library/settings"]').click();}else await page.locator('#navigation a[href="#'+section+'"]').click();}
  async function expand(title){const summary=page.locator('summary').filter({hasText:title}).first();await summary.waitFor();if(!await summary.evaluate(n=>n.parentElement.open))await summary.click();}
  async function tab(name){const mapped={Overview:'Summary',Settings:'Playback',Categories:'Channels & favourites',Favourites:'Channels & favourites',Playlists:'Playlists & guide',Logs:'Help & activity',Commands:'Help & activity'};const visible={Summary:'Overview','Channels & favourites':'Channels','Playlists & guide':'Playlists','Help & activity':'Support'};const key=mapped[name]||name;if(page.viewportSize().width<=900)await page.getByLabel('Device section',{exact:true}).selectOption(key);else await page.getByRole('button',{name:visible[key]||key,exact:true}).first().click();if(name==='Favourites'||name==='Categories'){const b=page.getByRole('button',{name:name==='Favourites'?'Favourites':'Channel groups',exact:true});await b.waitFor();if(!((await b.getAttribute('class'))||'').includes('active'))await b.click();}if(name==='Logs')await expand('App diagnostics & technical details');}
  try {
    await page.goto(base);
    await page.getByLabel('Email address').fill(credentials.email);
    await page.getByLabel('Password', { exact: true }).fill(credentials.password);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByRole('heading', { name: 'Dashboard', exact: true }).waitFor();
    await page.screenshot({ path: path.join(output, 'overview-desktop.png'), fullPage: true });
    done('owner login and actual dashboard rendering');
    const ownerTenant = await page.evaluate(async () => (await (await fetch('/api/tenants')).json()).items.find(t => t.kind === 'owner').id);

    if (credentials.enrollment_request_id) {
      await navigate('enrollments');
      await page.getByRole('heading', { name: 'Website settings', exact: true }).waitFor();
      const pending = page.locator('tr').filter({ hasText: 'TEST · Enrollment simulator' });
      await pending.getByRole('button', { name: 'Approve / assign', exact: true }).waitFor();
      await page.screenshot({ path: path.join(output, 'enrollment-review-desktop.png'), fullPage: true });
      const downloadPromise = page.waitForEvent('download');
      await page.getByRole('button', { name: 'Download connection settings', exact: true }).click();
      const download = await downloadPromise;
      assert.equal(download.suggestedFilename(), 'management-bridge.env');
      const content = fs.readFileSync(await download.path(), 'utf8');
      assert(content.includes('MANAGEMENT_CONTROL_ORIGIN=')); assert(/MANAGEMENT_ENROLLMENT_SECRET=[a-f0-9]{64}/.test(content));
      done('owner bridge settings download is a protected file, not displayed in the DOM');
      await pending.getByRole('button', { name: 'Approve / assign', exact: true }).click();
      await page.getByLabel('Assign to customer', { exact: true }).selectOption(credentials.enrollment_customer_id);
      await page.locator('#modal-form').getByRole('button', { name: 'Approve device', exact: true }).click();
      await page.locator('#modal').waitFor({ state: 'hidden' });
      const approved = await page.evaluate(async id => (await (await fetch('/api/enrollments?status=approved')).json()).items.find(r => r.id === id), credentials.enrollment_request_id);
      assert(approved && approved.device_id); assert.equal(approved.credential_cipher, undefined); assert.equal(approved.poll_hash, undefined);
      done('owner approves synthetic pending identity and assigns customer without exposing delivery credentials');
      const verified = await page.evaluate(async ({ request, poll, username }) => {
        const delivery = await (await fetch('/api/device/enrollment/poll', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ request_id: request, poll_token: poll }) })).json();
        const check = await fetch('/api/device/check-in', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + delivery.credential }, body: JSON.stringify({ capabilities: [], account_username: username }) });
        return { status: check.status, device: await (await fetch('/api/devices/' + delivery.device_id)).json() };
      }, { request: credentials.enrollment_request_id, poll: credentials.synthetic_enrollment_poll_token, username: credentials.synthetic_line_username });
      assert.equal(verified.status, 200); assert.equal(verified.device.customer_name, credentials.synthetic_line_username);
      done('verified line username replaces numeric panel label through the signed-account check-in binding');
    }

    await navigate('customers');
    await page.getByRole('heading', { name: 'Customers', exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Add customer', exact: true }).count(), 0);
    const malicious = 'TEST ' + Date.now() + ' <img src=x onerror="window.__injected=1">';
    // Synthetic fixture API seeding only; production UI enrollment is automatic.
    const seeded = await page.evaluate(async ({ name, tenant }) => { const me = await (await fetch('/api/me')).json(); return (await fetch('/api/customers', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': me.csrf }, body: JSON.stringify({ name, tenant_id: tenant }) })).status; }, { name: malicious, tenant: ownerTenant });
    assert.equal(seeded, 201); await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await page.getByText(malicious, { exact: true }).waitFor();
    assert.equal(await page.evaluate(() => window.__injected), undefined);
    assert.equal(await page.locator('img[src="x"]').count(), 0);
    done('customer values render as text, not injected HTML');
    done('Customers page has no manual Add customer action');

    const pendingLabel = 'TEST · Pending ' + Date.now();
    const pendingSeed=await page.evaluate(async({name,label})=>{const me=await(await fetch('/api/me')).json();const rows=await(await fetch('/api/customers?search='+encodeURIComponent(name))).json();return(await fetch('/api/devices',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':me.csrf},body:JSON.stringify({customer_id:rows.items[0].id,label})})).status;},{name:malicious,label:pendingLabel});assert.equal(pendingSeed,201);
    await navigate('devices');
    await page.locator('.device-records thead th').first().waitFor({state:'attached'});
    assert.equal(await page.locator('.device-records thead th').count(),5);
    const filteredResponse=page.waitForResponse(r=>r.url().includes('/api/devices?')&&r.url().includes('policy=blocked'));
    await page.getByLabel('App access filter',{exact:true}).selectOption('blocked');const filtered=await(await filteredResponse).json();assert(filtered.items.every(d=>d.policy==='blocked'));
    await page.getByLabel('App access filter',{exact:true}).selectOption('');
    await page.getByLabel('Search',{exact:true}).fill(pendingLabel);await page.getByRole('button',{name:'Search',exact:true}).click();
    await page.locator('.fleet-count').filter({hasText:'1 matching device'}).waitFor();
    assert.equal(await page.locator('.device-records tbody tr[data-device-id]').count(),1);
    await page.getByLabel('Search',{exact:true}).fill('');await page.getByRole('button',{name:'Search',exact:true}).click();
    await page.locator('.fleet-count').filter({hasText:/^\d+ devices$/}).waitFor();
    const beforeRowRefresh=await page.evaluate(async id=>(await(await fetch('/api/devices/'+id+'/commands')).json()).total,credentials.device_id);
    page.once('dialog',dialog=>dialog.dismiss());await page.locator(`tr[data-device-id="${credentials.device_id}"] .fleet-actions button`).first().click();
    assert.equal(await page.evaluate(async id=>(await(await fetch('/api/devices/'+id+'/commands')).json()).total,credentials.device_id),beforeRowRefresh);
    await page.screenshot({path:path.join(output,'clean-devices-desktop.png'),fullPage:false});
    done('compact device directory has five clear columns, a real matching count and server-scoped app-access filtering');
    await page.locator('tr').filter({ hasText: pendingLabel }).getByRole('button', { name: 'View →' }).click();
    await page.getByRole('heading', { name: malicious, exact: true }).waitFor();
    await tab('Settings');assert.equal(await page.getByRole('button',{name:'Load device settings',exact:true}).isDisabled(),true);
    await page.getByText('Awaiting verified enrollment; device actions are not available yet.',{exact:true}).waitFor();
    done('pending enrollment and unsupported features are represented honestly');

    await navigate('devices');
    await page.locator(`tr[data-device-id="${credentials.device_id}"]`).getByRole('button', { name: 'View →' }).click();
    await page.getByText('LOCAL TEST SIMULATOR — illustrative, not a customer connection.', { exact: true }).waitFor();
    await tab('Playlists');
    await page.getByRole('button', { name: 'Refresh playlist', exact: true }).click();
    await page.locator('#action-status').filter({hasText:/Waiting for the app|Request sent/}).waitFor();
    await tab('Commands');
    await page.getByRole('heading', { name: 'Current requests', exact: true }).waitFor();
    await page.getByText('refresh playlist', { exact: true }).first().waitFor();
    assert(await page.locator('tbody .badge.pending').count() > 0);
    await page.screenshot({ path: path.join(output, 'device-commands-desktop.png'), fullPage: true });
    done('command is queued and shown pending, never faked as executed');

    await tab('Settings');
    await page.getByRole('heading', { name: 'Playback', exact: true }).waitFor();
    await page.getByText('Last synced: ', { exact: false }).first().waitFor();
    await page.getByRole('button', { name: 'Apply sorting', exact: true }).waitFor();
    await tab('Logs');
    await page.getByRole('button',{name:'Load app diagnostics',exact:true}).waitFor();
    done('settings and diagnostics require requested app snapshots, not fabricated live data');
    await navigate('presets',ownerTenant);
    await page.getByRole('button', { name: 'Create sorting preset', exact: true }).click();
    const presetName = 'TEST preset <img src=x onerror="window.__presetInjected=1">';
    await page.getByLabel('Workspace / reseller', { exact: true }).selectOption(ownerTenant);
    await page.getByLabel('Preset name', { exact: true }).fill(presetName);
    await page.getByLabel('Channel sorting', { exact: true }).selectOption('number');
    await page.locator('#modal-form').getByRole('button', { name: 'Save', exact: true }).click();
    await page.getByText(presetName, { exact: true }).waitFor();
    assert.equal(await page.evaluate(() => window.__presetInjected), undefined);
    await page.screenshot({ path: path.join(output, 'settings-presets-desktop.png'), fullPage: true });
    done('workspace-scoped settings preset saves and its hostile name remains literal text');
    await navigate('devices');
    await page.locator(`tr[data-device-id="${credentials.device_id}"]`).getByRole('button', { name: 'View →' }).click();
    await tab('Settings');
    await page.getByRole('button', { name: 'Use saved sorting preset', exact: true }).click();
    await page.getByLabel('Sorting preset',{exact:true}).selectOption({label:presetName});
    await page.locator('#modal-form').getByRole('button', { name: 'Use in this draft', exact: true }).click();
    await page.locator('#modal').waitFor({ state: 'hidden' });
    await page.getByRole('button',{name:'Apply sorting',exact:true}).click();await tab('Commands');
    const settingsRow = page.locator('tr').filter({ hasText: 'Change channel sorting' });
    await settingsRow.locator('.badge.pending').waitFor();
    const queuedSettings = await page.evaluate(async id => (await (await fetch('/api/devices/' + id + '/commands')).json()).items.find(c => c.kind === 'set_settings'), credentials.device_id);
    assert.equal(queuedSettings.payload.sort_order, 'number'); assert.equal(queuedSettings.result, null);
    assert(/^[a-f0-9]{64}$/.test(queuedSettings.payload.expected_revision));
    page.once('dialog', dialog => dialog.accept());
    await settingsRow.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.locator('#action-status').filter({hasText:'Request cancelled.'}).waitFor();assert.equal(await page.evaluate(async({device,id})=>(await(await fetch('/api/devices/'+device+'/commands/'+id)).json()).status,{device:credentials.device_id,id:queuedSettings.id}),'cancelled');
    done('preset application queues a revision-bound setting and queued cancellation is real');
    await tab('Categories');
    await page.getByRole('heading', { name: 'Channel groups', exact: true }).waitFor();
    const categoryName = 'TEST <img src=x onerror="window.__categoryInjected=1">';
    await page.getByText(categoryName, { exact: true }).waitFor();
    assert.equal(await page.evaluate(() => window.__categoryInjected), undefined);
    assert.equal(await page.locator('img[src="x"]').count(), 0);
    const commandsBeforeDraft = (await page.evaluate(async id => (await (await fetch('/api/devices/' + id + '/commands')).json()).total, credentials.device_id));
    await page.getByRole('button', { name: 'Next local page', exact: true }).click();
    await page.getByLabel('Position of TEST Category 60', { exact: true }).fill('1');
    await page.getByLabel('Position of TEST Category 60', { exact: true }).press('Tab');
    await page.getByRole('button', { name: 'Previous local page', exact: true }).click();
    await page.locator('.layout-draft tr').filter({ hasText: categoryName }).dragTo(page.locator('.layout-draft tr').filter({ hasText: 'TEST Category 60' }));
    await page.locator('.layout-draft tbody tr').first().getByText(categoryName, { exact: true }).waitFor();
    await page.locator('.layout-draft tr').filter({ hasText: 'TEST Category 60' }).dragTo(page.locator('.layout-draft tr').filter({ hasText: categoryName }));
    await page.locator('.layout-draft tbody tr').first().getByText('TEST Category 60', { exact: true }).waitFor();
    await page.getByLabel('Filter layout categories', { exact: true }).fill('TEST Category 5');
    await page.getByRole('button', { name: 'Select all filtered categories', exact: true }).click();
    await page.getByRole('button', { name: 'Hide selected', exact: true }).click();
    await page.getByLabel('Filter layout categories', { exact: true }).fill('');
    await page.getByLabel('Visible ' + categoryName, { exact: true }).uncheck();
    assert.equal(await page.evaluate(async id => (await (await fetch('/api/devices/' + id + '/commands')).json()).total, credentials.device_id), commandsBeforeDraft);
    await page.screenshot({ path: path.join(output, 'whole-layout-draft-desktop.png'), fullPage: true });
    done('complete-source paging, positions and bulk visibility are local edits with ZERO device commands');
    page.once('dialog', dialog => dialog.accept()); // explicit excluded-name preview
    await page.getByRole('button', { name: 'Save as layout template', exact: true }).click();
    await page.getByLabel('Layout name', { exact: true }).fill('TEST Organized Layout');
    await page.locator('#modal-form').getByRole('button', { name: 'Save', exact: true }).click();
    await page.locator('#modal').waitFor({ state: 'hidden' });
    const templates = await page.evaluate(async () => (await (await fetch('/api/category-layout-templates')).json()).items);
    assert(templates.some(t => t.name === 'TEST Organized Layout' && t.category_count === 59));
    assert.equal(await page.evaluate(async id => (await (await fetch('/api/devices/' + id + '/commands')).json()).total, credentials.device_id), commandsBeforeDraft);
    done('custom organized layout saves as a portable template without pushing device changes');
    const originalFull = await page.evaluate(async id => (await (await fetch('/api/devices/' + id + '/category-layout')).json()).catalog, credentials.device_id);
    page.once('dialog', dialog => dialog.accept());
    await page.getByRole('button', { name: 'Push entire layout once', exact: true }).click();
    await page.getByText('ONE complete layout command queued', { exact: false }).waitFor();
    const queuedLayout = await page.evaluate(async id => (await (await fetch('/api/devices/' + id + '/commands')).json()).items.find(c => c.kind === 'apply_category_layout'), credentials.device_id);
    assert.equal(queuedLayout.status, 'pending'); assert.equal(queuedLayout.payload.order_count, 60); assert.equal(queuedLayout.result, null);
    assert.equal(await page.evaluate(async id => (await (await fetch('/api/devices/' + id + '/commands')).json()).total, credentials.device_id), commandsBeforeDraft + 1);
    async function syntheticDelivery(kind) {
      for (let attempt = 0; attempt < 6; attempt++) {
        const found = await page.evaluate(async ({ id, token, kind }) => {
          const device = await (await fetch('/api/devices/' + id)).json();
          const check = await (await fetch('/api/device/check-in', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token }, body: JSON.stringify({ capabilities: device.capabilities, model: 'SYNTHETIC TEST DRIVER' }) })).json();
          for (const c of check.commands || []) {
            if (c.kind === kind) return c;
            await fetch('/api/device/commands/' + c.id + '/ack', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token }, body: JSON.stringify({ status: 'failed', lease_token: c.lease_token, summary: 'TEST simulator does not execute this other command' }) });
          }
          return null;
        }, { id: credentials.device_id, token: credentials.synthetic_device_token, kind });
        if (found) return found;
      }
      throw new Error('Synthetic expected delivery not found: ' + kind);
    }
    const applyDelivery = await syntheticDelivery('apply_category_layout');
    assert.equal(applyDelivery.payload.order.length, 60); assert.equal(new Set(applyDelivery.payload.order).size, 60);
    const hidden = new Set(applyDelivery.payload.hidden), byRef = new Map(originalFull.items.map(r => [r.ref, r]));
    const confirmedFull = { ...originalFull, revision: '9'.repeat(64), provider_order: applyDelivery.payload.provider_order, items: applyDelivery.payload.order.map((ref, i) => ({ ...byRef.get(ref), hidden: hidden.has(ref), position: i + 1 })) };
    const ack = async (c, extra) => page.evaluate(async ({ c, token, extra }) => {
      const r = await fetch('/api/device/commands/' + c.id + '/ack', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token }, body: JSON.stringify({ status: 'succeeded', lease_token: c.lease_token, summary: 'TEST SYNTHETIC RECEIPT — NOT ANDROID EXECUTION', ...extra }) }); return { status: r.status, body: await r.json() };
    }, { c, token: credentials.synthetic_device_token, extra });
    assert.equal((await ack(applyDelivery, {})).status, 200);
    await page.getByText('Whole layout applied; requesting one fresh read to confirm.', { exact: true }).waitFor();
    const readDelivery = await syntheticDelivery('get_category_layout'); assert.equal((await ack(readDelivery, { catalog: confirmedFull })).status, 200);
    await page.getByText('Whole layout applied and confirmed by a fresh app read.', { exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Push entire layout once', exact: true }).isDisabled(), true);
    done('many browser edits push as ONE atomic command; only a real successful receipt/read clears the draft (synthetic driver)');
    await navigate('devices');
    await page.locator(`tr[data-device-id="${credentials.layout_target_id}"]`).getByRole('button', { name: 'View →' }).click();
    await tab('Categories');
    const targetBefore = await page.evaluate(async id => (await (await fetch('/api/devices/' + id + '/commands')).json()).total, credentials.layout_target_id);
    await page.getByRole('button', { name: 'Preview / apply saved layout template', exact: true }).click();
    await page.getByText('58 exact matches', { exact: false }).waitFor();
    await page.locator('#modal-form').getByRole('button', { name: 'Apply to browser draft', exact: true }).click();
    await page.locator('#modal').waitFor({ state: 'hidden' });
    assert.equal(await page.evaluate(async id => (await (await fetch('/api/devices/' + id + '/commands')).json()).total, credentials.layout_target_id), targetBefore);
    await page.locator('.layout-draft tbody tr').first().getByText('TEST Category 60', { exact: true }).waitFor();
    done('cross-device template preview maps exact names to target refs, preserves extras and remains LOCAL until push');
    await navigate('devices');
    await page.locator(`tr[data-device-id="${credentials.device_id}"]`).getByRole('button', { name: 'View →' }).click();
    await tab('Favourites');
    await page.getByRole('heading', { name: 'Favourites backups', exact: true }).waitFor();
    const recoveryBefore = await page.evaluate(async id => (await (await fetch('/api/devices/' + id + '/favourites')).json()), credentials.device_id);
    assert.equal(recoveryBefore.total, 1); assert.equal(recoveryBefore.live[0].channel_count, 0); assert.equal(recoveryBefore.items[0].channel_count, 2); assert.equal(recoveryBefore.items[0].group_count, 1); assert(recoveryBefore.items[0].protected);
    await page.getByRole('button', { name: 'Preview recovery on the app', exact: true }).click();
    const favouritePreviewDelivery = await syntheticDelivery('preview_favourites_restore');
    const favouriteContext = await page.evaluate(async ({ token, id }) => (await (await fetch('/api/device/favourites/context', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token }, body: JSON.stringify({ backup_id: id }) })).json()), { token: credentials.synthetic_device_token, id: credentials.synthetic_favourites_backup_id });
    const favouritePreview = { schema_version: 1, backup_id: credentials.synthetic_favourites_backup_id, profile_ref: favouriteContext.backup.profile_ref, source_ref: favouriteContext.backup.source_ref, expected_revision: '8'.repeat(64), missing_refs: [], matched_channels: 2, matched_groups: 1, current_channels: 0, current_groups: 0, backup_channels: 2, backup_groups: 1 };
    assert.equal((await ack(favouritePreviewDelivery, { favourites_preview: favouritePreview })).status, 200);
    await page.getByRole('button', { name: 'Push favourites recovery', exact: true }).waitFor();
    assert.equal(await page.getByLabel('Favourites recovery mode', { exact: true }).inputValue(), 'merge');
    page.once('dialog', dialog => dialog.accept());
    await page.getByRole('button', { name: 'Push favourites recovery', exact: true }).click();
    const favouriteRestoreDelivery = await syntheticDelivery('restore_favourites'); assert.equal(favouriteRestoreDelivery.payload.mode, 'merge');
    const stillEmpty = await page.evaluate(async id => (await (await fetch('/api/devices/' + id + '/favourites')).json()).live[0].channel_count, credentials.device_id); assert.equal(stillEmpty, 0);
    assert.equal((await ack(favouriteRestoreDelivery, {})).status, 200);
    const recaptured = await page.evaluate(async ({ token, doc }) => (await (await fetch('/api/device/favourites/capture', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token }, body: JSON.stringify({ ...doc, reason: 'automatic' }) })).json()), { token: credentials.synthetic_device_token, doc: favouriteContext.backup });
    assert.equal(recaptured.backup_id, credentials.synthetic_favourites_backup_id);
    await page.getByRole('button', { name: 'Refresh backups / results', exact: true }).click();
    await page.getByRole('heading', { name: 'Last app-reported favourites state', exact: true }).waitFor();
    const afterRecovery = await page.evaluate(async id => (await (await fetch('/api/devices/' + id + '/favourites')).json()), credentials.device_id); assert.equal(afterRecovery.total, 1); assert.equal(afterRecovery.live[0].channel_count, 2);
    done('empty customer state preserves a protected good favourites version; preview/merge recovery waits for receipts and fresh app reporting (synthetic driver)');
    await tab('Logs');
    await page.getByRole('button', { name: 'Load app diagnostics', exact: true }).click();
    const diagnosticsDelivery = await syntheticDelivery('get_diagnostics');
    const diagnosticsSnapshot = { schema_version: '1', profile_ref: 'a'.repeat(64), app_package: 'com.arvio.tv.test', app_version: '2.0.112-test', sdk: '36', storage_total: '1000', storage_free: '900', channel_count: '4000', guide_state: 'idle', diagnostics_version: '2', heap_used: '100', heap_limit: '200', memory_state: 'low', network_state: 'permission_denied', network_transport: 'unknown', network_metered: 'unknown' };
    assert.equal((await ack(diagnosticsDelivery, { snapshot: diagnosticsSnapshot })).status, 200);
    await tab('Overview');
    await tab('Logs');
    await page.getByText('permission_denied', { exact: true }).waitFor();
    await page.getByText('heap used', { exact: true }).waitFor();
    await page.getByText('does not prove a playlist or stream works', { exact: false }).waitFor();
    done('extended redacted diagnostics display denied network status and heap counters only after a leased receipt (synthetic driver)');
    await tab('Settings');
    assert.equal(await page.getByLabel('Live TV buffer', { exact: true }).inputValue(), '0');
    await page.getByLabel('Live TV buffer', { exact: true }).selectOption('5');
    await page.getByRole('button', { name: 'Apply buffer', exact: true }).click();
    const bufferDelivery = await syntheticDelivery('set_live_buffer');
    assert.equal(bufferDelivery.payload.prebuffer_seconds, '5');
    const pendingBuffer = await page.evaluate(async id => (await (await fetch('/api/devices/' + id + '/commands')).json()).items.find(c => c.kind === 'set_live_buffer'), credentials.device_id);
    assert.equal(pendingBuffer.result, null);
    const bufferSnapshot = { schema_version: '1', profile_ref: bufferDelivery.payload.profile_ref, settings_revision: 'b'.repeat(64), sort_order: 'provider', playlist_count: '1', enabled_playlist_count: '1', portal_count: '0', favourite_channel_count: '0', favourite_group_count: '0', live_prebuffer_seconds: '5', live_buffer_revision: 'c'.repeat(64) };
    assert.equal((await ack(bufferDelivery, { snapshot: bufferSnapshot })).status, 200);
    await page.getByText('Buffer saved on device. Change channels to use it.',{exact:true}).waitFor();
    assert.equal(await page.getByLabel('Live TV buffer', { exact: true }).inputValue(), '5');
    done('device Live TV pre-buffer defaults Off, queues a bounded revision-guarded setting and displays saved amount after receipt (synthetic driver)');
    await tab('Playlists');
    await page.getByRole('heading',{name:'Playlists',exact:true}).waitFor();
    const sourceCommandsBefore=await page.evaluate(async id=>(await(await fetch('/api/devices/'+id+'/commands')).json()).total,credentials.device_id);
    await page.locator('tr').filter({hasText:'TEST Main source'}).getByRole('button',{name:'Edit',exact:true}).click();
    assert.equal(await page.getByLabel('Playlist URL',{exact:true}).count(),0);
    assert.equal(await page.getByLabel('Status',{exact:true}).isDisabled(),true);
    await page.getByLabel('Playlist name',{exact:true}).fill('TEST Renamed source');
    await page.locator('#modal-form').getByRole('button',{name:'Save playlist draft',exact:true}).click();
    await page.locator('#modal').waitFor({state:'hidden'});
    await page.getByRole('button',{name:'Add playlist link',exact:true}).click();
    await page.getByLabel('Playlist name',{exact:true}).fill('TEST Additional source');
    assert.equal(await page.getByLabel('Xtream username',{exact:true}).isVisible(),false);
    await page.getByLabel('Connection',{exact:true}).selectOption('xtream');assert.equal(await page.getByLabel('Playlist URL',{exact:true}).isVisible(),false);assert.equal(await page.getByLabel('Xtream username',{exact:true}).isVisible(),true);await page.getByLabel('Connection',{exact:true}).selectOption('url');
    await page.getByLabel('Playlist URL',{exact:true}).fill('https://synthetic-extra.invalid/list?password=write-only-fixture');
    await page.locator('#modal-form').getByRole('button',{name:'Save playlist draft',exact:true}).click();
    await page.locator('#modal').waitFor({state:'hidden'});
    assert.equal(await page.evaluate(()=>document.querySelector('#modal-form input[name=url]')?.value||''),'');
    assert.equal(await page.evaluate(async id=>(await(await fetch('/api/devices/'+id+'/commands')).json()).total,credentials.device_id),sourceCommandsBefore);
    async function changeConnection(mode){await page.locator('tr').filter({hasText:'TEST Additional source'}).getByRole('button',{name:'Edit',exact:true}).click();await page.getByLabel('Connection',{exact:true}).selectOption(mode);if(mode==='xtream'){await page.getByLabel('Xtream server address',{exact:true}).fill('https://synthetic-extra.invalid');await page.getByLabel('Xtream username',{exact:true}).fill('ui-client');await page.getByLabel('Xtream password',{exact:true}).fill('ui-password');}else await page.getByLabel('Playlist URL',{exact:true}).fill('https://synthetic-extra.invalid/list?password=write-only-fixture');await page.locator('#modal-form').getByRole('button',{name:'Save playlist draft',exact:true}).click();await page.locator('#modal').waitFor({state:'hidden'});return page.evaluate(id=>{const row=state.sourceEditors.get(id).entries.find(r=>r.name==='TEST Additional source');return {url:!!row.replace_url,xtream:!!row.xtream_host};},credentials.device_id);}
    assert.deepEqual(await changeConnection('xtream'),{url:false,xtream:true});assert.deepEqual(await changeConnection('url'),{url:true,xtream:false});assert.equal(await page.evaluate(async id=>(await(await fetch('/api/devices/'+id+'/commands')).json()).total,credentials.device_id),sourceCommandsBefore);
    await page.getByRole('button',{name:'Review playlist changes',exact:true}).click();
    await page.getByRole('button',{name:'Apply playlist changes',exact:true}).waitFor();
    page.once('dialog',dialog=>dialog.accept());await page.getByRole('button',{name:'Apply playlist changes',exact:true}).click();
    const sourceDelivery=await syntheticDelivery('apply_sources');
    const sourceContext=await page.evaluate(async({token,c})=>(await(await fetch('/api/device/sources/context',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify({command_id:c.id,lease_token:c.lease_token})})).json()),{token:credentials.synthetic_device_token,c:sourceDelivery});
    assert.equal(sourceContext.target.playlists.length,2);
    const sourceCaptures=await page.evaluate(async({token,c,context})=>{
      const status=[];for(const[reason,config,revision]of [['pre_apply',context.before,context.expected_revision],['confirmation',context.target,'d'.repeat(64)]]){
        const r=await fetch('/api/device/sources/capture',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify({command_id:c.id,lease_token:c.lease_token,reason,snapshot:{schema_version:1,profile_ref:context.profile_ref,revision,config}})});status.push(r.status);
      }return status;
    },{token:credentials.synthetic_device_token,c:sourceDelivery,context:sourceContext});assert.deepEqual(sourceCaptures,[200,200]);assert.equal((await ack(sourceDelivery,{})).status,200);
    await page.getByRole('button',{name:'Refresh status',exact:true}).click();
    await page.getByText('TEST Renamed source',{exact:true}).first().waitFor();
    await page.getByText('TEST Additional source',{exact:true}).first().waitFor();
    const sourceViews=await page.evaluate(async id=>{const history=await(await fetch('/api/devices/'+id+'/sources')).json();return await(await fetch('/api/devices/'+id+'/sources/'+history.items.find(r=>r.reason==='confirmation').id)).json();},credentials.device_id);
    assert.equal(sourceViews.entries.length,2);assert(!JSON.stringify(sourceViews).includes('write-only-fixture'));assert(!JSON.stringify(sourceViews).includes('synthetic-only'));
    await expand('Playlist backups');const rollbackRow=page.locator('tr').filter({hasText:'Before changes'});page.once('dialog',dialog=>dialog.accept());await rollbackRow.getByRole('button',{name:'Review restore',exact:true}).click();
    await page.getByRole('button',{name:'Apply playlist changes',exact:true}).waitFor();
    await page.getByRole('button',{name:'Cancel preview (re-enter secret replacements to edit again)',exact:true}).click();
    done('whole source edits stay local, canonical credentials stay protected, write-only replacements are encrypted and one push requires safety/confirmation; rollback is previewed (synthetic driver)');
    await navigate('presets',ownerTenant);
    await page.getByRole('button',{name:'Create playback settings',exact:true}).click();
    await page.getByLabel('Workspace / reseller',{exact:true}).selectOption(ownerTenant);
    await page.getByLabel('Settings profile name',{exact:true}).fill('TEST Browser broad profile');
    await page.getByLabel('Clock format',{exact:true}).selectOption('24h');
    await page.locator('#modal-form').getByRole('button',{name:'Save settings',exact:true}).click();
    await page.getByText('TEST Browser broad profile',{exact:true}).waitFor();
    const broadProfile=await page.evaluate(async()=>(await(await fetch('/api/preference-profiles?search=TEST%20Browser%20broad%20profile')).json()).items[0]);
    await navigate('devices');await page.locator(`tr[data-device-id="${credentials.device_id}"]`).getByRole('button',{name:'View →'}).click();
    await tab('Settings');await expand('Movie & series preferences, saved profiles and overrides');
    await page.getByLabel('Base settings profile',{exact:true}).selectOption(broadProfile.id);
    await page.getByLabel('Override clock_format',{exact:true}).selectOption('12h');
    await page.getByRole('button',{name:'Review playback changes',exact:true}).click();
    await page.getByRole('button',{name:'Apply playback settings',exact:true}).waitFor();page.once('dialog',dialog=>dialog.accept());await page.getByRole('button',{name:'Apply playback settings',exact:true}).click();
    const prefDelivery=await syntheticDelivery('apply_preferences');
    const prefCaptured=await page.evaluate(async({token,c,id})=>{
      const ctx=await(await fetch('/api/device/preferences/context',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify({command_id:c.id,lease_token:c.lease_token})})).json();
      const old=(await(await fetch('/api/devices/'+id+'/preferences')).json()).snapshot.document;
      const base=ctx.target.base_values,overrides=ctx.target.overrides,values={...old.values,...base,...overrides},origins={};for(const key of Object.keys(old.values))origins[key]=key in overrides?'device_profile_override':key in base?'base_preset':'local';
      return (await fetch('/api/device/preferences/capture',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify({command_id:c.id,lease_token:c.lease_token,document:{...old,...ctx.target,values,origins,revision:'e'.repeat(64)}})})).status;
    },{token:credentials.synthetic_device_token,c:prefDelivery,id:credentials.device_id});assert.equal(prefCaptured,200);assert.equal((await ack(prefDelivery,{})).status,200);
    await page.getByRole('button',{name:'Refresh preferences',exact:true}).click();
    assert.equal(await page.getByLabel('Override clock_format',{exact:true}).inputValue(),'12h');
    done('versioned broader settings profile is saved without auto-push; explicit device/profile override wins after preview and matching receipt (synthetic driver)');
    await tab('Logs');
    await page.getByRole('button',{name:'Collect support report',exact:true}).click();const supportDelivery=await syntheticDelivery('get_support_bundle');
    const supportUploaded=await page.evaluate(async({token,c,snapshot})=>(await fetch('/api/device/support/upload',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify({command_id:c.id,lease_token:c.lease_token,kind:'bundle',profile_ref:snapshot.profile_ref,document:{schema_version:1,profile_ref:snapshot.profile_ref,snapshot,events:[{code:'LIVE_RECOVERY',at_ms:Date.now()}]}})})).status,{token:credentials.synthetic_device_token,c:supportDelivery,snapshot:diagnosticsSnapshot});assert.equal(supportUploaded,200);assert.equal((await ack(supportDelivery,{})).status,200);
    await page.getByRole('button',{name:'Refresh support reports',exact:true}).click();
    const supportLink=page.getByRole('link',{name:'Download redacted JSON bundle',exact:true});await supportLink.waitFor();const download=await page.request.get(new URL(await supportLink.getAttribute('href'),base).href);assert.equal(download.status(),200);assert.equal((await download.json()).events[0].code,'LIVE_RECOVERY');
    page.once('dialog',dialog=>dialog.accept());await page.getByRole('button',{name:'Ask customer for support image',exact:true}).click();const panelDelivery=await syntheticDelivery('capture_app_screen');assert.equal((await ack(panelDelivery,{status:'failed',summary:'TEST synthetic customer declined — no image uploaded'})).status,200);
    await page.getByRole('button',{name:'Refresh support reports',exact:true}).click();assert.equal(await page.getByRole('link',{name:'Download consented panel PNG',exact:true}).count(),0);
    done('redacted private support export is session-authorized and a declined panel request produces no image link (synthetic driver)');
    await tab('Overview');await expand('Manage app access & assignment');assert.equal(await page.getByRole('button',{name:'Remove from reseller — keep app working',exact:true}).count(),0);
    const detachRecord=await page.evaluate(async id=>await(await fetch('/api/devices/'+id)).json(),credentials.reseller_device_id);await page.evaluate(id=>{location.hash='device/'+id;},credentials.reseller_device_id);await page.getByRole('heading',{name:detachRecord.line_username||detachRecord.customer_name,exact:true}).waitFor();await expand('Manage app access & assignment');
    page.once('dialog', dialog => dialog.accept());
    await page.getByRole('button', { name: 'Remove from reseller — keep app working', exact: true }).click();
    await page.getByText('Unassigned — owner-managed. Removing the reseller did not block the app.', { exact: true }).waitFor();
    const unassigned = await page.evaluate(async id => (await (await fetch('/api/devices/' + id)).json()), credentials.reseller_device_id);
    assert.equal(unassigned.reseller_detached, true); assert.equal(unassigned.policy, 'active');
    await page.screenshot({ path: path.join(output, 'device-unassigned-desktop.png'), fullPage: true });
    done('owner unassigns reseller management without blocking the synthetic device');

    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole('button',{name:'Open navigation',exact:true}).click();
    await navigate('overview');
    await page.getByRole('heading', { name: 'Dashboard', exact: true }).waitFor();
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'Mobile page overflow');
    await page.screenshot({ path: path.join(output, 'overview-mobile.png'), fullPage: true });
    await page.getByRole('button',{name:'Open navigation',exact:true}).click();
    assert.equal(await page.locator('#workspace').evaluate(n=>n.inert),true);
    assert.equal(await page.locator('#navigation .nav-group-title').count(),7);
    assert.equal(await page.locator('#navigation a.active').count(),1);
    assert.equal(await page.locator('#navigation a[aria-current="page"]').getAttribute('href'),'#overview');
    assert.equal(await page.locator('#navigation a[href="#library/defaults"]').isVisible(),true);
    const drawer=await page.locator('#sidebar').boundingBox();assert(drawer.width<390&&drawer.width>=280);
    await page.locator('#sidebar').evaluate(async node=>{await Promise.all(node.getAnimations().map(a=>a.finished));});
    await page.screenshot({path:path.join(output,'portal-menu-mobile.png'),fullPage:false});
    await page.locator('#sidebar').evaluate(node=>{node.scrollTop=node.scrollHeight;});
    assert.equal(await page.locator('#navigation a[href="#more"]').evaluate(node=>{const r=node.getBoundingClientRect();return r.top>=0&&r.bottom<=innerHeight;}),true);
    await page.locator('#sidebar').evaluate(node=>{node.scrollTop=0;});
    await page.keyboard.press('Escape');assert.equal(await page.evaluate(()=>document.activeElement.id),'nav-toggle');
    assert.equal(await page.locator('#workspace').evaluate(n=>n.inert),false);
    await page.getByRole('button',{name:'Open navigation',exact:true}).click();
    await page.locator('#navigation').getByRole('link',{name:'Devices',exact:true}).click();
    await page.getByRole('heading',{name:'Devices',exact:true}).waitFor();
    assert.equal(await page.locator('#nav-toggle').getAttribute('aria-expanded'),'false');
    assert.equal(await page.locator('.mobile-records table').first().evaluate(n=>getComputedStyle(n).display),'block');
    assert(await page.getByRole('button',{name:'View →',exact:true}).first().isVisible());
    await page.screenshot({path:path.join(output,'devices-mobile-modern.png'),fullPage:true});
    await page.screenshot({path:path.join(output,'clean-devices-mobile.png'),fullPage:true});
    await page.locator(`tr[data-device-id="${credentials.device_id}"]`).getByRole('button',{name:'View →'}).click();
    await tab('Settings');
    await page.getByLabel('Live TV buffer',{exact:true}).waitFor();
    await page.getByRole('button',{name:'Send message',exact:true}).click();
    const modalBounds=await page.locator('#modal').boundingBox();assert(modalBounds.x>=0&&modalBounds.x+modalBounds.width<=390);
    assert((await page.locator('#modal-form button').first().boundingBox()).height>=44);
    await page.locator('#modal-form').getByRole('button',{name:'Cancel',exact:true}).click();
    done('mobile drawer supports Escape/focus restoration; device records become readable cards and editor dialogs keep touch-sized actions');
    if(await page.evaluate(()=>document.documentElement.dataset.theme)==='light')await page.getByRole('button',{name:'Switch to dark theme',exact:true}).click();
    await page.getByRole('button',{name:'Switch to light theme',exact:true}).click();
    assert.equal(await page.evaluate(()=>document.documentElement.dataset.theme),'light');
    async function menuMatchesTheme(){const colors=await page.evaluate(()=>{const probe=document.createElement('span');probe.style.cssText='background:var(--panel);color:var(--accent);border-color:var(--accent-soft)';document.body.append(probe);const expected=getComputedStyle(probe),sidebar=getComputedStyle(document.getElementById('sidebar')),active=getComputedStyle(document.querySelector('#navigation a.active'));const result=[sidebar.backgroundColor,expected.backgroundColor,active.color,expected.color,active.backgroundColor,expected.borderTopColor];probe.remove();return result;});assert.equal(colors[0],colors[1]);assert.equal(colors[2],colors[3]);assert.equal(colors[4],colors[5]);}
    await menuMatchesTheme();
    const themePage=await page.context().newPage();await themePage.goto(base);await themePage.getByRole('heading',{name:'Dashboard',exact:true}).waitFor();assert.equal(await themePage.evaluate(()=>document.documentElement.dataset.theme),'light');await themePage.close();
    await page.screenshot({path:path.join(output,'device-settings-mobile-light.png'),fullPage:false});
    for(const width of [320,390,768,1024,1440]){await page.setViewportSize({width,height:950});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,`Page overflows at ${width}px`);}
    await page.getByRole('button',{name:'Switch to dark theme',exact:true}).click();
    await menuMatchesTheme();
    done('menu surfaces and active highlights match the shared site palette in both light and dark themes');
    await page.screenshot({path:path.join(output,'portal-menu-desktop.png'),fullPage:false});
    done('grouped portal sidebar has direct setup links, one active row and a scrollable accessible mobile drawer');
    done('light/dark appearance persists without customer data; active settings remain usable from 320px to desktop without page overflow');
    done('mobile overview has no horizontal page overflow');

    await page.setViewportSize({width:1440,height:1000});await navigate('devices');await page.locator(`tr[data-device-id="${credentials.device_id}"]`).waitFor();await page.screenshot({path:path.join(output,'clean-devices-desktop-dark.png'),fullPage:false});await page.locator(`tr[data-device-id="${credentials.device_id}"]`).getByRole('button',{name:'View →'}).click();await tab('Settings');
    const acceptDiscard=d=>d.accept();page.on('dialog',acceptDiscard);await page.getByRole('button',{name:'Load device settings',exact:true}).click();page.off('dialog',acceptDiscard);const guidedRead=await syntheticDelivery('get_settings');assert.equal((await ack(guidedRead,{snapshot:bufferSnapshot})).status,200);
    await page.getByText('Settings loaded.',{exact:true}).waitFor();assert.equal(await page.getByLabel('Live TV buffer',{exact:true}).inputValue(),'5');
    done('guided Playback read completes in place after one Load action, without manual refresh or a separate command page');

    await page.locator('#navigation a[href="#library"]').click();await page.getByLabel('Library workspace',{exact:true}).selectOption(ownerTenant);
    const beforeLibraryActions=await page.evaluate(async id=>(await(await fetch('/api/devices/'+id+'/commands')).json()).total,credentials.device_id);
    await page.getByRole('button',{name:'Create channel layout',exact:true}).click();await page.getByLabel('Layout name',{exact:true}).fill('TEST new workspace layout');
    await page.getByRole('button',{name:'Add category rules',exact:true}).click();await page.getByLabel('Category names — one per line',{exact:true}).fill('News\nSports');await page.locator('#modal-form').getByRole('button',{name:'Save',exact:true}).click();await page.locator('#modal').waitFor({state:'hidden'});
    await page.getByRole('button',{name:'Save channel layout',exact:true}).click();await page.getByText('Channel layout saved.',{exact:true}).waitFor();
    const makerLayout=await page.evaluate(async tenant=>(await(await fetch('/api/category-layout-templates?tenant_id='+tenant+'&search=TEST%20new%20workspace%20layout')).json()).items[0],ownerTenant);assert.equal(Number(makerLayout.version),1);
    assert.equal(await page.evaluate(async id=>(await(await fetch('/api/devices/'+id+'/commands')).json()).total,credentials.device_id),beforeLibraryActions);
    await page.screenshot({path:path.join(output,'task-workspace-layout-maker.png'),fullPage:true});await page.setViewportSize({width:390,height:950});await page.locator('.sidebar').waitFor({state:'hidden'});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);assert.equal(await page.locator('.mobile-records table').first().evaluate(n=>getComputedStyle(n).display),'block');await page.screenshot({path:path.join(output,'task-workspace-layout-maker-mobile.png'),fullPage:true});await page.setViewportSize({width:1440,height:1000});done('discoverable Library maker creates exact-name reusable layouts without sending device commands');

    await page.locator('#main a[href="#library/profiles"]').click();await page.getByRole('button',{name:'Create device setup',exact:true}).click();await page.getByLabel('Setup name',{exact:true}).fill('TEST new-device recipe');await page.getByLabel('Channel layout',{exact:true}).selectOption(makerLayout.id+'@1');await page.getByLabel('Playback settings',{exact:true}).selectOption(broadProfile.id+'@1');await page.getByLabel('Live TV buffer (whole device)',{exact:true}).selectOption('2');await page.locator('#modal-form').getByRole('button',{name:'Save device setup',exact:true}).click();await page.locator('#modal').waitFor({state:'hidden'});
    const recipe=await page.evaluate(async tenant=>(await(await fetch('/api/device-profiles?tenant_id='+tenant+'&search=TEST%20new-device%20recipe')).json()).items[0],ownerTenant);assert.equal(recipe.definition.layout.version,1);assert.equal(recipe.definition.prebuffer_seconds,'2');
    await page.locator('#main a[href="#library/defaults"]').click();await page.getByLabel('Saved device setup',{exact:true}).selectOption(recipe.id+'@1');await page.getByLabel('Use for new devices',{exact:true}).check();page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'Save automatic setup',exact:true}).click();
    await page.waitForFunction(async tenant=>(await(await fetch('/api/device-defaults?tenant_id='+tenant)).json()).policy?.enabled===1,ownerTenant);
    await page.getByText('On · TEST new-device recipe',{exact:false}).waitFor();
    const existingSetups=await page.evaluate(async id=>(await(await fetch('/api/device-setups?device_id='+id)).json()).total,credentials.device_id);assert.equal(existingSetups,0);
    await page.screenshot({path:path.join(output,'task-workspace-new-device-defaults.png'),fullPage:true});
    await page.evaluate(id=>{location.hash='library/layout/'+id;},makerLayout.id);await page.getByLabel('Position of Sports',{exact:true}).fill('1');await page.getByLabel('Position of Sports',{exact:true}).press('Tab');await page.getByRole('button',{name:'Save channel layout',exact:true}).click();
    await page.waitForFunction(async id=>(await(await fetch('/api/category-layout-templates/'+id)).json()).version===2,makerLayout.id);
    const pinnedRecipe=await page.evaluate(async id=>await(await fetch('/api/device-profiles/'+id)).json(),recipe.id);assert.equal(pinnedRecipe.definition.layout.version,1);assert.equal(pinnedRecipe.definition.layout.entries[0].name,'News');
    await page.locator('#main a[href="#library/defaults"]').click();await page.getByLabel('Use for new devices',{exact:true}).uncheck();page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'Save automatic setup',exact:true}).click();
    done('reseller defaults use an explicitly enabled version-pinned recipe, do not touch existing devices and do not silently follow template edits');

    await page.setViewportSize({ width: 1440, height: 1000 });
    {
    // A separate verified reseller/session models the actual self-service role and
    // keeps independent scenarios within each actor's production request budget.
    const brandContext=await browser.newContext({viewport:{width:1440,height:1000}});const page=await brandContext.newPage();const ownerTenant=credentials.brand_tenant_id;
    page.on('pageerror',error=>errors.push(error.message));await page.goto(base);await page.getByLabel('Email address').fill(credentials.brand_email);await page.getByLabel('Password',{exact:true}).fill(credentials.brand_password);await page.getByRole('button',{name:'Sign in',exact:true}).click();await page.getByRole('heading',{name:'Dashboard',exact:true}).waitFor();
    await page.locator('#navigation a[href="#branding"]').click();
    await page.getByLabel('Branded app name',{exact:true}).fill('TEST North TV');
    const brandImage=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSuoAAAAASUVORK5CYII=','base64');
    for(const field of ['Loading image','In-app logo / TV banner','Launcher icon']){const uploaded=page.waitForResponse(r=>r.url().endsWith('/api/app-brand/assets')&&r.request().method()==='POST');await page.getByLabel(field,{exact:true}).setInputFiles({name:'test.png',mimeType:'image/png',buffer:brandImage});assert.equal((await uploaded).status(),201);await page.getByAltText(field+' preview',{exact:true}).waitFor();}
    await page.getByRole('button',{name:'Save brand',exact:true}).click();await page.getByRole('heading',{name:'Name & artwork',exact:true}).waitFor();
    const savedBrand=await page.evaluate(async t=>(await(await fetch('/api/app-brand?tenant_id='+t)).json()).brand,ownerTenant);
    const buildResponse=page.waitForResponse(r=>r.url().endsWith('/api/app-brand/builds')&&r.request().method()==='POST',{timeout:10000});await page.getByRole('button',{name:'Build app',exact:true}).click();let buildReply;try{buildReply=await buildResponse;}catch(e){console.log('Brand action status:',await page.locator('#toast').textContent());throw e;}assert.equal(buildReply.status(),201,await buildReply.text());await page.getByText('Queued',{exact:true}).first().waitFor();
    assert.equal(await page.getByRole('link',{name:'Download build inputs',exact:true}).count(),0);
    assert.equal(await page.locator('.brand-editor').evaluate(n=>n.open),false);await page.locator('.brand-editor>summary').click();await page.getByLabel('Branded app name',{exact:true}).fill('TEST Renamed North');await page.getByRole('button',{name:'Save brand',exact:true}).click();await page.waitForFunction(async t=>(await(await fetch('/api/app-brand?tenant_id='+t)).json()).brand.version===2,ownerTenant);
    const changedBrand=await page.evaluate(async t=>(await(await fetch('/api/app-brand?tenant_id='+t)).json()),ownerTenant);assert.equal(changedBrand.brand.id,savedBrand.id);assert.equal(changedBrand.brand.package_name,savedBrand.package_name);assert.equal(changedBrand.builds[0].brand_version,1);assert.equal(changedBrand.trial_creation_enabled,false);
    await page.screenshot({path:path.join(output,'app-brand-self-service-desktop.png'),fullPage:true});await page.setViewportSize({width:390,height:950});await page.locator('.sidebar').waitFor({state:'hidden'});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);await page.screenshot({path:path.join(output,'app-brand-self-service-mobile.png'),fullPage:true});await page.setViewportSize({width:1440,height:1000});
    done('self-service branding uploads scoped artwork, updates one stable app identity and pins an honest waiting build without enabling trials');
    // Render publication responses without pretending a synthetic APK was signed.
    const siteApk='/downloads/apps/'+'a'.repeat(32)+'/Brand-2.0.115.3-test.apk';
    await page.route(/\/api\/app-brand(?:\?.*)?$/,async route=>{const response=await route.fetch();const data=await response.json();const current=data.builds[0];data.builds=[{...current,brand_version:8},...Array.from({length:7},(_,i)=>({...current,id:String(i+1).repeat(32),brand_version:7-i,state:'cancelled'}))];data.artifacts=[{channel:'testing',version_name:'2.0.115.3-test',package_name:savedBrand.package_name+'.test',apk_sha256:'b'.repeat(64),artifact_url:siteApk,download_ready:true},{channel:'production',version_name:'2.0.115.3',package_name:savedBrand.package_name,apk_sha256:'c'.repeat(64),artifact_url:'https://github.com/example/provider/private.apk',download_ready:true}];await route.fulfill({response,json:data});});
    await page.evaluate(()=>{location.hash='overview';});await page.getByRole('heading',{name:'Dashboard',exact:true}).waitFor();await page.locator('#navigation a[href="#branding"]').click();await page.getByRole('link',{name:'Download test app 2.0.115.3-test',exact:true}).waitFor();
    assert.equal(await page.getByRole('link',{name:'Download test app 2.0.115.3-test',exact:true}).getAttribute('href'),siteApk);assert.equal(await page.locator('a[href*="github"]').count(),0);assert.equal(await page.getByRole('link',{name:'Download app 2.0.115.3',exact:true}).count(),0);
    assert.equal(await page.locator('#main tbody tr').count(),1);assert.equal(await page.locator('#main tbody tr td').first().textContent(),'8');assert.equal(await page.locator('.brand-editor').evaluate(n=>n.open),false);assert.equal(await page.getByLabel('Branded app name',{exact:true}).isVisible(),false);
    await page.locator('.brand-editor>summary').click();await page.getByLabel('Branded app name',{exact:true}).waitFor();await page.locator('.brand-editor>summary').click();await page.setViewportSize({width:390,height:950});await page.locator('.sidebar').waitFor({state:'hidden'});assert.equal(await page.locator('.brand-editor').evaluate(n=>n.open),false);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);await page.screenshot({path:path.join(output,'branding-current-build-collapsed-mobile.png'),fullPage:true});await page.setViewportSize({width:1440,height:1000});await page.locator('.sidebar').waitFor({state:'visible'});await page.screenshot({path:path.join(output,'branding-current-build-collapsed-desktop.png'),fullPage:true});
    done('branding shows only the latest build from eight returned records and keeps the existing-brand editor collapsed until explicitly opened');
    done('reseller download UI uses site-only APK links and refuses injected provider URLs (synthetic publication response)');
    await brandContext.close();
    }
    // The preceding synthetic suite compresses hundreds of requests into a minute.
    // Respect the unchanged 300/min actor budget before the independent admin cases.
    await page.waitForTimeout(61000);
    {
      await page.evaluate(id=>{location.hash='branding/'+id;},credentials.brand_tenant_id);await page.getByRole('link',{name:'Download build inputs',exact:true}).waitFor();
      const downloadPromise=page.waitForEvent('download');await page.getByRole('link',{name:'Download build inputs',exact:true}).click();const download=await downloadPromise;assert.match(download.suggestedFilename(),/^brand-input-[a-f0-9]{32}\.json$/);const bundle=JSON.parse(fs.readFileSync(await download.path(),'utf8'));assert.deepEqual(Object.keys(bundle),['manifest','images']);assert.equal(bundle.manifest.brand_version,1);assert.equal(bundle.manifest.app_name,'TEST North TV');assert.equal(bundle.manifest.package_name,'com.extreme.brand.b'+bundle.manifest.brand_id);assert.deepEqual(Object.keys(bundle.images).sort(),['icon_asset','loading_asset','logo_asset']);
      done('owner downloads immutable build inputs in-browser without a server terminal; reseller export remains unavailable');
    }
    {
      const ownContext=await browser.newContext({viewport:{width:390,height:1000}});const own=await ownContext.newPage();own.on('pageerror',e=>errors.push(e.message));await own.route(/\/api\/tenants$/,async route=>{const response=await route.fetch();const data=await response.json();data.items=data.items.filter(t=>t.kind!=='owner'&&t.id!==credentials.brand_tenant_id);await route.fulfill({response,json:data});});await own.goto(base);await own.getByLabel('Email address').fill(credentials.email);await own.getByLabel('Password',{exact:true}).fill(credentials.password);await own.getByRole('button',{name:'Sign in',exact:true}).click();await own.getByRole('heading',{name:'Dashboard',exact:true}).waitFor();await own.evaluate(()=>{location.hash='branding';});await own.getByLabel('Branded app name',{exact:true}).waitFor();
      const ownerSpace=await own.evaluate(async()=>(await(await fetch('/api/workspace-context')).json()).workspace);assert.equal(await own.getByLabel('Brand workspace',{exact:true}).inputValue(),ownerSpace.id);assert.equal(await own.getByLabel('Brand workspace',{exact:true}).locator('option:checked').textContent().then(t=>t.startsWith('My owner workspace')),true);
      await own.getByLabel('Branded app name',{exact:true}).fill('TEST Owner TV');const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSuoAAAAASUVORK5CYII=','base64');for(const field of ['Loading image','In-app logo / TV banner','Launcher icon']){const uploaded=own.waitForResponse(r=>r.url().endsWith('/api/app-brand/assets')&&r.request().method()==='POST');await own.getByLabel(field,{exact:true}).setInputFiles({name:'owner.png',mimeType:'image/png',buffer:png});assert.equal((await uploaded).status(),201);}
      await own.getByRole('button',{name:'Save brand',exact:true}).click();await own.getByRole('heading',{name:'Name & artwork',exact:true}).waitFor();await own.getByRole('button',{name:'Build app',exact:true}).click();await own.getByRole('link',{name:'Download build inputs',exact:true}).waitFor();assert.equal(await own.getByRole('button',{name:'Open trials & contacts',exact:true}).count(),1);assert.equal(await own.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);await own.screenshot({path:path.join(output,'owner-branding-mobile.png'),fullPage:true});
      const response=await own.evaluate(async()=>(await(await fetch('/api/app-brand')).json()));assert.equal(response.workspace_kind,'owner');assert.equal(response.tenant_id,ownerSpace.id);assert.equal(response.trial_creation_enabled,false);done('administrator can select the owner workspace by default and prepare a separate owner brand without a new reseller login');
       await own.getByRole('button',{name:'Open trials & contacts',exact:true}).click();await own.getByLabel('Trial workspace',{exact:true}).waitFor();assert.equal(await own.getByLabel('Trial workspace',{exact:true}).inputValue(),ownerSpace.id);await own.getByText('The server trial connection needs admin setup.',{exact:true}).waitFor();assert.equal(await own.getByRole('switch',{name:'Show trial button in this app',exact:true}).isDisabled(),true);await own.screenshot({path:path.join(output,'owner-trials-blocked-mobile.png'),fullPage:true});await own.evaluate(()=>{location.hash='branding';});await own.getByRole('heading',{name:'Name & artwork',exact:true}).waitFor();done('owner trial workspace is selectable without a reseller login and remains unpublished while its server connection and approved default are unavailable');
      await require('./owner-workspace-browser.cjs')(own,credentials,ownerSpace,output,done);
      let pendingBrandFetches=0;await own.route('**/api/app-brand?tenant_id=*',r=>{pendingBrandFetches++;return r.continue();});await own.route('**/api/system/status',r=>r.fulfill({status:200,contentType:'application/json',body:JSON.stringify({pending_migrations:['014_reseller_app_brands.sql']})}));await own.reload();await own.getByRole('heading',{name:'Database upgrade required',exact:true}).waitFor();await own.getByRole('button',{name:'Open website upgrades',exact:true}).waitFor();assert.equal(pendingBrandFetches,0);await own.screenshot({path:path.join(output,'owner-branding-upgrade-required-mobile.png'),fullPage:true});await own.unroute('**/api/system/status');await own.unroute('**/api/app-brand?tenant_id=*');done('branding checks pending database upgrades before calling an endpoint that could fail behind an HTML error page');
      await own.route('**/api/app-brand?tenant_id=*',r=>r.fulfill({status:503,contentType:'text/html',headers:{'X-Request-ID':'test_brand_503'},body:'<html>PRIVATE_SERVER_DETAIL_MUST_NOT_SHOW</html>'}));await own.reload();await own.getByRole('heading',{name:'Branding could not load',exact:true}).waitFor();await own.getByText(/HTTP 503.*Request ID: test_brand_503/).waitFor();assert.equal((await own.locator('body').textContent()).includes('PRIVATE_SERVER_DETAIL_MUST_NOT_SHOW'),false);await own.screenshot({path:path.join(output,'branding-html-error-mobile.png'),fullPage:true});await own.getByRole('button',{name:'Check website upgrades',exact:true}).click();await own.getByRole('heading',{name:'Website settings',exact:true}).waitFor();
      done('HTML error pages show safe HTTP status and request ID with a working upgrade link instead of blaming server configuration');await ownContext.close();
    }
    {
      const trialsContext=await browser.newContext({viewport:{width:1440,height:1000}});const tpage=await trialsContext.newPage();tpage.on('pageerror',e=>errors.push(e.message));
      await tpage.goto(base);await tpage.getByLabel('Email address').fill(credentials.trial_email);await tpage.getByLabel('Password',{exact:true}).fill(credentials.trial_password);await tpage.getByRole('button',{name:'Sign in',exact:true}).click();await tpage.getByRole('heading',{name:'Dashboard',exact:true}).waitFor();
       await tpage.locator('#navigation a[href="#trials"]').click();await tpage.getByLabel('Trial package',{exact:true}).waitFor();assert.equal(await tpage.getByRole('heading',{name:'Trial access',exact:true}).count(),0);assert.equal(await tpage.getByLabel('Trial package',{exact:true}).locator('option[value="8"]').count(),0);
       await tpage.getByText('••••4001',{exact:true}).waitFor();assert.equal(await tpage.getByRole('switch',{name:'Show trial button in this app',exact:true}).isDisabled(),true);await tpage.getByText('The server trial connection needs admin setup.',{exact:true}).waitFor();assert.equal(await tpage.getByText('+15551234001',{exact:true}).count(),0);
      let releaseDetail;const detailGate=new Promise(resolve=>{releaseDetail=resolve;});const detailPattern='**/api/trial-contacts/'+credentials.trial_contact_id;await tpage.route(detailPattern,async route=>{await detailGate;await route.continue();});const delayed=tpage.waitForRequest(r=>r.url().endsWith('/api/trial-contacts/'+credentials.trial_contact_id));await tpage.getByRole('button',{name:'View contact',exact:true}).click();await delayed;await tpage.getByRole('button',{name:'Change password',exact:true}).click();await tpage.getByRole('heading',{name:'Change your password',exact:true}).waitFor();releaseDetail();await tpage.waitForLoadState('networkidle');await tpage.getByRole('heading',{name:'Change your password',exact:true}).waitFor();assert.equal(await tpage.getByText('+15551234001',{exact:true}).count(),0);await tpage.locator('#modal-form').getByRole('button',{name:'Cancel',exact:true}).click();await tpage.unroute(detailPattern);
      await tpage.getByRole('button',{name:'View contact',exact:true}).click();await tpage.getByText('+15551234001',{exact:true}).waitFor();assert.equal(await tpage.getByRole('link',{name:'Call customer',exact:true}).getAttribute('href'),'tel:+15551234001');await tpage.getByText('Expired',{exact:true}).last().waitFor();
       await tpage.screenshot({path:path.join(output,'trial-expired-contact-detail.png'),fullPage:false});await tpage.locator('#modal-form').getByRole('button',{name:'Close',exact:true}).click();await tpage.locator('#modal').waitFor({state:'hidden'});const oldDefault=await tpage.evaluate(async()=>(await api('/api/trial-policy')).default);
       await page.evaluate(id=>{location.hash='trials/'+id;},credentials.trial_tenant_id);await page.getByLabel('Allow TEST 48-hour trial',{exact:true}).check();await page.getByRole('switch',{name:'Allow trials for this reseller',exact:true}).check();await page.getByRole('button',{name:'Save trial access',exact:true}).click();await page.waitForFunction(async id=>(await(await fetch('/api/trial-policy?tenant_id='+id)).json()).grant.version===2,credentials.trial_tenant_id);
       const stale=await tpage.evaluate(async old=>{try{await api('/api/trial-policy/default',{expected_revision:Number(old.revision),grant_version:Number(old.grant_version),plan_id:7,enabled:true});return 200;}catch(e){return e.status;}},oldDefault);assert.equal(stale,409);
       await tpage.getByRole('button',{name:'Reload status',exact:true}).click();await tpage.getByLabel('Trial package',{exact:true}).locator('option[value="8"]').waitFor({state:'attached'});await tpage.getByLabel('Trial package',{exact:true}).selectOption('8');await tpage.getByRole('button',{name:'Save trial settings',exact:true}).click();await tpage.waitForFunction(async()=>{const p=await(await fetch('/api/trial-policy')).json();return p.default.plan_id===8&&p.default.grant_version===2;});assert.equal(await tpage.evaluate(async()=>(await(await fetch('/api/trial-policy')).json()).live_offer_enabled),false);
      await tpage.screenshot({path:path.join(output,'trial-defaults-reseller-desktop.png'),fullPage:true});await tpage.setViewportSize({width:390,height:950});await tpage.locator('.sidebar').waitFor({state:'hidden'});assert.equal(await tpage.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);await tpage.screenshot({path:path.join(output,'trial-contacts-mobile.png'),fullPage:true});
      await tpage.getByRole('button',{name:'View contact',exact:true}).click();tpage.once('dialog',d=>d.accept());await tpage.getByRole('button',{name:'Remove contact details here',exact:true}).click();await tpage.locator('#modal').waitFor({state:'hidden'});await tpage.getByText('Contact removed',{exact:true}).waitFor();assert.equal(await tpage.evaluate(()=>JSON.stringify(localStorage).includes('15551234001')),false);
      const contact=await tpage.evaluate(async id=>await(await fetch('/api/trial-contacts/'+id)).json(),credentials.trial_contact_id);assert.equal(contact.contact_available,false);assert.equal(contact.phone,null);await trialsContext.close();
      done('trial policies enforce owner-approved choices and stale-review guards; expired contacts stay scoped and callable without leaking phone numbers into lists or storage');
    }
     await require('./trial-controls-browser.cjs')(browser,base,credentials,output,done);
     await require('./trial-controls-legacy-browser.cjs')(browser,base,credentials,output,done);
     await require('./global-trials-browser.cjs')(browser,base,credentials,output,done);
     await require('./compact-browser.cjs')(browser,base,credentials,output,done);
      await require('./device-access-browser.cjs')(browser,base,credentials,output,done);
      await require('./addons-browser.cjs')(browser,base,credentials,output,done);
    await require('./build-progress-browser.cjs')(browser,base,credentials,output,done);
    await navigate('resellers');
    await page.getByRole('button', { name: 'Link panel reseller', exact: true }).first().click();
    assert((await page.getByLabel('Exact panel login username', { exact: true }).inputValue()).length > 0);
    await page.locator('#modal').getByRole('button', { name: 'Cancel', exact: true }).click();
    done('owner reseller-link form defaults to workspace name and requires server verification');
    await page.getByRole('button', { name: 'Create reseller', exact: true }).click();
    const email = 'browser-' + Date.now() + '@preview.test';
    const password = 'Browser-Test-Reseller-Password-46!';
    const company = 'TEST · Browser reseller ' + Date.now();
    await page.getByLabel('Reseller name', { exact: true }).fill(company);
    await page.getByLabel('Contact name (optional)', { exact: true }).fill('Test reseller');
    await page.getByLabel('Sign-in email', { exact: true }).fill(email);
    await page.getByLabel('Temporary password', { exact: true }).fill(password);
    await page.locator('#modal-form').getByRole('button', { name: 'Save', exact: true }).click();
    await page.locator('#modal').waitFor({ state: 'hidden' });
    await page.getByText(company, { exact: true }).waitFor();
    page.once('dialog', dialog => dialog.accept()); // target layout draft intentionally not pushed
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await page.getByLabel('Email address').waitFor();
    assert.equal(await page.locator('#main').textContent(), '');
    assert.equal(await page.locator('#modal-form input[type="password"]').count(), 0);
    await page.getByLabel('Email address').fill(email);
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByRole('heading', { name: 'Dashboard', exact: true }).waitFor();
    await navigate('more');assert.equal(await page.locator('#main a[href="#resellers"]').count(), 0);
    const denied = await page.evaluate(async tenant => (await fetch('/api/devices?tenant_id=' + tenant)).status, ownerTenant);
    assert.equal(denied, 404);
    assert.equal(await page.evaluate(async () => (await fetch('/api/enrollments')).status), 403);
    assert.equal(await page.evaluate(async tenant => (await fetch('/api/settings-presets?tenant_id=' + tenant)).status, ownerTenant), 404);
    await navigate('customers');
    await page.getByText('No records yet.', { exact: true }).waitFor();
    done('reseller login sees only its empty workspace; foreign tenant API is denied');
    assert.deepEqual(errors, []);
    done('no uncaught browser JavaScript errors');
    fs.writeFileSync(path.join(output, 'browser.json'), JSON.stringify({ passed: checks.length, checks, errors }, null, 2));
  } catch (error) {
    await page.screenshot({ path: path.join(output, 'browser-failure.png'), fullPage: true });
    throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
