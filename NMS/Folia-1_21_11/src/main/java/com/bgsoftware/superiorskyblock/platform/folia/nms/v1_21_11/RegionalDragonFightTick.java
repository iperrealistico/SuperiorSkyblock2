package com.bgsoftware.superiorskyblock.platform.folia.nms.v1_21_11;

import net.minecraft.world.level.dimension.end.EndDragonFight;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Vanilla fight progression without its global player scan or origin-zero tickets. */
public final class RegionalDragonFightTick {
    private static final Field KILLED = field("dragonKilled");
    private static final Field RESPAWN_TIME = field("respawnTime");
    private static final Field CRYSTAL_TICKS = field("ticksSinceCrystalsScanned");
    private static final Method CRYSTAL_SCAN = method("updateCrystalCount");

    private RegionalDragonFightTick() { }

    public static void tick(Object object) {
        EndDragonFight fight = (EndDragonFight) object;
        try {
            fight.dragonEvent.setVisible(!KILLED.getBoolean(fight));
            if (fight.dragonEvent.getPlayers().isEmpty())
                return;
            if (fight.respawnStage != null) {
                if (fight.respawnCrystals == null) {
                    fight.respawnStage = null;
                    fight.tryRespawn();
                } else {
                    int time = RESPAWN_TIME.getInt(fight);
                    RESPAWN_TIME.setInt(fight, time + 1);
                    fight.respawnStage.tick(fight.level, fight, fight.respawnCrystals, time, fight.portalLocation);
                }
            }
            if (!KILLED.getBoolean(fight)) {
                int ticks = CRYSTAL_TICKS.getInt(fight) + 1;
                CRYSTAL_TICKS.setInt(fight, ticks);
                if (ticks >= 100)
                    CRYSTAL_SCAN.invoke(fight);
            }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not advance regional dragon fight", error);
        }
    }

    private static Field field(String name) {
        try { Field field = EndDragonFight.class.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }

    private static Method method(String name) {
        try { Method method = EndDragonFight.class.getDeclaredMethod(name); method.setAccessible(true); return method; }
        catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }
}
