# Live category refresh — 2.0.132 testing follow-up

The user confirmed missing Live TV categories containing channels after installing
the 2.0.131 testing update. That build already requests `get_live_categories` and
`get_live_streams` on foreground entry and at five-minute process-alive intervals.
The following code defects can nevertheless leave old category data visible;
they have not been attributed to a captured request from the user's phone.

## Corrections

- Live TV enrichment now keys both its effect and cached tree on the refreshed
  snapshot's `loadedAt` generation. Previously the small-list key used only count
  and first/last IDs, and the paged key used count and selection. Category changes
  with unchanged channel identities could therefore reuse the previous tree.
  The snapshot carries its generation with its rows, avoiding separate-flow races.
- Provider live and launch VOD catalogue clients bypass the shared HTTP response
  cache. A scheduled check must reach the provider rather than reuse a fresh HTTP
  cache entry.
- The guarded, complete-source refresh can replace a larger SQLite channel store
  with a smaller result. The old partial-startup safeguard rejected all shrinking
  writes, leaving the paged category index on the previous playlist. Partial
  startup writes retain their original protection; failed sources retain their
  previous rows in the complete-source merge.

The regression adds a populated category, moves an existing channel into it while
keeping channel count/IDs constant, and checks both memory and SQLite. Another
case starts with over 10,000 stored channels and verifies a smaller complete
refresh replaces obsolete categories. Existing failed/malformed catalogue,
profile switch, reopen and scheduler checks remain in the test selection.

The release preparation advances staging to 2.0.132 / 448. The existing Publish
Testing APK workflow produces `com.arvio.tv.test` / `2.0.132-test`; this does not
publish a customer release. Management identity, signing configuration, user
favourites and hidden-group preferences are unchanged.
