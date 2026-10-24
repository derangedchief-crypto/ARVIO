# Owner website bridge setup

This package is for the WEBSITE server38.190.133.176:22, not the worker server or
ai-coding. It contains a dedicated PUBLIC SSH key, never a signing/private SSH key.
The corresponding private SSH key remains operator-controlled for GitHub only.

1. Install Extreme-Control-Center-0.14.1-Update.zip over the existing application
   folder /www/wwwroot/control.fecxbrfzku.net. Preserve config.php/storage/original
   keys. No database migration beyond017, no PHP reinstall, no trial enablement.
2. Extract this setup bundle into a root-only folder OUTSIDE public/, for example
   /root/extreme-brand-bridge. On this website host, install the Ubuntu acl package
   if setfacl/getfacl are unavailable. No other package changes are needed.
3. Run `bash /root/extreme-brand-bridge/install.sh --check` as root. It checks the
   actual site version, files, PHP8.4 extensions, public key and ACL prerequisites
   without creating an account or changing any ACL/config. Stop on any refusal.
4. Run `bash /root/extreme-brand-bridge/install.sh` as root. It creates only the
   restricted brandbridge account, root-owned fixed wrapper/authorization, narrow
   read-only source/config/key ACLs and dedicated writable APK storage. It preserves
   original file CONTENTS and verifies the account cannot write source/config/key.
   Only the APK directory receives builder writes. The account has no sudo or web
   group membership, no usable password and no user-editable authorized keys.
5. Send the printed completion status and PUBLIC host-key SHA256 fingerprint to the
   operator for pinned-host verification. Do not send a password, private key,
   config.php content or application.key. No tunnel or root SSH access is needed.

The fixed command uses /www/server/php/84/bin/php with CLI opcache disabled and
diagnostics on stderr, without touching php.ini or changing the website's PHP.
It ignores SSH_ORIGINAL_COMMAND. SSH restrict prevents PTY/forwarding/user rc;
arbitrary shell commands/SFTP are not supported by this account. The PHP bridge
accepts only bounded named queue operations and frozen-receipt APK transfers.

Installer limits: it needs root for account/ACL setup, refuses unknown account/key
reuse, symlinks and masked pre-existing ACLs, and does not enable the builder,
publication, trials or a dispatcher timer. It does not change existing workers,
SSH daemon configuration, website config CONTENTS or encryption keys. Because ACLs
are explicit, do not recursively chmod/chown the site after installation; that can
remove the bridge's narrow read permissions. Keep backup copies of existing ACLs
as part of the owner's normal server backup before making permission changes.
The installer also retains its first pre-change ACL snapshot at
/var/lib/extreme-brand-bridge/pre-setup.acl, readable only by root. It does not
automatically restore/delete anything; any recovery is an explicit owner action.

Once the restricted connection is verified, the operator configures the existing
private site's source pin/delivery/build-only policy and GitHub bridge secrets.
Build-only -> exact-job owner-approved publication -> new-revision updater pilot
must succeed before any unattended dispatcher timer or production publication.
