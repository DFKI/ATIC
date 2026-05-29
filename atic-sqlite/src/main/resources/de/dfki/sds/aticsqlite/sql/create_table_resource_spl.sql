
CREATE TABLE IF NOT EXISTS resource_spl (
    id INTEGER PRIMARY KEY,

    s INTEGER NOT NULL,
    p INTEGER NOT NULL,

    lex TEXT NOT NULL,
    lang TEXT,
    dt TEXT,

    FOREIGN KEY (s) REFERENCES resource(id),
    FOREIGN KEY (p) REFERENCES property(id),

    UNIQUE (s, p, lex, lang, dt)
);
