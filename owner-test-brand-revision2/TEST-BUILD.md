# Owner branding update test — revision 2

Generated from the owner's supplied revision 2 bundle using branding/prepare.py.
Same permanent brand/package and artwork as revision 1; supplied name is now
Extreme IPTV. Version code increases from 43000001 to 43000002.

Use the unchanged centrally signed testing workflow, retain a separately named
APK, and restore the generic testing114 download immediately after publication.
Do not merge this test-only Gradle selection into the reseller branch.

APK identity, certificate, version and artwork checks do not establish on-device
data preservation. The owner must install over revision 1 without uninstalling
or clearing data and check login, favourites, settings and playback.
Trials stay off; this does not connect the website build job or updater channel.
