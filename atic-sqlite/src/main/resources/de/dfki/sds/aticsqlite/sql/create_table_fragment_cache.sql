CREATE TABLE IF NOT EXISTS fragment_cache (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    lang TEXT,
    group_id INTEGER,
    fragment JSON NOT NULL CHECK (json_valid(fragment)),
    UNIQUE (lang, group_id),
    FOREIGN KEY (group_id) REFERENCES "group"(id)
);
