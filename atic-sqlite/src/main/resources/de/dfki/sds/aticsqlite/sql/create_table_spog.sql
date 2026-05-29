
CREATE TABLE IF NOT EXISTS spog (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    s INTEGER NOT NULL,
    p INTEGER NOT NULL,
    o INTEGER NOT NULL,
    g INTEGER NOT NULL,

    creator INTEGER NOT NULL,
    created_at INTEGER NOT NULL DEFAULT (unixepoch()),

    confidence REAL NOT NULL DEFAULT 1
        CHECK (confidence >= 0 AND confidence <= 1),

    FOREIGN KEY (s) REFERENCES resource(id),
    FOREIGN KEY (p) REFERENCES property(id),
    FOREIGN KEY (o) REFERENCES resource(id),
    FOREIGN KEY (g) REFERENCES graph(id),
    FOREIGN KEY (creator) REFERENCES user(id),

    UNIQUE (s, p, o, g)
);
