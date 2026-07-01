CREATE TRIGGER IF NOT EXISTS user_group_assignment_ad
AFTER DELETE ON user_group_assignment
BEGIN

    DELETE FROM resource_acl_effective
    WHERE user_id = OLD.user_id;

    INSERT INTO resource_acl_effective
        (resource_id, user_id, permission)
    SELECT
        a.resource_id,
        OLD.user_id,
        MAX(a.permission)
    FROM resource_acl a
    LEFT JOIN "group" g
           ON g.id = a.group_id
    LEFT JOIN user_group_assignment uga
           ON uga.group_id = a.group_id
          AND uga.user_id = OLD.user_id
    WHERE
            uga.user_id IS NOT NULL
         OR g.user_id = OLD.user_id
    GROUP BY
        a.resource_id;

END
