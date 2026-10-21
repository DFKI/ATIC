CREATE TABLE IF NOT EXISTS fragment_settings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    context_uri TEXT,
    group_id INTEGER,
    settings JSON NOT NULL CHECK (json_valid(settings)),
    UNIQUE (context_uri, group_id),
    FOREIGN KEY (group_id) REFERENCES "group"(id) ON DELETE CASCADE
);
