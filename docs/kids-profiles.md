# Kids profiles

Enable **Kids profile** when creating or editing a profile. The existing profile
flag is persisted and cloud-synced with the profile. Kids profiles show a separate
movie/show catalogue and search, with season and episode selection and the normal
player/source-resolution and activation-expiry checks.

The policy is a conservative children/family selection for ages 12 and under:
TMDB Family or Kids classification AND a known US rating (movies G/PG; television
TV-Y/TV-Y7/TV-Y7-FV/TV-G/TV-PG). Animation alone is not sufficient. Unknown,
unrated, conflicting or unavailable classifications are excluded. This means
some suitable international titles will not appear until metadata is available.
Ratings are checked again before displaying episode selection and before mounting
the player, including next-episode navigation. No cached adult homepage, generic
recommendations, addon catalogue or unfiltered search is used in this graph.

Live TV is disabled in Kids profiles. The ordinary navigation graph, mobile bottom
bar and external title/install-pack navigation are not exposed while Kids is
active. Back and Switch profile return to the existing profile selector. Existing
profile PINs still govern entry/editing of locked profiles; parents should lock
adult profiles using their existing PIN controls. Kids is a content profile, not
a replacement for those profile locks.

Validation: GitHub Build Check compiles the APK and runs Kids rating/repository
regressions alongside existing tests. Physical phone and TV checks should cover
profile creation/edit/restart, D-pad focus, search, episode playback, profile
switching and expired-line blocking before customer release.
