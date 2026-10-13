package com.bgsoftware.superiorskyblock.pvc;

import java.util.Locale;

/** Selects presentation language without rewriting a player's stored preference. */
public final class PvcPresentationLocale {
    private PvcPresentationLocale() { }

    public static Locale resolve(Locale requested, Locale serverDefault, boolean forceDefault) {
        return forceDefault && serverDefault != null ? serverDefault : requested;
    }
}
