CREATE TABLE panel_resellers (
 panel_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 username VARCHAR(150) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL UNIQUE,
 linked_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 linked_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (tenant_id) REFERENCES tenants(id),
 FOREIGN KEY (linked_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE panel_customers (
 panel_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 account_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 customer_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reseller_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 FOREIGN KEY (customer_id,tenant_id) REFERENCES customers(id,tenant_id),
 FOREIGN KEY (reseller_id) REFERENCES panel_resellers(panel_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
