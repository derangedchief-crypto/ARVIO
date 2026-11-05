const assert = require('node:assert/strict');
const fs = require('node:fs');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || '/tmp/opencode/control-center-browser-tools/node_modules/playwright');
(async () => {
  const base = process.env.CONTROL_PREVIEW_URL;
  assert.ok(['localhost', '127.0.0.1'].includes(new URL(base).hostname), 'Local fixtures only');
  const credentials = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({viewport: {width:1440,height:960}});
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(base);
    await page.getByLabel('Email address').fill(credentials.email);
    await page.getByLabel('Password', {exact:true}).fill(credentials.password);
    await page.getByRole('button', {name:'Sign in',exact:true}).click();
    await page.locator('.dashboard-metrics').waitFor();
    const endpoint = '**/api/devices/' + credentials.device_id + '/inspection';
    let turn = 0, denied = false;
    await page.route(endpoint, async route => {
      if (denied) return route.fulfill({status:403,json:{error:'Device access changed.'}});
      const response = await route.fetch();
      const data = await response.json();
      // Force completed-result revisions to exercise replacement during editing.
      data.revision = 'fixture-revision-' + (++turn);
      await route.fulfill({response,json:data});
    });
    await page.evaluate(id => {location.hash='device/'+id;}, credentials.device_id);
    await page.locator('.device-desktop-grid').waitFor();
    await page.getByRole('button', {name:'Playback',exact:true}).click();
    const buffer = page.locator('#playback-field-buffer');
    await buffer.waitFor();
    await buffer.selectOption('5');
    await page.locator('h1').click();
    await page.waitForResponse(response => response.url().endsWith('/inspection'));
    await page.getByText('New data is ready. Your unsaved edits are preserved.', {exact:true}).waitFor();
    assert.equal(await buffer.inputValue(), '5');
    assert.equal(await page.getByRole('button', {name:/^(Read current|Load settings|Refresh this source)/}).count(), 0);
    const columns = await page.locator('.device-desktop-grid').evaluate(e => getComputedStyle(e).gridTemplateColumns);
    assert.equal(columns.split(' ').length, 2, 'Desktop workspace must have two columns');
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    denied = true;
    await page.getByText('Device access changed. Return to your device list.', {exact:true}).waitFor({timeout:15000});
    assert.equal(await buffer.count(), 0, 'Revoked access must remove the editor');
    assert.deepEqual(errors, []);
    console.log('PASS automatic inspection, dirty draft retention, desktop/mobile workspace, and revoked access');
  } finally { await browser.close(); }
})().catch(error => {console.error(error);process.exitCode=1;});
