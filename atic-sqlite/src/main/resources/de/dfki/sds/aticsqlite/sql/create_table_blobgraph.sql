CREATE TABLE IF NOT EXISTS blobgraph (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    filepath TEXT,
    uri TEXT,
    sha256 TEXT,
    mimeType TEXT,
    creator INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL DEFAULT (unixepoch()),
    UNIQUE (filepath)
);