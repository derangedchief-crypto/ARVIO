ALTER TABLE enrollment_requests ADD COLUMN panel_reseller_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL, ADD COLUMN panel_customer_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL, ADD INDEX enrollment_panel_review (status,panel_reseller_id,last_request_at);

UPDATE enrollment_requests SET poll_expires_at=UTC_TIMESTAMP() WHERE device_id IS NULL AND panel_reseller_id IS NULL;
