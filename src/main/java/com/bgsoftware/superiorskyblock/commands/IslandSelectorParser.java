package com.bgsoftware.superiorskyblock.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Selectors precede payload text; after its first word, nested commands are opaque. */
public final class IslandSelectorParser {
    private IslandSelectorParser() { }
    public static Result parse(String[] input, boolean enabled, Function<String, String> players,
                               Function<String, String> administrators, boolean completion) {
        List<String> structural = new ArrayList<>();
        String selector = null;
        boolean payload = false;
        for (int i = 0; i < input.length; i++) {
            String word = input[i];
            if (enabled && !payload && "--".equals(word)) { payload = true; continue; }
            if (enabled && !payload && "--island".equalsIgnoreCase(word)) {
                if (selector != null) throw new IllegalArgumentException("Duplicate selector");
                if (++i >= input.length) {
                    if (completion) return new Result(structural, "", true);
                    throw new IllegalArgumentException("Missing selector");
                }
                selector = input[i];
                if (completion && i == input.length - 1) return new Result(structural, selector, true);
                continue;
            }
            if (structural.size() >= payloadStart(structural, players, administrators)) payload = true;
            structural.add(word);
        }
        return new Result(structural, selector, false);
    }
    private static int payloadStart(List<String> words, Function<String, String> players,
                                    Function<String, String> administrators) {
        if (words.isEmpty()) return Integer.MAX_VALUE;
        String family = players.apply(words.get(0));
        if ("localchat".equals(family) || "teamchat".equals(family) ||
                "setdiscord".equals(family) || "delwarp".equals(family)) return 1;
        if (!"admin".equals(family) || words.size() < 2) return Integer.MAX_VALUE;
        String command = administrators.apply(words.get(1));
        if ("msg".equals(command) || "msgall".equals(command) || "delwarp".equals(command)) return 3;
        if ("cmdall".equals(command)) return 4;
        if ("title".equals(command) || "titleall".equals(command)) return 6;
        if ("data".equals(command) && words.size() > 2 && "set".equalsIgnoreCase(words.get(2))) return 6;
        return Integer.MAX_VALUE;
    }
    public static final class Result {
        public final String[] arguments;
        public final String selector;
        public final boolean completingSelector;
        Result(List<String> arguments, String selector, boolean completingSelector) {
            this.arguments = arguments.toArray(new String[0]); this.selector = selector; this.completingSelector = completingSelector;
        }
    }
}
