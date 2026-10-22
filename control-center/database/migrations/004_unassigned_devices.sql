ALTER TABLE devices ADD COLUMN reseller_detached BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE commands DROP FOREIGN KEY commands_ibfk_1, ADD CONSTRAINT commands_device_tenant_fk FOREIGN KEY (device_id,tenant_id) REFERENCES devices(id,tenant_id) ON UPDATE CASCADE;
