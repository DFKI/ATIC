
CREATE TABLE IF NOT EXISTS resource_uri (
    id INTEGER PRIMARY KEY,

    -- either a URI or a blank node label
    uri TEXT UNIQUE NOT NULL,

    -- indicates whether this is a blank node label
    is_blank BOOLEAN NOT NULL DEFAULT 0
);
