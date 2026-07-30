
CREATE TABLE IF NOT EXISTS system_splu (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    s INTEGER NOT NULL,
    p INTEGER NOT NULL,
    lex TEXT NOT NULL,
    lang TEXT,
    dt TEXT,
    user_primary_group INTEGER NOT NULL, -- u

    creator INTEGER NOT NULL,
    created_at INTEGER NOT NULL DEFAULT (unixepoch()),

    FOREIGN KEY (s) REFERENCES resource(id),
    FOREIGN KEY (p) REFERENCES property(id),
    FOREIGN KEY (creator) REFERENCES user(id),
    FOREIGN KEY (user_primary_group) REFERENCES "group"(id),

    UNIQUE (s, p, lex, lang, dt, user_primary_group)
);
