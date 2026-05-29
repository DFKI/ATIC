
CREATE TABLE IF NOT EXISTS resource_spo (
    id INTEGER PRIMARY KEY,

    s INTEGER NOT NULL,
    p INTEGER NOT NULL,
    o INTEGER NOT NULL,

    FOREIGN KEY (s) REFERENCES resource(id),
    FOREIGN KEY (p) REFERENCES property(id),
    FOREIGN KEY (o) REFERENCES resource(id),

    UNIQUE (s, p, o)
);
