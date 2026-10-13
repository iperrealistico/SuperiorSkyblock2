package com.bgsoftware.superiorskyblock.commands;

/** Caller-local presentation only; never changes command authority or island state. */
public final class CommandPresentation implements AutoCloseable {
    private static final ThreadLocal<Boolean> IDENTIFIERS = new ThreadLocal<>();
    private final Boolean previous;
    private CommandPresentation(boolean visible) { previous = IDENTIFIERS.get(); IDENTIFIERS.set(visible); }
    public static CommandPresentation open(boolean visible) { return new CommandPresentation(visible); }
    public static boolean identifiersVisible() { return !Boolean.FALSE.equals(IDENTIFIERS.get()); }
    @Override public void close() { if (previous == null) IDENTIFIERS.remove(); else IDENTIFIERS.set(previous); }
}
