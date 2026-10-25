ALTER TABLE app_brand_builds
 ADD COLUMN progress_phase VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN progress_started_at DATETIME(6) NULL,
 ADD COLUMN progress_updated_at DATETIME(6) NULL,
 ADD COLUMN progress_times JSON NULL,
 ADD KEY brand_build_progress_samples(state,build_channel,published_at,id);
