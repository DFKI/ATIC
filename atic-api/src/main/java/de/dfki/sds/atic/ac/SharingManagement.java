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
     * @param graphUris the URIs of graphs to share
     * @param groupUris the URIs of groups to share with
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

    /*
    I think we should do non‑cascading unshare behavior:
        - Revoking the parent share does not rollback child shares
        - Maintains autonomy of delegation
     */
    /**
     * Remove (unshare) one or more graphs from the specified group principals entirely — all permissions are revoked.
     *
     * @param graphUris the URIs of the graphs to unshare
     * @param groupUris the URIs of the groups whose access is to be removed
     * @param ctx the caller's invocation context
     * @throws PermissionDeniedException if the caller is not allowed to revoke
     */
    void unshareGraphs(
            Set<String> graphUris,
            Set<String> groupUris,
            InvocationContext ctx
    );

    public void shareResources(
            Set<String> resourceUris,
            Set<String> groupUris,
            Permission permission,
            InvocationContext ctx
    );

    void unshareResources(
            Set<String> resourceUris,
            Set<String> groupUris,
            InvocationContext ctx
    );
}
