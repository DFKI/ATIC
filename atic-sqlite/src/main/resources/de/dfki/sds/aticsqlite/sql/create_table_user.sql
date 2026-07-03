
CREATE TABLE IF NOT EXISTS user (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    uri TEXT NOT NULL UNIQUE,
    password TEXT NOT NULL,
    firstname TEXT,
    lastname TEXT,
    email TEXT,

    is_agent BOOLEAN NOT NULL DEFAULT 0,
    agent_factory TEXT,
    agent_config TEXT
);
