(function (root) {
  'use strict';
  const refs = rows => rows.map(r => r.ref);
  const equal = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);
  function validate(c) {
    if (!c || c.page_size !== 2000 || c.page !== 1 || c.pages !== 1 || !Array.isArray(c.items) || c.items.length !== c.total || c.total < 1 || c.total > 2000 || new Set(refs(c.items)).size !== c.total) throw new Error('Read a complete layout with the newer app before editing.');
    return c;
  }
  function create(catalog, id) {
    validate(catalog);
    return { base: catalog, snapshotId: id, items: catalog.items.map(r => ({ ...r })), providerOrder: catalog.provider_order, dirty: false, mod: 0, selected: new Set(), filter: '', page: 1, push: null, readId: null, available: null, needsFresh: false };
  }
  function mark(d) {
    const base = new Map(d.base.items.map(r => [r.ref, r]));
    d.mod++;
    d.dirty = d.providerOrder !== d.base.provider_order || !equal(refs(d.items), refs(d.base.items)) || d.items.some(r => r.hidden !== base.get(r.ref)?.hidden);
    return d;
  }
  function move(d, ref, position) {
    const index = d.items.findIndex(r => r.ref === ref); if (index < 0) return d;
    const target = Math.max(0, Math.min(d.items.length - 1, Number(position) - 1));
    if (!Number.isInteger(target) || index === target) return d;
    const [row] = d.items.splice(index, 1); d.items.splice(target, 0, row); d.providerOrder = false; return mark(d);
  }
  function visibility(d, selected, hidden) { const set = new Set(selected); d.items = d.items.map(r => set.has(r.ref) ? { ...r, hidden } : r); return mark(d); }
  function block(d, selected, edge) {
    const set = new Set(selected), picked = d.items.filter(r => set.has(r.ref)), rest = d.items.filter(r => !set.has(r.ref));
    if (!picked.length) return d;
    d.items = edge === 'top' ? [...picked, ...rest] : [...rest, ...picked]; d.providerOrder = false; return mark(d);
  }
  function provider(d) { d.items.sort((a, b) => a.provider_position - b.provider_position); d.providerOrder = true; return mark(d); }
  function template(d) { return { entries: d.items.filter(r => r.match_name !== null).map(r => ({ name: r.match_name, hidden: r.hidden })), excluded: d.items.filter(r => r.match_name === null).map(r => r.label) }; }
  function preview(d, entries, visibilityToo = true) {
    const byName = new Map(), ambiguous = [];
    for (const row of d.items) if (row.match_name !== null) { if (byName.has(row.match_name)) ambiguous.push(row.match_name); else byName.set(row.match_name, row); }
    const matched = [], missing = [], seen = new Set();
    for (const entry of entries) { if (seen.has(entry.name)) { ambiguous.push(entry.name); continue; } seen.add(entry.name); const row = byName.get(entry.name); if (row) matched.push({ ...row, hidden: visibilityToo ? entry.hidden : row.hidden }); else missing.push(entry.name); }
    const picked = new Set(refs(matched)); const unmatched = d.items.filter(r => !picked.has(r.ref));
    return { matched, missing, unmatched, ambiguous, items: [...matched, ...unmatched] };
  }
  function applyTemplate(d, entries, visibilityToo = true) {
    const p = preview(d, entries, visibilityToo); if (p.ambiguous.length) throw new Error('Ambiguous names: template was not applied.');
    if (!p.matched.length) throw new Error('No exact portable category names match this source. Nothing was changed.');
    d.items = p.items; d.providerOrder = false; mark(d); return p;
  }
  function rebasePreview(d, fresh) {
    validate(fresh); if (fresh.profile_ref !== d.base.profile_ref || fresh.source_ref !== d.base.source_ref) throw new Error('The profile/source changed. Save your draft as a template, then explicitly load the new source.');
    const old = new Set(refs(d.items)), next = new Set(refs(fresh.items));
    return { added: fresh.items.filter(r => !old.has(r.ref)), removed: d.items.filter(r => !next.has(r.ref)) };
  }
  function rebase(d, fresh, id) {
    const diff = rebasePreview(d, fresh); const base = new Map(d.base.items.map(r => [r.ref, r])), current = new Map(d.items.map(r => [r.ref, r]));
    const changedOrder = d.providerOrder !== d.base.provider_order || !equal(refs(d.items), refs(d.base.items));
    const rows = new Map(fresh.items.map(row => { const edited = current.get(row.ref), previous = base.get(row.ref); return [row.ref, { ...row, hidden: edited && previous && edited.hidden !== previous.hidden ? edited.hidden : row.hidden }]; }));
    let next;
    if (!changedOrder) next = fresh.items.map(r => rows.get(r.ref));
    else if (d.providerOrder) next = [...rows.values()].sort((a, b) => a.provider_position - b.provider_position);
    else next = [...d.items.filter(r => rows.has(r.ref)).map(r => rows.get(r.ref)), ...fresh.items.filter(r => !current.has(r.ref)).map(r => rows.get(r.ref))];
    d.providerOrder = changedOrder ? d.providerOrder : fresh.provider_order; d.base = fresh; d.snapshotId = id; d.items = next; d.available = null; d.needsFresh = false; d.selected = new Set([...d.selected].filter(ref => rows.has(ref))); mark(d); return diff;
  }
  function payload(d) { validate({ ...d.base, items: d.items }); return { profile_ref: d.base.profile_ref, source_ref: d.base.source_ref, expected_revision: d.base.revision, order: refs(d.items), hidden: d.items.filter(r => r.hidden).map(r => r.ref), provider_order: d.providerOrder }; }
  const api = { create, mark, move, visibility, block, provider, template, preview, applyTemplate, rebasePreview, rebase, payload };
  root.LayoutDraft = api; if (typeof module !== 'undefined') module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : window);
