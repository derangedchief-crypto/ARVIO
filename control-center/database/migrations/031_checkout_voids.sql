ALTER TABLE renewal_orders
 ADD provider_checkout_id VARCHAR(180) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD void_state VARCHAR(20) CHARACTER SET ascii NULL,
 ADD void_token CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL;
CREATE TABLE renewal_route_switches (
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 line_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 primary_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 token CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(20) CHARACTER SET ascii NOT NULL,
 country CHAR(2) CHARACTER SET ascii NOT NULL,
 gateway VARCHAR(12) CHARACTER SET ascii NOT NULL,
 quoted_expiry BIGINT NOT NULL,
 lease_until DATETIME NOT NULL,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,line_id,primary_fingerprint),
 FOREIGN KEY(tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
