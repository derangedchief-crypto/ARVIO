<?php
// Configure this aaPanel website to use PHP 8.1+ and HTTPS.
// The private setup key is generated ON YOUR SERVER, never shipped in the ZIP.
return [
    'origin' => 'https://hubmanager.extremeiptv.net',
    'setup_key' => '',
    // Set the SAME private random 32+ character secret as EPG_ADMIN_SECRET
    // on the EPG worker. Never put this secret into browser JavaScript or a URL.
    'epg_worker_url' => 'https://activationworker.extremeiptv.net',
    'epg_worker_secret' => '',
];
