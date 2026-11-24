# Kids profile shared layout

The user approved the code-based mobile preview before the testing build.
Kids now supplies verified movie/show rows to the normal HomeScreen instead of
rendering a separate button grid. This uses the existing mobile hero carousel,
rectangular media cards, row layout preferences, TV home presentation, and
Home/Search/Library bottom navigation. Profile switching uses the normal avatar.

Title details, episode browsing and source selection use the ordinary DetailsScreen.
Player navigation preserves selected sources, episode numbering and resume position.
Unverified related titles, collections, cast filmographies and reviews are withheld
from kids details. Search results and stored library items pass the kids catalogue
rating policy. The isolated navigation graph still verifies details and playback;
ordinary shelves and sports rows cannot become a loading fallback.

Version 2.0.137 (453) allows the standard test app to detect this as an update to
2.0.136. Publishing is through Publish Testing APK on staging/testing.
