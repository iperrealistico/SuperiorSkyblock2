package com.bgsoftware.superiorskyblock.commands.player;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.PlayerRole;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class CmdTeamOrderingTest {
    @Test public void asyncRosterOrdersByItsIslandEvenWhenMembersSelectDifferentIslands() {
        Island listed = island(), another = island();
        SuperiorPlayer leader = player("Leader", listed, 0, 80), member = player("Member", listed, 20, -10);
        List<SuperiorPlayer> roster = new ArrayList<>(Arrays.asList(member, leader));
        // No lexical command scope exists on the asynchronous renderer.
        roster.sort(CmdTeam.teamComparator(listed));
        assertEquals(Arrays.asList(leader, member), roster);
        roster.sort(CmdTeam.teamComparator(another));
        assertEquals(Arrays.asList(member, leader), roster);
    }

    @Test public void roleTiesRetainTheOriginalReverseNameOrdering() {
        Island listed = island();
        SuperiorPlayer alpha = player("Alpha", listed, 20, 0), zulu = player("Zulu", listed, 20, 0);
        List<SuperiorPlayer> roster = new ArrayList<>(Arrays.asList(alpha, zulu));
        roster.sort(CmdTeam.teamComparator(listed));
        assertEquals(Arrays.asList(zulu, alpha), roster);
    }

    private static Island island() {
        return (Island) Proxy.newProxyInstance(Island.class.getClassLoader(), new Class<?>[]{Island.class},
                (proxy, method, args) -> null);
    }
    private static SuperiorPlayer player(String name, Island listed, int listedWeight, int selectedWeight) {
        PlayerRole listedRole = role(listedWeight), selectedRole = role(selectedWeight);
        return (SuperiorPlayer) Proxy.newProxyInstance(SuperiorPlayer.class.getClassLoader(), new Class<?>[]{SuperiorPlayer.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) return name;
                    if ("getPlayerRole".equals(method.getName())) {
                        if (args == null || args.length == 0) throw new AssertionError("The roster consulted an implicit island role");
                        return args[0] == listed ? listedRole : selectedRole;
                    }
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    return null;
                });
    }
    private static PlayerRole role(int weight) {
        return (PlayerRole) Proxy.newProxyInstance(PlayerRole.class.getClassLoader(), new Class<?>[]{PlayerRole.class},
                (proxy, method, args) -> "getWeight".equals(method.getName()) ? weight : null);
    }
}
