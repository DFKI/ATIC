
CREATE TABLE IF NOT EXISTS resource_acl_effective (
    resource_id INTEGER NOT NULL,
    user_id      INTEGER NOT NULL,
    permission   INTEGER NOT NULL,

    PRIMARY KEY(resource_id, user_id)
) WITHOUT ROWID;
