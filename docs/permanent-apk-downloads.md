# Permanent APK download links

Use these as go.aftvnews destinations rather than version-specific assets:

- Main customer app: `https://github.com/derangedchief-crypto/ARVIO/releases/latest/download/ExtremeTV.apk`
- Main testing app: `https://github.com/derangedchief-crypto/ARVIO/releases/download/staging-testing/ExtremeTV-Test-latest.apk`
- Each owner/reseller brand: `https://control.fecxbrfzku.net/downloads/latest/<brand-id>/<testing-or-production>/App.apk`

The My app download card provides the full branded URL and a copy button. Its
filename remains `App.apk`; brand identity and channel are fixed in the URL.
The request streams the latest verified, locally mirrored artifact in that brand's
channel. Queued/failed builds do not change it. HEAD and Range are supported;
responses are no-store and use the APK digest as ETag. An If-Range digest from an
older APK causes a full response rather than splicing bytes from two versions.

Version-specific download routes remain immutable for app updater integrity.
Existing short codes aimed at them need their destination updated once, if the
shortener allows it; otherwise create a code for the permanent URL. No access to
the customer's shortener account is required or assumed by the control site.

Customer releases retain their versioned APK and publish a byte-identical
`ExtremeTV.apk` alongside it. The current v2.0.136 alias was seeded from its
existing release APK after verifying its GitHub digest and size.

Deploy `Stable-App-Downloads-0.42.0.zip` on the control website with
`python3 install-stable-downloads.py Stable-App-Downloads-0.42.0.zip`, reload
PHP-FPM and refresh the browser. Existing builds do not need to be rebuilt.
