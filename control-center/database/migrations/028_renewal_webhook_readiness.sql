ALTER TABLE renewal_connections ADD COLUMN webhook_verified_at DATETIME NULL;
-- Bind a paid invoice to the expired line revision it quoted. Legacy unknown
-- invoices are retained, not guessed or replayed against an already renewed line.
ALTER TABLE renewal_orders ADD COLUMN quoted_expiry BIGINT NULL;
CREATE INDEX renewal_order_merchant_link ON renewal_orders(tenant_id,merchant_id,provider_link_id);
