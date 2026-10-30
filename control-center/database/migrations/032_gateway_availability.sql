ALTER TABLE renewal_orders ADD checkout_superseded TINYINT(1) NOT NULL DEFAULT 0;
CREATE TABLE renewal_gateway_availability (
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 gateway VARCHAR(12) CHARACTER SET ascii NOT NULL,
 enabled TINYINT(1) NOT NULL DEFAULT 1,
 revision INT UNSIGNED NOT NULL DEFAULT 1,
 PRIMARY KEY(tenant_id,gateway),
 FOREIGN KEY(tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
