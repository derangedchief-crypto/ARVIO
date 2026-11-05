CREATE TABLE my_hub_owner_workspaces (
 token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 workspace_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 panel_id VARCHAR(32) NOT NULL,
 FOREIGN KEY (token_hash) REFERENCES my_hub_sessions(token_hash) ON DELETE CASCADE,
 FOREIGN KEY (workspace_id) REFERENCES tenants(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
