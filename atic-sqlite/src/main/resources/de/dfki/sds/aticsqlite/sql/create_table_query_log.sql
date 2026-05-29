
CREATE TABLE IF NOT EXISTS query_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    query_id INTEGER NOT NULL,
    params TEXT NOT NULL,
    user_id INTEGER,
    start_time INTEGER NOT NULL,
    end_time INTEGER NOT NULL,
    duration INTEGER NOT NULL,
    scope TEXT,
    num_results INTEGER,
    FOREIGN KEY (query_id) REFERENCES query(id) ON DELETE CASCADE
    -- FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
);
