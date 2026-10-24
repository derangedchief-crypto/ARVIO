# Owner branding test — revision 1

Pinned build request: `4da78da5b59dd14ea000ab3b156faf1f`.
Generated from the owner's supplied bundle with `branding/prepare.py`.
All three input image SHA256 values verified against the immutable manifest.
The owner explicitly approved the screenshot artwork for this test.

This branch alone selects `owner-test-brand` through `gradle.properties`.
No workflow, worker, website or trial-rollout settings are changed.
The existing Publish Testing APK workflow signs with the central release key.
The owner explicitly authorised replacing the rolling normal testing APK.
Customer releases remain untouched. This package installs separately from the
normal testing app. Its updater remains brand-scoped, never generic.

This is a manual test build, not a connected automatic builder. It does not mark
the website job as published or register an artifact for managed enrollment.
