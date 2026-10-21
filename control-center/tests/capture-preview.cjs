// Capture a fresh, explicitly synthetic local preview without adding test attack records.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || '/tmp/opencode/control-center-browser-tools/node_modules/playwright');
(async () => {
  const url = process.env.CONTROL_PREVIEW_URL || 'http://127.0.0.1:8187';
  assert(['127.0.0.1', 'localhost'].includes(new URL(url).hostname));
  const credentials = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  assert.equal(credentials.email, 'owner@preview.test');
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const output = path.resolve(__dirname, '../test-results'); fs.mkdirSync(output, { recursive: true });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  try {
    await page.goto(url);
    await page.getByLabel('Email address').fill(credentials.email);
    await page.getByLabel('Password', { exact: true }).fill(credentials.password);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByRole('heading', { name: 'Operations overview', exact: true }).waitFor();
    await page.screenshot({ path: path.join(output, 'preview-desktop.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await page.screenshot({ path: path.join(output, 'preview-mobile.png'), fullPage: true });
    assert.deepEqual(errors, []);
    console.log('Captured real local desktop/mobile preview; all records are explicitly synthetic test devices.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
