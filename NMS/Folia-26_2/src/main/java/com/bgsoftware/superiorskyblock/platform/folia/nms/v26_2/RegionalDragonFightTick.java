package com.bgsoftware.superiorskyblock.platform.folia.nms.v26_2;

import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.dimension.end.EnderDragonFight;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/** Vanilla fight progression without its global player scan or origin-zero tickets. */
public final class RegionalDragonFightTick {
    private static final Field KILLED = field("dragonKilled");
    private static final Field RESPAWN_TIME = field("respawnTime");
    private static final Field CRYSTAL_TICKS = field("ticksSinceCrystalsScanned");
    private static final Method CRYSTAL_SCAN = method("updateCrystalCount");
    private static final Method ABORT_RESPAWN = method("abortRespawnSequence");

    private RegionalDragonFightTick() { }

    public static void tick(Object object) {
        EnderDragonFight fight = (EnderDragonFight) object;
        try {
            fight.dragonEvent.setVisible(!KILLED.getBoolean(fight));
            if (fight.dragonEvent.getPlayers().isEmpty())
                return;
            if (fight.respawnStage != null) {
                List<EndCrystal> crystals = fight.respawnCrystals.stream()
                        .map(reference -> reference.getEntity(fight.level, EndCrystal.class))
                        .filter(Objects::nonNull).toList();
                if (crystals.isEmpty()) {
                    ABORT_RESPAWN.invoke(fight);
                    return;
                }
                int time = RESPAWN_TIME.getInt(fight);
                RESPAWN_TIME.setInt(fight, time + 1);
                fight.respawnStage.tick(fight.level, fight, crystals, time);
                fight.setDirty();
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
        try { Field field = EnderDragonFight.class.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }

    private static Method method(String name) {
        try { Method method = EnderDragonFight.class.getDeclaredMethod(name); method.setAccessible(true); return method; }
        catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }
}
