CREATE TABLE verified_line_usernames (
 account_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 username VARCHAR(256) NOT NULL,
 verified_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (account_ref) REFERENCES managed_accounts(account_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
