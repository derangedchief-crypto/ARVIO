-- Checkout-only login capabilities. No device enrollment or app grants created.
ALTER TABLE renewal_sessions
 MODIFY device_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD login_installation_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD login_public_key VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NULL;
CREATE TABLE renewal_login_challenges (
 nonce_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 installation_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 key_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 app_package VARCHAR(80) NOT NULL,
 expires_at DATETIME NOT NULL,
 used_at DATETIME NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
