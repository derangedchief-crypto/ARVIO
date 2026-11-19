# Managed scraper replacement

The entitlement guard and Xtream sign-in use the same serialized installation
path. A verified provider grant is still required; an APK update alone does not
grant access. Startup's unthrottled entitlement check picks up a changed build URL.

The repository hydrates and validates the replacement manifest before editing
storage. One DataStore transaction writes the new addon, removes owned old
configurations and persists a local ownership receipt. A failed/cancelled download
leaves the previous source in place. Disabled state is preserved during rotation.

The receipt records installed IDs and URL fingerprints, including retired ones.
Cloud restore paths read it inside their write transaction and retain the local
managed configuration rather than restoring retired sources. A successful
revocation persists a tombstone so cloud restore cannot regrant it. Ordinary addon
mutations are fenced against reintroducing retired managed entries too.

For installations predating ownership tracking, exact SHA-256 fingerprints of the
official 2.0.134 and 2.0.135 URLs identify obsolete configurations. No credentials
are committed. The migration does not delete every addon sharing Torrentio's host
or manifest ID: separately configured user addons remain untouched. Unknown legacy
URLs without a tracking receipt are deliberately not guessed at.

Tests cover transactional replacement through the actual repository/DataStore,
offline/cancelled fetches, repository recreation, each cloud import entry point,
concurrent cloud pull during download, explicit revocation, disabled-state
preservation and untracked known-legacy cleanup. Validation runs in GitHub Build
Check; this staging change does not publish an APK or change main.
