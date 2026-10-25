ALTER TABLE platform_settings
 ADD COLUMN trial_offers_enabled BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN trial_control_revision INT UNSIGNED NOT NULL DEFAULT 1;

-- Preserve an already explicitly published offer on upgrade. This never changes
-- private deployment/worker gates, publication stamps, seller choices or quotas.
UPDATE platform_settings SET trial_offers_enabled=EXISTS(
 SELECT 1 FROM trial_offer_publications WHERE enabled=1
) WHERE id=1;
