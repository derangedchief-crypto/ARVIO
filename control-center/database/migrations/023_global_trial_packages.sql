CREATE TABLE trial_global_packages (
 id TINYINT UNSIGNED PRIMARY KEY,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 revision INT UNSIGNED NOT NULL DEFAULT 1,
 allowed_plans JSON NOT NULL,
 updated_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 FOREIGN KEY(updated_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- The explicit website upgrade opts into this model. SQL alone does not broaden
-- old configuration-only approvals or silently switch any app on.
INSERT INTO trial_global_packages (id,allowed_plans) VALUES (1,JSON_OBJECT());
ALTER TABLE trial_reseller_defaults
 ADD COLUMN global_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN daily_limit INT UNSIGNED NOT NULL DEFAULT 50;

-- Preserve already-explicit app opt-ins from0.21, not old configuration-only
-- approvals. New/future apps still choose their own settings before going live.
UPDATE trial_reseller_defaults d
 JOIN tenants t ON t.id=d.tenant_id AND t.kind='reseller'
 JOIN trial_plan_grants g ON g.tenant_id=d.tenant_id AND g.enabled=1 AND g.live_enabled=1
 JOIN trial_offer_publications p ON p.tenant_id=d.tenant_id AND p.enabled=1
 SET d.global_opt_in=1,d.daily_limit=p.daily_limit
 WHERE d.enabled=1 AND p.daily_limit BETWEEN 1 AND 1000;
