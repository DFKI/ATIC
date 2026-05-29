
CREATE TABLE IF NOT EXISTS prefixmap (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    prefix TEXT NOT NULL,
    uri TEXT NOT NULL,

    UNIQUE (prefix, uri)
);
