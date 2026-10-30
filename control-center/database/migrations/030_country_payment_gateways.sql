ALTER TABLE renewal_connections
 ADD gateway VARCHAR(12) CHARACTER SET ascii NOT NULL DEFAULT 'rampex',
 ADD provider_options_cipher TEXT NULL;
ALTER TABLE renewal_plans ADD country CHAR(2) CHARACTER SET ascii NOT NULL DEFAULT '';
ALTER TABLE renewal_sessions
 ADD country CHAR(2) CHARACTER SET ascii NULL,
 ADD gateway VARCHAR(12) CHARACTER SET ascii NULL;
ALTER TABLE renewal_events MODIFY signature VARCHAR(1024) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;
ALTER TABLE renewal_orders ADD country CHAR(2) CHARACTER SET ascii NULL;
ALTER TABLE renewal_settlements MODIFY settlement_ref VARCHAR(220) NOT NULL;
CREATE TABLE renewal_gateway_routes (
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 country CHAR(2) CHARACTER SET ascii NOT NULL,
 gateways TEXT NOT NULL,
 revision INT UNSIGNED NOT NULL DEFAULT 1,
 PRIMARY KEY(tenant_id,country),
 FOREIGN KEY(tenant_id) REFERENCES tenants(id),
 CHECK(country IN ('US','CA'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Reserve the real callback URL before the reseller creates a provider
-- webhook and obtains its endpoint-specific signing secret.
CREATE TABLE renewal_connection_setups (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 gateway VARCHAR(12) CHARACTER SET ascii NOT NULL,
 created_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 used_at DATETIME NULL,
 FOREIGN KEY(tenant_id) REFERENCES tenants(id),
 FOREIGN KEY(created_by) REFERENCES users(id),
 INDEX renewal_setup_workspace(tenant_id,gateway,used_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
