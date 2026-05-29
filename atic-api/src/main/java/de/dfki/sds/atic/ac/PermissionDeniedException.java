package de.dfki.sds.atic.ac;

import java.util.Set;

/**
 * A structured exception used when a permission check fails.
 *
 * Contains: - the name of the database table (for context) - the row id (primary key) - the URI (logical identifier) of the entity - required permission -
 * actual permissions the caller has
 */
public class PermissionDeniedException extends RuntimeException {

    private final String tableName;           // e.g. "graph"
    private final Long entityId;              // the DB row id
    private final String uri;                 // the logical URI of the entity
    private final Permission requiredPermission;
    private final Set<Permission> actualPermissions;

    public PermissionDeniedException(
            String tableName,
            Long entityId,
            String uri,
            Permission requiredPermission,
            Set<Permission> actualPermissions) {

        super(buildMessage(tableName, entityId, uri, requiredPermission, actualPermissions));

        this.tableName = tableName;
        this.entityId = entityId;
        this.uri = uri;
        this.requiredPermission = requiredPermission;
        this.actualPermissions = actualPermissions;
    }

    private static String buildMessage(
            String tableName,
            Long entityId,
            String uri,
            Permission required,
            Set<Permission> actual) {

        return String.format(
                "Permission denied on table='%s', id=%s, uri='%s': required=%s, actual=%s",
                tableName,
                entityId,
                uri,
                required,
                actual
        );
    }

    public String getTableName() {
        return tableName;
    }

    public Long getEntityId() {
        return entityId;
    }

    public String getUri() {
        return uri;
    }

    public Permission getRequiredPermission() {
        return requiredPermission;
    }

    public Set<Permission> getActualPermissions() {
        return actualPermissions;
    }
}
