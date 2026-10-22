const test = require('node:test');
const assert = require('node:assert/strict');
const D = require('../public/assets/layout-editor.js');
const hex = n => n.toString(16).padStart(64, '0');
function catalog(n = 60) { return { page_size: 2000, page: 1, pages: 1, total: n, profile_ref: hex(10000), source_ref: hex(20000), revision: hex(30000), provider_order: true, order_editable: true, items: Array.from({ length: n }, (_, i) => ({ ref: hex(i + 1), label: 'Category ' + (i + 1), match_name: 'Category ' + (i + 1), hidden: false, position: i + 1, provider_position: i + 1 })) }; }
test('many local edits produce one complete payload without any device transport', () => {
  const d = D.create(catalog(), 'snapshot');
  D.move(d, hex(60), 1); D.block(d, [hex(10), hex(20)], 'top'); D.visibility(d, [hex(1), hex(30), hex(60)], true);
  const p = D.payload(d); assert.equal(p.order.length, 60); assert.equal(new Set(p.order).size, 60); assert.equal(p.hidden.length, 3); assert.equal(p.provider_order, false); assert.equal(d.dirty, true);
  assert.deepEqual(p.order.slice(0, 3), [hex(10), hex(20), hex(60)]);
});
test('templates use exact safe names across different device refs and retain unmatched data', () => {
  const a = D.create(catalog(3), 'a'); D.move(a, hex(3), 1); D.visibility(a, [hex(3)], true);
  const entries = D.template(a).entries;
  const other = catalog(4); other.items.forEach((r, i) => r.ref = hex(100 + i)); other.items[3].hidden = true;
  const b = D.create(other, 'b'); const preview = D.preview(b, [...entries, { name: 'Not on target', hidden: true }]);
  assert.equal(preview.matched.length, 3); assert.deepEqual(preview.missing, ['Not on target']); assert.equal(preview.unmatched.length, 1);
  D.applyTemplate(b, entries); assert.equal(b.items[0].match_name, 'Category 3'); assert.equal(b.items[0].hidden, true); assert.equal(b.items[3].hidden, true); assert.equal(b.items[0].ref, hex(102));
});
test('shortened/redacted and ambiguous names are not silently matched', () => {
  const c = catalog(3); c.items[0].match_name = null; const d = D.create(c, 'x'); assert.equal(D.template(d).excluded.length, 1);
  assert.throws(() => D.applyTemplate(d, [{ name: 'Category 1', hidden: true }]), /No exact/);
  assert.throws(() => D.applyTemplate(d, [{ name: 'Category 2', hidden: true }, { name: 'Category 2', hidden: false }]), /Ambiguous/);
});
test('rebase keeps edited visibility/order, adopts untouched local changes and appends new categories', () => {
  const d = D.create(catalog(3), 'old'); D.move(d, hex(3), 1); D.visibility(d, [hex(1)], true);
  const fresh = catalog(4); fresh.revision = hex(30001); fresh.items[1].hidden = true; fresh.items[3].hidden = true;
  const diff = D.rebase(d, fresh, 'new'); assert.equal(diff.added.length, 1); assert.equal(d.snapshotId, 'new');
  assert.deepEqual(d.items.map(r => r.ref), [hex(3), hex(1), hex(2), hex(4)]); assert.equal(d.items.find(r => r.ref === hex(1)).hidden, true); assert.equal(d.items.find(r => r.ref === hex(2)).hidden, true); assert.equal(d.items.find(r => r.ref === hex(4)).hidden, true);
  assert.equal(D.payload(d).expected_revision, fresh.revision);
});
test('source/profile changes never rebase an old device draft by guessed names', () => {
  const d = D.create(catalog(3), 'old'); D.move(d, hex(3), 1); const fresh = catalog(3); fresh.source_ref = hex(999);
  assert.throws(() => D.rebase(d, fresh, 'new'), /profile\/source changed/); assert.equal(d.snapshotId, 'old'); assert.equal(d.items[0].ref, hex(3));
});
test('provider mode stays explicit and rejects paged snapshots', () => {
  const d = D.create(catalog(3), 'old'); D.move(d, hex(3), 1); D.provider(d); assert.equal(d.providerOrder, true); assert.equal(d.dirty, false);
  assert.throws(() => D.create({ ...catalog(), page_size: 50 }, 'partial'), /complete layout/);
});
