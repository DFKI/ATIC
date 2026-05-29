
CREATE TABLE IF NOT EXISTS user_group_assignment (
    user_id  INTEGER NOT NULL,
    group_id INTEGER NOT NULL,

    PRIMARY KEY (user_id, group_id),

    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE,
    FOREIGN KEY (group_id) REFERENCES "group"(id) ON DELETE CASCADE
);
