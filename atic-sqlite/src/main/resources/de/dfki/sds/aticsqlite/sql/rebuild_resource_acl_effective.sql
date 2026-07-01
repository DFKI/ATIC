INSERT INTO resource_acl_effective
    (resource_id, user_id, permission)
SELECT
    a.resource_id,
    u.user_id,
    MAX(a.permission)
FROM resource_acl a
JOIN (
    SELECT uga.group_id, uga.user_id
    FROM user_group_assignment uga

    UNION

    SELECT g.id, g.user_id
    FROM "group" g
    WHERE g.user_id IS NOT NULL
) AS u
ON u.group_id = a.group_id
WHERE NOT EXISTS (
    SELECT 1
    FROM resource_acl_effective
)
GROUP BY
    a.resource_id,
    u.user_id
