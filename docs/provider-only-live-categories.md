# Provider-only Live TV categories

Live TV must not invent country, genre or quality categories from channel names
or metadata. Both full tree builders previously attached generated categories
under All Channels; the phone category picker flattened those children alongside
the real playlist groups. This could display Poland / PL General or US genre
groups without any additional playlist being configured.

Remove generated tree nodes and inferred index buckets in full, startup and paged
paths. Reject old saved synthetic category IDs instead of reconstructing them.
The existing screen selection guard falls back to All Channels when an old ID
is absent. Channel-to-category navigation uses only its actual provider group.

All Channels, Favourites and Recently Watched remain navigation controls over
existing playlist channels; they do not create sources or content. Provider
category names remain unchanged, including country/genre labels when the provider
actually supplies them. Hidden-category preferences and explicit group order are
preserved. No credentials, activation binding, source configuration, playlist
permissions, package identity or signing configuration is changed.

GitHub Build Check compiles and runs regressions across raw/enriched trees,
startup/paged indexes, the flattened category picker, exact provider labels,
hidden groups, custom order and stale synthetic selections. No local Android
build or customer APK publication is part of this change.
