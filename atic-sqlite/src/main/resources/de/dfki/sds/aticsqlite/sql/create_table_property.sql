
CREATE TABLE IF NOT EXISTS property (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uri TEXT UNIQUE NOT NULL,

    -- type:
    -- 0 = undefined
    -- 1 = object is a URI
    -- 2 = object is a literal
    type INTEGER NOT NULL DEFAULT 0 CHECK (type IN (0, 1, 2)),

    creator INTEGER NOT NULL,
    created_at INTEGER NOT NULL DEFAULT (unixepoch()),

    FOREIGN KEY (creator) REFERENCES user(id)
);

