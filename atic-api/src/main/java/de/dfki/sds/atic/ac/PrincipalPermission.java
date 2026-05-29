

package de.dfki.sds.atic.ac;

/**
 *
 */
public class PrincipalPermission {

private Principal principal;
private Permission permission;

public PrincipalPermission(Principal principal, Permission permission) {
    this.principal = principal;
    this.permission = permission;
}

public Principal getPrincipal() {
    return principal;
}

public Permission getPermission() {
    return permission;
}

}
