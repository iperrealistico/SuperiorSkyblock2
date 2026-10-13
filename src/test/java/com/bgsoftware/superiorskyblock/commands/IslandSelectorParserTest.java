package com.bgsoftware.superiorskyblock.commands;

import org.junit.Test;
import java.util.Locale;
import java.util.function.Function;
import static org.junit.Assert.*;

public class IslandSelectorParserTest {
    private static final Function<String, String> PLAYERS = word -> {
        String lower = word.toLowerCase(Locale.ENGLISH);
        if ("lc".equals(lower) || "localchat".equals(lower)) return "localchat";
        if ("tc".equals(lower) || "teamchat".equals(lower) || "customchat".equals(lower)) return "teamchat";
        return lower;
    };
    private static final Function<String, String> ADMINISTRATORS = word -> word.toLowerCase(Locale.ENGLISH);

    @Test public void structuralSelectorsWorkBeforeAndAfterTheCommandAlias() {
        assertParsed(new String[]{"--island", "7", "home"}, new String[]{"home"}, "7");
        assertParsed(new String[]{"home", "--ISLAND", "uuid:abc"}, new String[]{"home"}, "uuid:abc");
        assertParsed(new String[]{"invite", "Nick", "--island", "name:Garden"}, new String[]{"invite", "Nick"}, "name:Garden");
    }

    @Test public void chatAliasesKeepSelectorTextAfterTheFirstPayloadWord() {
        for (String alias : new String[]{"teamchat", "tc", "localchat", "lc", "customchat"}) {
            String[] input = {alias, "hello", "--island", "literal", "--", "tail"};
            assertParsed(input, input, null);
            assertParsed(new String[]{alias, "--island", "7", "hello", "--island", "literal"},
                    new String[]{alias, "hello", "--island", "literal"}, "7");
        }
    }

    @Test public void messagePayloadStartsAfterItsPlayerOrWildcardTarget() {
        for (String alias : new String[]{"msg", "msgall"}) {
            String[] input = {"admin", alias, "Nick", "hello", "--island", "literal"};
            assertParsed(input, input, null);
            assertParsed(new String[]{"admin", alias, "Nick", "--island", "7", "hello", "--island", "literal"}, input, "7");
        }
    }

    @Test public void cmdallTreatsTheNestedCommandAsOpaque() {
        String[] input = {"admin", "cmdall", "Nick", "true", "minecraft:say", "--island", "literal"};
        assertParsed(input, input, null);
        assertParsed(new String[]{"admin", "cmdall", "--island", "7", "Nick", "true", "minecraft:say", "--island", "literal"}, input, "7");
    }

    @Test public void titlePayloadStartsAfterTheThreeTimingArguments() {
        for (String alias : new String[]{"title", "titleall"}) {
            String[] input = {"admin", alias, "Nick", "10", "20", "10", "title:Hello", "--island", "literal"};
            assertParsed(input, input, null);
            assertParsed(new String[]{"admin", alias, "Nick", "10", "20", "10", "--island", "7", "title:Hello", "--island", "literal"}, input, "7");
        }
    }

    @Test public void persistentDataSetPreservesTheValuePayload() {
        String[] input = {"admin", "data", "set", "island", "Nick", "path", "value", "--island", "literal"};
        assertParsed(input, input, null);
        assertParsed(new String[]{"admin", "data", "set", "island", "Nick", "path", "--island", "7", "value", "--island", "literal"}, input, "7");
        assertParsed(new String[]{"admin", "data", "get", "island", "Nick", "path", "--island", "7"},
                new String[]{"admin", "data", "get", "island", "Nick", "path"}, "7");
    }

    @Test public void escapeProtectsALiteralSelectorAtTheBeginningOfPayload() {
        assertParsed(new String[]{"tc", "--", "--island", "literal"}, new String[]{"tc", "--island", "literal"}, null);
        assertParsed(new String[]{"tc", "--island", "7", "--", "--island", "literal"},
                new String[]{"tc", "--island", "literal"}, "7");
    }

    @Test public void disabledSelectorsAndEscapeTokensRemainUntouched() {
        String[] input = {"tc", "--island", "7", "--", "--island"};
        IslandSelectorParser.Result result = IslandSelectorParser.parse(input, false, PLAYERS, ADMINISTRATORS, false);
        assertArrayEquals(input, result.arguments); assertNull(result.selector); assertFalse(result.completingSelector);
    }

    @Test(expected = IllegalArgumentException.class) public void duplicateStructuralSelectorsAreRejected() {
        parse(new String[]{"home", "--island", "7", "--island", "8"}, false);
    }

    @Test(expected = IllegalArgumentException.class) public void missingStructuralSelectorIsRejectedOnExecution() {
        parse(new String[]{"home", "--island"}, false);
    }

    @Test public void incompleteAndPartialSelectorCompletionKeepTheCommandArguments() {
        IslandSelectorParser.Result missing = parse(new String[]{"warp", "--island"}, true);
        assertArrayEquals(new String[]{"warp"}, missing.arguments); assertEquals("", missing.selector); assertTrue(missing.completingSelector);
        IslandSelectorParser.Result partial = parse(new String[]{"warp", "--island", "12"}, true);
        assertArrayEquals(new String[]{"warp"}, partial.arguments); assertEquals("12", partial.selector); assertTrue(partial.completingSelector);
        IslandSelectorParser.Result next = parse(new String[]{"warp", "--island", "12", "s"}, true);
        assertArrayEquals(new String[]{"warp", "s"}, next.arguments); assertEquals("12", next.selector); assertFalse(next.completingSelector);
    }

    @Test public void completionInsidePayloadDoesNotOfferIslandIdentifiers() {
        String[] input = {"tc", "hello", "--island", "12"};
        IslandSelectorParser.Result result = parse(input, true);
        assertArrayEquals(input, result.arguments); assertNull(result.selector); assertFalse(result.completingSelector);
    }

    @Test public void discordAndMultiwordWarpNamesCannotRedirectToAnotherIsland() {
        for (String alias : new String[]{"setdiscord", "delwarp"}) {
            String[] payload = {alias, "base", "--island", "id:2"};
            assertParsed(payload, payload, null);
            assertParsed(new String[]{alias, "--island", "id:1", "base", "--island", "id:2"}, payload, "id:1");
            IslandSelectorParser.Result completed = parse(payload, true);
            assertFalse(completed.completingSelector); assertNull(completed.selector);
        }
        String[] payload = {"admin", "delwarp", "id:1", "base", "--island", "id:2"};
        assertParsed(payload, payload, null);
        assertParsed(new String[]{"admin", "delwarp", "id:1", "--island", "id:3", "base", "--island", "id:2"}, payload, "id:3");
    }

    private static IslandSelectorParser.Result parse(String[] input, boolean completion) {
        return IslandSelectorParser.parse(input, true, PLAYERS, ADMINISTRATORS, completion);
    }
    private static void assertParsed(String[] input, String[] expected, String selector) {
        IslandSelectorParser.Result result = parse(input, false);
        assertArrayEquals(expected, result.arguments); assertEquals(selector, result.selector); assertFalse(result.completingSelector);
    }
}
