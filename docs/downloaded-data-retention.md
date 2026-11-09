# Downloaded-data retention

## Replacement order

Download to a temporary file, finish/validate it, atomically install the replacement,
then remove obsolete data. Failed requests or failed imports keep the last usable
guide. Temporary extra disk space for old + new is necessary during replacement.

## Current owners

- Server EPG snapshots: keep one accepted guide after a successful usable import.
  Retire earlier full SQLite snapshots only after that import, under the same mutex
  as readers. An empty/rejected import, exception or cancellation retains fallback.
  The unchanged-manifest path also cleans historical copies. Download/decompression
  temporary files are removed on completion/failure and interrupted ones on restart.
- Indexed EPG: existing generation replacement is transactional; partial channel
  updates preserve unrelated schedules and prune expired history. SQLite reuses
  freed pages, so file size need not immediately shrink when rows are removed.
- Channel/compact IPTV snapshots: same filenames replaced atomically after the new
  compressed payload is written and synced, rather than truncating the old file.
- Movie/series/category catalogues: existing CatalogueDiskFile already streams a
  compressed replacement to a temporary file, syncs it and atomically replaces the
  old file. These are scoped by provider credentials, not downloaded once per refresh.
- Image/HTTP/playback caches: existing size-limited eviction (image disk 48MiB,
  HTTP 50MiB, Media3 playback 256MiB). These are rolling caches, not archive histories.
- Update APKs: completed files for this installed package at the installed or older
  version are removed on app startup in the background, from only the two owned
  update directories. Newer/unrecognized/different-package archives are retained.
  Downloads now stage and atomically replace instead of deleting the old target
  before downloading. Failed/cancelled staging is removed; interrupted staging is
  cleaned once per directory on first use. Cleanup and promotion share a lock.

This is retention for these owned download caches, not a global delete sweep or a
fixed upper bound on all app storage. Profile/account state, user downloads and
third-party stores (e.g. Telegram) have their own lifecycles. A provider's current
guide/catalogue can itself grow; a device needs enough free space for a replacement.
