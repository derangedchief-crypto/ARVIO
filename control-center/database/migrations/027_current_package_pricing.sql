-- Simulator-only assignment mirrors the real panel customers.package_id.
-- NULL legacy demo assignments fail closed; never guess a first catalog package.
ALTER TABLE renewal_simulated_targets ADD COLUMN package_id INT UNSIGNED NULL;
