package com.bgsoftware.superiorskyblock.commands.player;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.warps.IslandWarp;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.commands.arguments.CommandArguments;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.*;

public class CmdWarpTargetsTest {
    @Test public void singletonKeepsCategoriesAndNamedWarpShorthand() {
        Island own = island(true, "home", false);
        CmdWarp.WarpRequest categories = CmdWarp.resolveRequest(new String[]{"warp"}, null, own, selector -> null);
        assertSame(own, categories.island); assertNull(categories.warpName);
        CmdWarp.WarpRequest home = CmdWarp.resolveRequest(new String[]{"warp", "home"}, null, own, selector -> null);
        assertSame(own, home.island); assertEquals("home", home.warpName);
    }

    @Test public void explicitExternalIslandWorksWithOrWithoutAnotherSelectedIsland() {
        Island external = island(false, "shop", false), own = island(true, "shop", false);
        for (Island senderIsland : Arrays.asList(null, own)) {
            AtomicInteger fallbackCalls = new AtomicInteger();
            Function<String, Island> resolver = selector -> { fallbackCalls.incrementAndGet(); return own; };
            CmdWarp.WarpRequest request = CmdWarp.resolveRequest(new String[]{"warp", "shop"}, external, senderIsland, resolver);
            assertSame(external, request.island); assertEquals("shop", request.warpName);
            CmdWarp.WarpRequest categories = CmdWarp.resolveRequest(new String[]{"warp"}, external, senderIsland, resolver);
            assertSame(external, categories.island); assertNull(categories.warpName);
            assertEquals(0, fallbackCalls.get());
        }
    }

    @Test public void explicitMissingWarpIsNeverReinterpretedAsAnotherIsland() {
        Island external = island(false, "shop", false), other = island(true, "missing", false);
        AtomicInteger fallbackCalls = new AtomicInteger();
        CmdWarp.WarpRequest request = CmdWarp.resolveRequest(new String[]{"warp", "missing"}, external, other,
                selector -> { fallbackCalls.incrementAndGet(); return other; });
        assertSame(external, request.island); assertEquals("missing", request.warpName);
        assertNull(request.island.getWarp(request.warpName)); assertEquals(0, fallbackCalls.get());
    }

    @Test public void positionalIslandAndWarpRemainIndependentOfSelection() {
        Island external = island(false, "shop", false), selected = island(true, "shop", false);
        for (String selector : new String[]{"42", "uuid:8c81bc82-f8d2-4c04-80ab-84503d7bbff4", "name:Market"}) {
            CmdWarp.WarpRequest request = CmdWarp.resolveRequest(new String[]{"warp", selector, "shop"}, null, selected,
                    argument -> selector.equals(argument) ? external : null);
            assertSame(external, request.island); assertEquals("shop", request.warpName);
            CmdWarp.WarpRequest categories = CmdWarp.resolveRequest(new String[]{"warp", selector}, null, null,
                    argument -> selector.equals(argument) ? external : null);
            assertSame(external, categories.island); assertNull(categories.warpName);
        }
    }

    @Test public void legacyWarpNameWinsOverAnIdenticalIslandName() {
        Island selected = island(true, "Market", false), external = island(false, "shop", false);
        CmdWarp.WarpRequest request = CmdWarp.resolveRequest(new String[]{"warp", "Market"}, null, selected, selector -> external);
        assertSame(selected, request.island); assertEquals("Market", request.warpName);
        CmdWarp.WarpRequest categories = CmdWarp.resolveRequest(new String[]{"warp", "Different"}, null, selected, selector -> external);
        assertSame(external, categories.island); assertNull(categories.warpName);
    }

    @Test public void completionSharesOwnedFirstNicknamePolicyWithCommandResolution() {
        Island first = island(false, "shop", false), second = island(false, "other", false);
        Map<String, SuperiorPlayer> players = new LinkedHashMap<>();
        players.put("Single", player(Collections.singletonList(first), Collections.singletonList(first)));
        players.put("Multiple", player(Arrays.asList(first, second), Arrays.asList(first, second)));
        players.put("OwnerAndTeam", player(Arrays.asList(first, second), Collections.singletonList(first)));
        players.put("TeamOnly", player(Collections.singletonList(second), Collections.emptyList()));
        players.put("SeveralTeams", player(Arrays.asList(first, second), Collections.emptyList()));
        assertSame(first, CommandArguments.resolvePlayerIsland(players.get("Single"), true));
        assertSame(first, CommandArguments.resolvePlayerIsland(players.get("OwnerAndTeam"), true));
        assertSame(second, CommandArguments.resolvePlayerIsland(players.get("TeamOnly"), true));
        assertNull(CommandArguments.resolvePlayerIsland(players.get("Multiple"), true));
        assertNull(CommandArguments.resolvePlayerIsland(players.get("SeveralTeams"), true));
        assertSame(first, CommandArguments.resolvePlayerIsland(players.get("Multiple"), false));
    }

    @Test public void completionKeepsPrivateWarpsHiddenAndFiltersTheTypedPrefix() {
        SuperiorPlayer viewer = player(Collections.emptyList());
        Map<String, IslandWarp> warps = new LinkedHashMap<>();
        warps.put("shop", warp(false)); warps.put("secret", warp(true)); warps.put("home", warp(false));
        assertEquals(Collections.singletonList("shop"), CmdWarp.completeWarps(island(false, warps), viewer, "s"));
        assertEquals(Arrays.asList("shop", "secret"), CmdWarp.completeWarps(island(true, warps), viewer, "s"));
        assertTrue(CmdWarp.completeWarps(null, viewer, "").isEmpty());
    }

    private static Island island(boolean member, String name, boolean privateWarp) {
        return island(member, Collections.singletonMap(name, warp(privateWarp)));
    }
    private static Island island(boolean member, Map<String, IslandWarp> warps) {
        return (Island) Proxy.newProxyInstance(Island.class.getClassLoader(), new Class<?>[]{Island.class}, (proxy, method, args) -> {
            if ("isMember".equals(method.getName())) return member;
            if ("getWarp".equals(method.getName())) return warps.get(args[0]);
            if ("getIslandWarps".equals(method.getName())) return warps;
            if ("equals".equals(method.getName())) return proxy == args[0];
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            return null;
        });
    }
    private static IslandWarp warp(boolean privateFlag) {
        return (IslandWarp) Proxy.newProxyInstance(IslandWarp.class.getClassLoader(), new Class<?>[]{IslandWarp.class},
                (proxy, method, args) -> "hasPrivateFlag".equals(method.getName()) ? privateFlag : null);
    }
    private static SuperiorPlayer player(List<Island> memberships) {
        return player(memberships, memberships);
    }
    private static SuperiorPlayer player(List<Island> memberships, List<Island> owned) {
        return (SuperiorPlayer) Proxy.newProxyInstance(SuperiorPlayer.class.getClassLoader(), new Class<?>[]{SuperiorPlayer.class},
                (proxy, method, args) -> "getIslands".equals(method.getName()) ? memberships :
                        "getOwnedIslands".equals(method.getName()) ? owned : null);
    }
}
