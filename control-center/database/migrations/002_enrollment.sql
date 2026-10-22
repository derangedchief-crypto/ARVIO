ALTER TABLE devices ADD COLUMN app_package VARCHAR(80) NULL, ADD COLUMN key_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;

CREATE TABLE platform_settings (
 id TINYINT PRIMARY KEY,
 enrollment_enabled BOOLEAN NOT NULL DEFAULT FALSE,
 CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO platform_settings (id) VALUES (1);

CREATE TABLE managed_accounts (
 account_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 customer_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 account_hint VARCHAR(40) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (customer_id, tenant_id) REFERENCES customers(id, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE enrollment_challenges (
 nonce_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 installation_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 key_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 app_package VARCHAR(80) NOT NULL,
 expires_at DATETIME NOT NULL,
 used_at DATETIME NULL,
 request_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 INDEX enrollment_challenge_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE enrollment_requests (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 installation_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 key_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 public_key VARCHAR(256) NOT NULL,
 account_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 account_hint VARCHAR(40) NOT NULL,
 app_package VARCHAR(80) NOT NULL,
 label VARCHAR(120) NOT NULL,
 model VARCHAR(120) NOT NULL,
 os_version VARCHAR(80) NOT NULL,
 app_version VARCHAR(40) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'pending',
 poll_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 poll_cipher TEXT NULL,
 credential_cipher TEXT NULL,
 credential_expires_at DATETIME NULL,
 poll_expires_at DATETIME NOT NULL,
 confirmed_at DATETIME NULL,
 device_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 reviewed_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_request_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE enrollment_identity (installation_id, key_fingerprint, account_ref, app_package),
 FOREIGN KEY (device_id) REFERENCES devices(id),
 FOREIGN KEY (reviewed_by) REFERENCES users(id),
 INDEX enrollment_review (status, last_request_at),
 INDEX enrollment_poll_expiry (poll_expires_at),
 CHECK (status IN ('pending','approved','rejected'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE enrollment_proofs (
 proof_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 nonce_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX enrollment_proof_expiry (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
