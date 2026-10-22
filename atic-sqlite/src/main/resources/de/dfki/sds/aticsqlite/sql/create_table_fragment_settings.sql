CREATE TABLE IF NOT EXISTS fragment_settings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    context_uri TEXT,
    group_id INTEGER,
    settings JSON NOT NULL CHECK (json_valid(settings)),
    creator INTEGER NOT NULL,
    created_at INTEGER NOT NULL DEFAULT (unixepoch()),
    UNIQUE (context_uri, group_id),
    FOREIGN KEY (creator) REFERENCES user(id)
);
