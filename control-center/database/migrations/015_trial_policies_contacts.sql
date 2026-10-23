CREATE TABLE trial_plan_catalogs (
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 catalog_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 panel_reseller_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 plans JSON NOT NULL,
 fetched_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 expires_at DATETIME NOT NULL,
 FOREIGN KEY(tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE trial_plan_grants (
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 version INT UNSIGNED NOT NULL DEFAULT 1,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 allowed_plans JSON NOT NULL,
 updated_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 FOREIGN KEY(tenant_id) REFERENCES tenants(id),
 FOREIGN KEY(updated_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE trial_reseller_defaults (
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 revision INT UNSIGNED NOT NULL DEFAULT 1,
 grant_version INT UNSIGNED NOT NULL,
 plan_id INT UNSIGNED NOT NULL,
 plan_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 updated_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 FOREIGN KEY(tenant_id) REFERENCES tenants(id),
 FOREIGN KEY(updated_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE trial_contacts (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 operation_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 brand_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 customer_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 device_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 contact_cipher TEXT NULL,
 panel_line_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 plan_id INT UNSIGNED NOT NULL,
 state VARCHAR(24) NOT NULL,
 expires_at DATETIME NULL,
 trial_expires_at DATETIME NULL,
 contact_retention_until DATETIME NOT NULL,
 status_checked_at DATETIME NULL,
 panel_status_revision_us BIGINT UNSIGNED NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 UNIQUE KEY trial_eligibility(tenant_id,customer_ref,device_ref),
 KEY trial_contact_expiry(contact_retention_until),
 KEY trial_contact_workspace(tenant_id,created_at),
 FOREIGN KEY(tenant_id) REFERENCES tenants(id),
 FOREIGN KEY(brand_id) REFERENCES app_brands(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
