package com.bgsoftware.superiorskyblock.platform.folia.nms.v1_21_11;

import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Node;

import java.lang.reflect.Field;

/** Initializes vanilla multipart/navigation geometry on the actual arena owner. */
public final class RegionalDragonGeometry {
    private static final Field NODES = field("nodes");
    private static final Field ADJACENCY = field("nodeAdjacency");
    private static final int[] VANILLA_ADJACENCY = {6146, 8197, 8202, 16404, 32808, 32848, 65696, 131392,
            131712, 263424, 526848, 525313, 1581057, 3166214, 2138120, 6373424, 4358208, 12910976,
            9044480, 9706496, 15216640, 13688832, 11763712, 8257536};
    private RegionalDragonGeometry() { }

    public static void initializeParts(Object value) {
        EnderDragon dragon = owned(value);
        EnderDragonPart[] parts = dragon.getSubEntities();
        if (parts.length != 8) throw new IllegalStateException("Unknown native dragon multipart geometry");
        // A new or NBT-loaded dragon's head/neck still have origin-zero AABBs.
        // Native aiStep queries them before positioning them for the first time.
        dragon.flightHistory.record(dragon.getY(), dragon.getYRot());
        float rotation = dragon.getYRot() * Mth.DEG_TO_RAD;
        float sin = Mth.sin(rotation), cos = Mth.cos(rotation);
        float headRotation = rotation - dragon.yRotA * 0.01F;
        float headSin = Mth.sin(headRotation), headCos = Mth.cos(headRotation);
        float headY = dragon.getPhaseManager().getCurrentPhase().isSitting() ? -1.0F : 0.0F;
        position(dragon, parts[0], headSin * 6.5F, headY, -headCos * 6.5F);
        position(dragon, parts[1], headSin * 5.5F, headY, -headCos * 5.5F);
        position(dragon, parts[2], sin * 0.5F, 0.0, -cos * 0.5F);
        for (int i = 0; i < 3; i++) {
            float distance = 1.5F + (i + 1) * 2.0F;
            position(dragon, parts[3 + i], -sin * distance, 1.5, cos * distance);
        }
        position(dragon, parts[6], cos * 4.5F, 2.0, sin * 4.5F);
        position(dragon, parts[7], -cos * 4.5F, 2.0, -sin * 4.5F);
    }

    public static void initializeNodes(Object value, int centerX, int centerY, int centerZ) {
        EnderDragon dragon = owned(value);
        ServerLevel world = (ServerLevel) dragon.level();
        try {
            Node[] nodes = (Node[]) NODES.get(dragon);
            int[] adjacency = (int[]) ADJACENCY.get(dragon);
            if (nodes.length != 24 || adjacency.length != 24)
                throw new IllegalStateException("Unknown native dragon navigation geometry");
            for (int i = 0; i < 24; i++) {
                int multiplier = i;
                float radius, divisor;
                int adjustment = 5;
                if (i < 12) { radius = 60.0F; divisor = (float) (Math.PI / 12); }
                else if (i < 20) { multiplier -= 12; radius = 40.0F; divisor = (float) (Math.PI / 8); adjustment += 10; }
                else { multiplier -= 20; radius = 20.0F; divisor = (float) (Math.PI / 4); }
                float angle = 2.0F * (-Mth.PI + divisor * multiplier);
                int x = centerX + Mth.floor(radius * Mth.cos(angle));
                int z = centerZ + Mth.floor(radius * Mth.sin(angle));
                int y = Math.max(centerY + 9, world.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        new BlockPos(x, 0, z)).getY() + adjustment);
                nodes[i] = new Node(x, y, z);
            }
            System.arraycopy(VANILLA_ADJACENCY, 0, adjacency, 0, 24);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot initialize native regional dragon navigation", failure);
        }
    }

    private static EnderDragon owned(Object value) {
        EnderDragon dragon = (EnderDragon) value;
        if (!BukkitExecutor.owns(dragon.getBukkitEntity()))
            throw new IllegalStateException("Dragon geometry initialization requires its entity owner");
        return dragon;
    }
    private static void position(EnderDragon dragon, EnderDragonPart part, double x, double y, double z) {
        part.setPos(dragon.getX() + x, dragon.getY() + y, dragon.getZ() + z);
        part.setOldPosAndRot();
    }
    private static Field field(String name) {
        try { Field result = EnderDragon.class.getDeclaredField(name); result.setAccessible(true); return result; }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
