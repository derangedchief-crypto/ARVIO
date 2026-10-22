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
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const errors = []; const checks = [];
  page.on('pageerror', error => errors.push(error.message));
  const done = name => { checks.push(name); console.log('PASS', name); };
  try {
    await page.goto(base);
    await page.getByLabel('Email address').fill(credentials.email);
    await page.getByLabel('Password', { exact: true }).fill(credentials.password);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByRole('heading', { name: 'Operations overview', exact: true }).waitFor();
    await page.screenshot({ path: path.join(output, 'overview-desktop.png'), fullPage: true });
    done('owner login and actual dashboard rendering');
    const ownerTenant = await page.evaluate(async () => (await (await fetch('/api/tenants')).json()).items.find(t => t.kind === 'owner').id);

    if (credentials.enrollment_request_id) {
      await page.locator('nav a[href="#enrollments"]').click();
      await page.getByRole('heading', { name: 'Device enrollments', exact: true }).waitFor();
      const pending = page.locator('tr').filter({ hasText: 'TEST · Enrollment simulator' });
      await pending.getByRole('button', { name: 'Approve / assign', exact: true }).waitFor();
      await page.screenshot({ path: path.join(output, 'enrollment-review-desktop.png'), fullPage: true });
      const downloadPromise = page.waitForEvent('download');
      await page.getByRole('button', { name: 'Download worker bridge settings', exact: true }).click();
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
    }

    await page.locator('nav a[href="#customers"]').click();
    await page.getByRole('heading', { name: 'Customers', exact: true }).waitFor();
    await page.getByRole('button', { name: 'Add customer', exact: true }).click();
    const malicious = 'TEST ' + Date.now() + ' <img src=x onerror="window.__injected=1">';
    await page.getByLabel('Customer name', { exact: true }).fill(malicious);
    await page.locator('#modal-form').getByRole('button', { name: 'Save', exact: true }).click();
    await page.getByText(malicious, { exact: true }).waitFor();
    assert.equal(await page.evaluate(() => window.__injected), undefined);
    assert.equal(await page.locator('img[src="x"]').count(), 0);
    done('customer values render as text, not injected HTML');

    await page.locator('tr').filter({ hasText: malicious }).getByRole('button', { name: 'Add device', exact: true }).click();
    const pendingLabel = 'TEST · Pending ' + Date.now();
    await page.getByLabel('Device nickname', { exact: true }).fill(pendingLabel);
    await page.locator('#modal-form').getByRole('button', { name: 'Save', exact: true }).click();
    await page.locator('#modal').waitFor({ state: 'hidden' });
    await page.locator('nav a[href="#devices"]').click();
    await page.locator('tr').filter({ hasText: pendingLabel }).getByRole('button', { name: 'View →' }).click();
    await page.getByRole('heading', { name: pendingLabel, exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Refresh playlist', exact: true }).isDisabled(), true);
    await page.getByRole('button', { name: 'Logs', exact: true }).click();
    await page.getByText('Not available in the backend foundation yet.', { exact: false }).waitFor();
    done('pending enrollment and unsupported features are represented honestly');

    await page.locator('nav a[href="#devices"]').click();
    await page.locator('tr').filter({ hasText: credentials.device_id }).getByRole('button', { name: 'View →' }).click();
    await page.getByText('LOCAL TEST SIMULATOR — not a real customer device.', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'Refresh playlist', exact: true }).click();
    await page.getByText('Action queued—not yet executed.', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'Commands', exact: true }).click();
    await page.getByRole('heading', { name: 'Command history', exact: true }).waitFor();
    await page.getByText('refresh playlist', { exact: true }).first().waitFor();
    assert(await page.locator('tbody .badge.pending').count() > 0);
    await page.screenshot({ path: path.join(output, 'device-commands-desktop.png'), fullPage: true });
    done('command is queued and shown pending, never faked as executed');

    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator('nav a[href="#overview"]').click();
    await page.getByRole('heading', { name: 'Operations overview', exact: true }).waitFor();
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'Mobile page overflow');
    await page.screenshot({ path: path.join(output, 'overview-mobile.png'), fullPage: true });
    done('mobile overview has no horizontal page overflow');

    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.locator('nav a[href="#resellers"]').click();
    await page.getByRole('button', { name: 'Create reseller', exact: true }).click();
    const email = 'browser-' + Date.now() + '@preview.test';
    const password = 'Browser-Test-Reseller-Password-46!';
    const company = 'TEST · Browser reseller ' + Date.now();
    await page.getByLabel('Company / workspace name', { exact: true }).fill(company);
    await page.getByLabel('Contact name', { exact: true }).fill('Test reseller');
    await page.getByLabel('Reseller email', { exact: true }).fill(email);
    await page.getByLabel('Initial password', { exact: true }).fill(password);
    await page.locator('#modal-form').getByRole('button', { name: 'Save', exact: true }).click();
    await page.locator('#modal').waitFor({ state: 'hidden' });
    await page.getByText(company, { exact: true }).waitFor();
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await page.getByLabel('Email address').waitFor();
    assert.equal(await page.locator('#main').textContent(), '');
    assert.equal(await page.locator('#modal-form input[type="password"]').count(), 0);
    await page.getByLabel('Email address').fill(email);
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByRole('heading', { name: 'Operations overview', exact: true }).waitFor();
    assert.equal(await page.locator('nav a[href="#resellers"]').count(), 0);
    const denied = await page.evaluate(async tenant => (await fetch('/api/devices?tenant_id=' + tenant)).status, ownerTenant);
    assert.equal(denied, 404);
    assert.equal(await page.evaluate(async () => (await fetch('/api/enrollments')).status), 403);
    await page.locator('nav a[href="#customers"]').click();
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
