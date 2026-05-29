
CREATE TABLE IF NOT EXISTS graph (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uri TEXT UNIQUE NOT NULL,

    creator INTEGER NOT NULL,
    created_at INTEGER NOT NULL DEFAULT (unixepoch()),

    is_virtual BOOLEAN NOT NULL DEFAULT 0,
    virtual_factory TEXT,
    virtual_config TEXT,

    FOREIGN KEY (creator) REFERENCES user(id)
);
