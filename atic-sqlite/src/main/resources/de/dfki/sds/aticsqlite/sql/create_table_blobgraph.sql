CREATE TABLE IF NOT EXISTS blobgraph (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    filepath TEXT,
    uri TEXT,
    creator INTEGER NOT NULL,
    created_at INTEGER NOT NULL DEFAULT (unixepoch()),
    UNIQUE (filepath),
    FOREIGN KEY (creator) REFERENCES user(id)
);