
CREATE TABLE IF NOT EXISTS resource (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    unique_key TEXT UNIQUE NOT NULL,

    creator INTEGER NOT NULL,
    created_at INTEGER NOT NULL DEFAULT (unixepoch()),

    FOREIGN KEY (creator) REFERENCES user(id)
);
