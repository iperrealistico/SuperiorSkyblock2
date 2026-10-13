package com.bgsoftware.superiorskyblock.pvc.entitlement;

import java.util.Map;

/** Pure, bounded numeric permission resolver. No group name or OP status is a grant. */
public final class EntitlementPolicy {
    public static final String OWNERSHIP_PREFIX = "superior.island.ownership.limit.";
    public static final String UNLIMITED = OWNERSHIP_PREFIX + "unlimited";
    public static final String RADIUS_PREFIX = "superior.island.size.radius.";

    private EntitlementPolicy() { }

    public static Values resolve(Map<String, Boolean> permissions, int defaultRadius, int gridCap) {
        int limit = 1;
        int radius = Math.max(1, Math.min(defaultRadius, gridCap));
        boolean radiusGranted = false;
        for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
            if (!Boolean.TRUE.equals(entry.getValue())) continue;
            String node = entry.getKey();
            if (node.startsWith(OWNERSHIP_PREFIX)) {
                Integer value = positiveSuffix(node, OWNERSHIP_PREFIX);
                if (value != null) limit = Math.max(limit, value);
            } else if (node.startsWith(RADIUS_PREFIX)) {
                Integer value = positiveSuffix(node, RADIUS_PREFIX);
                if (value != null) {
                    int capped = Math.min(value, gridCap);
                    radius = radiusGranted ? Math.max(radius, capped) : capped;
                    radiusGranted = true;
                }
            }
        }
        if (Boolean.TRUE.equals(permissions.get(UNLIMITED))) limit = Integer.MAX_VALUE;
        return new Values(limit, radius);
    }

    public static Integer positiveSuffix(String node, String prefix) {
        if (!node.startsWith(prefix)) return null;
        String suffix = node.substring(prefix.length());
        if (suffix.isEmpty() || suffix.length() > 10 || (suffix.length() > 1 && suffix.charAt(0) == '0')) return null;
        long value = 0;
        for (int i = 0; i < suffix.length(); ++i) {
            char c = suffix.charAt(i);
            if (c < '0' || c > '9') return null;
            value = value * 10 + c - '0';
            if (value > Integer.MAX_VALUE) return null;
        }
        return value > 0 ? (int) value : null;
    }

    public static final class Values {
        private final int ownershipLimit;
        private final int radius;
        public Values(int ownershipLimit, int radius) {
            this.ownershipLimit = ownershipLimit;
            this.radius = radius;
        }
        public int getOwnershipLimit() { return ownershipLimit; }
        public int getRadius() { return radius; }
    }
}
