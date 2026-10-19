# Third-party notices

## OwnTV Core and Player Core

The IPTV replacement uses OwnTV Core and OwnTV Player Core from
<https://github.com/ahXN00/OwnTV_Core>.

Copyright © OwnTV contributors.

Licensed under the GNU General Public License, version 3.0 or later. The
combined ARVIO distribution containing these components must be distributed in
accordance with GPL-3.0-or-later, including providing corresponding source.

The ARVIO IPTV adapter is a separate integration layer. ARVIO's movies, shows,
catalog, scraper, account and cloud features remain independently maintained.

## OwnTV TV guide adaptations

The canvas programme-strip renderer and programme/time-cursor navigation in
`OwnTvGuideViewport.kt` and `OwnTvGuideNavigation.kt` are adapted from
`features/epg/GuideCore.kt` and `features/epg/EpgScreen.kt` in
<https://github.com/ahXN00/OwnTV>, revision
`cead977803d90bd62ece69b4140e373f01feddca`.

Copyright © OwnTV contributors. GPL-3.0-or-later. Modifications for ARVIO replace
theme, scaling and actions with ARVIO adapters, retain provider identifiers and
use OwnTV Core programme entities and deduplication.
