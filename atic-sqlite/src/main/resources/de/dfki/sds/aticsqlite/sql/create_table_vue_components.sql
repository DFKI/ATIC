CREATE TABLE IF NOT EXISTS vue_components (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    path TEXT NOT NULL,
    name TEXT NOT NULL,

    bridges JSON NOT NULL CHECK (json_valid(bridges)),

    script TEXT,
    template TEXT,
    style TEXT,

    creator INTEGER NOT NULL,
    created_at INTEGER NOT NULL DEFAULT (unixepoch()),
    
    UNIQUE (path, name),
    
    FOREIGN KEY (creator) REFERENCES user(id)
);
