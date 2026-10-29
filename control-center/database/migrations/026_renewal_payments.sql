CREATE TABLE renewal_policy (
 id TINYINT PRIMARY KEY,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 testing_only BOOLEAN NOT NULL DEFAULT TRUE,
 CHECK(id=1)
) ENGINE=InnoDB;
INSERT INTO renewal_policy(id) VALUES(1);
CREATE TABLE brand_staging_sources (
 source_sha CHAR(40) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 app_version VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 version_code INT UNSIGNED NOT NULL UNIQUE,
 approved_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(approved_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE renewal_merchants (
 merchant_id VARCHAR(200) PRIMARY KEY,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 FOREIGN KEY(tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE renewal_connections (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 merchant_id VARCHAR(200) NOT NULL,
 business_name VARCHAR(120) NOT NULL,
 api_cipher TEXT NOT NULL,
 webhook_cipher TEXT NOT NULL,
 mode VARCHAR(8) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(tenant_id) REFERENCES tenants(id),
 UNIQUE(id,tenant_id),
 INDEX renewal_connection_tenant(tenant_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE renewal_plans (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 package_id INT UNSIGNED NOT NULL,
 plan_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 package_snapshot TEXT NOT NULL,
 name VARCHAR(80) NOT NULL,
 currency CHAR(3) CHARACTER SET ascii NOT NULL,
 amount_minor INT UNSIGNED NOT NULL,
 revision INT UNSIGNED NOT NULL DEFAULT 1,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(tenant_id) REFERENCES tenants(id),
 UNIQUE(id,tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE renewal_challenges (
 nonce_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 device_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 key_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 app_package VARCHAR(80) NOT NULL,
 primary_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expires_at DATETIME NOT NULL,
 used_at DATETIME NULL,
 FOREIGN KEY(device_id) REFERENCES devices(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE renewal_sessions (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 token_cipher TEXT NOT NULL,
 device_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 brand_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 app_package VARCHAR(80) NOT NULL,
 key_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 account_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reseller_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 line_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 primary_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expires_at DATETIME NOT NULL,
 order_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(device_id) REFERENCES devices(id),
 FOREIGN KEY(brand_id) REFERENCES app_brands(id),
 FOREIGN KEY(tenant_id) REFERENCES tenants(id),
 INDEX renewal_session_device(device_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE renewal_orders (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 session_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 brand_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reseller_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 line_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 primary_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 connection_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 merchant_id VARCHAR(200) NOT NULL,
 plan_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 plan_snapshot TEXT NOT NULL,
 currency CHAR(3) CHARACTER SET ascii NOT NULL,
 amount_minor INT UNSIGNED NOT NULL,
 email_cipher TEXT NOT NULL,
 provider_link_id VARCHAR(200) NULL,
 provider_url TEXT NULL,
 payment_state VARCHAR(24) NOT NULL DEFAULT 'creating',
 fulfilment_state VARCHAR(24) NOT NULL DEFAULT 'not_paid',
 lease_token CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 lease_until DATETIME NULL,
 next_attempt_at DATETIME NULL,
 receipt TEXT NULL,
 expires_at DATETIME NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 paid_at DATETIME NULL,
 FOREIGN KEY(session_id) REFERENCES renewal_sessions(id),
 FOREIGN KEY(connection_id,tenant_id) REFERENCES renewal_connections(id,tenant_id),
 FOREIGN KEY(plan_id,tenant_id) REFERENCES renewal_plans(id,tenant_id),
 UNIQUE(connection_id,provider_link_id),
 INDEX renewal_order_session(session_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE renewal_sessions ADD FOREIGN KEY(order_id) REFERENCES renewal_orders(id);
CREATE TABLE renewal_events (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 connection_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 order_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 body_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 body_cipher MEDIUMTEXT NOT NULL,
 signature CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(24) NOT NULL DEFAULT 'pending',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(connection_id) REFERENCES renewal_connections(id),
 FOREIGN KEY(order_id) REFERENCES renewal_orders(id),
 UNIQUE(connection_id,body_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE renewal_simulations (
 link_id VARCHAR(200) PRIMARY KEY,
 merchant_id VARCHAR(200) NOT NULL,
 currency CHAR(3) NOT NULL,
 amount_minor INT UNSIGNED NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'active'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE renewal_settlements (
 merchant_id VARCHAR(200) NOT NULL,
 link_id VARCHAR(200) NOT NULL,
 order_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 settlement_ref VARCHAR(100) NOT NULL,
 PRIMARY KEY(merchant_id,link_id),
 UNIQUE(merchant_id,settlement_ref),
 FOREIGN KEY(order_id) REFERENCES renewal_orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE renewal_simulated_targets (
 reseller_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 line_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 primary_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'expired',
 expires_at BIGINT NOT NULL,
 plans TEXT NOT NULL,
 PRIMARY KEY(reseller_id,line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE renewal_simulated_operations (
 operation_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 receipt TEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
