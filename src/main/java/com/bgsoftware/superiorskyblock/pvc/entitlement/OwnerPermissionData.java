package com.bgsoftware.superiorskyblock.pvc.entitlement;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** A permission source must report UNKNOWN instead of turning provider failures into revocations. */
public final class OwnerPermissionData {
    private final Map<String, Boolean> permissions;
    private final boolean known;
    private OwnerPermissionData(Map<String, Boolean> permissions, boolean known) {
        this.permissions = Collections.unmodifiableMap(new HashMap<>(permissions));
        this.known = known;
    }
    public static OwnerPermissionData known(Map<String, Boolean> permissions) {
        return new OwnerPermissionData(permissions, true);
    }
    public static OwnerPermissionData unknown() {
        return new OwnerPermissionData(Collections.<String, Boolean>emptyMap(), false);
    }
    public Map<String, Boolean> getPermissions() { return permissions; }
    public boolean isKnown() { return known; }
}
