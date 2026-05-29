
CREATE TABLE IF NOT EXISTS resource_acl (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    resource_id INTEGER NOT NULL,
    group_id INTEGER NOT NULL,
    permission INTEGER NOT NULL, -- 1=read, 2=edit, 3=admin
    granted_by_group_id INTEGER NOT NULL,

    UNIQUE(resource_id, group_id, permission, granted_by_group_id),

    FOREIGN KEY(resource_id) REFERENCES resource(id) ON DELETE CASCADE,
    FOREIGN KEY(group_id) REFERENCES "group"(id) ON DELETE CASCADE,
    FOREIGN KEY(granted_by_group_id) REFERENCES "group"(id)
);
