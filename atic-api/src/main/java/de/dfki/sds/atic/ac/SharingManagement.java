package de.dfki.sds.atic.ac;

import de.dfki.sds.atic.jenatic.InvocationContext;
import java.util.Set;

/**
 *
 */
public interface SharingManagement {

    /**
     * Share one or more graphs with one or more group principals, granting the specified permission.
     *
     * @param graphUris the URIs of the graphs to share
     * @param groupUris the URIs of the groups to share with
     * @param permission the permission level to grant (READ, EDIT, ADMIN)
     * @param ctx the calling user's invocation context
     * @throws PermissionDeniedException if the caller is not allowed to share
     */
    void shareGraphs(
            Set<String> graphUris,
            Set<String> groupUris,
            Permission permission,
            InvocationContext ctx
    );

    /**
     * Share one or more graphs with one or more group principals, granting the specified permission, and include a message that may be delivered to recipients
     * as part of the sharing operation.
     *
     * @param graphUris the URIs of the graphs to share
     * @param groupUris the URIs of the groups to share with
     * @param permission the permission level to grant (READ, EDIT, ADMIN)
     * @param ctx the calling user's invocation context
     * @param message an optional message to accompany the share operation
     * @throws PermissionDeniedException if the caller is not allowed to share
     */
    void shareGraphs(
            Set<String> graphUris,
            Set<String> groupUris,
            Permission permission,
            String message, String sessionId,
            InvocationContext ctx
    );

    /**
     * Remove (unshare) one or more graphs from the specified group principals entirely; all permissions are revoked.
     * <p>
     * Unsharing is non-cascading: revoking a share granted by a parent principal does not automatically revoke any shares that were subsequently delegated by
     * recipients. Child shares remain intact unless explicitly revoked.
     *
     * @param graphUris the URIs of the graphs to unshare
     * @param groupUris the URIs of the groups whose access is to be removed
     * @param ctx the caller's invocation context
     * @throws PermissionDeniedException if the caller is not allowed to revoke access
     */
    void unshareGraphs(
            Set<String> graphUris,
            Set<String> groupUris,
            InvocationContext ctx
    );

    /**
     * Remove (unshare) one or more graphs from the specified group principals entirely; all permissions are revoked, optionally including a message describing
     * the revocation.
     * <p>
     * Unsharing is non-cascading: revoking a share granted by a parent principal does not automatically revoke any shares that were subsequently delegated by
     * recipients. Child shares remain intact unless explicitly revoked.
     *
     * @param graphUris the URIs of the graphs to unshare
     * @param groupUris the URIs of the groups whose access is to be removed
     * @param ctx the caller's invocation context
     * @param message an optional message to accompany the unshare operation
     * @throws PermissionDeniedException if the caller is not allowed to revoke access
     */
    void unshareGraphs(
            Set<String> graphUris,
            Set<String> groupUris,
            String message, String sessionId,
            InvocationContext ctx
    );

    /**
     * Share one or more resources with one or more group principals, granting the specified permission.
     *
     * @param resourceUris the URIs of the resources to share
     * @param groupUris the URIs of the groups to share with
     * @param permission the permission level to grant (READ, EDIT, ADMIN)
     * @param ctx the calling user's invocation context
     * @throws PermissionDeniedException if the caller is not allowed to share
     */
    void shareResources(
            Set<String> resourceUris,
            Set<String> groupUris,
            Permission permission,
            InvocationContext ctx
    );

    /**
     * Share one or more resources with one or more group principals, granting the specified permission, and include a message that may be delivered to
     * recipients as part of the sharing operation.
     *
     * @param resourceUris the URIs of the resources to share
     * @param groupUris the URIs of the groups to share with
     * @param permission the permission level to grant (READ, EDIT, ADMIN)
     * @param ctx the calling user's invocation context
     * @param message an optional message to accompany the share operation
     * @throws PermissionDeniedException if the caller is not allowed to share
     */
    void shareResources(
            Set<String> resourceUris,
            Set<String> groupUris,
            Permission permission,
            String message, String sessionId,
            InvocationContext ctx
    );

    /**
     * Remove (unshare) one or more resources from the specified group principals entirely; all permissions are revoked.
     * <p>
     * Unsharing is non-cascading: revoking a share granted by a parent principal does not automatically revoke any shares that were subsequently delegated by
     * recipients. Child shares remain intact unless explicitly revoked.
     *
     * @param resourceUris the URIs of the resources to unshare
     * @param groupUris the URIs of the groups whose access is to be removed
     * @param ctx the caller's invocation context
     * @throws PermissionDeniedException if the caller is not allowed to revoke access
     */
    void unshareResources(
            Set<String> resourceUris,
            Set<String> groupUris,
            InvocationContext ctx
    );

    /**
     * Remove (unshare) one or more resources from the specified group principals entirely; all permissions are revoked, optionally including a message
     * describing the revocation.
     * <p>
     * Unsharing is non-cascading: revoking a share granted by a parent principal does not automatically revoke any shares that were subsequently delegated by
     * recipients. Child shares remain intact unless explicitly revoked.
     *
     * @param resourceUris the URIs of the resources to unshare
     * @param groupUris the URIs of the groups whose access is to be removed
     * @param message an optional message to accompany the unshare operation
     * @param ctx the caller's invocation context
     * @throws PermissionDeniedException if the caller is not allowed to revoke access
     */
    void unshareResources(
            Set<String> resourceUris,
            Set<String> groupUris,
            String message, String sessionId,
            InvocationContext ctx
    );
}
