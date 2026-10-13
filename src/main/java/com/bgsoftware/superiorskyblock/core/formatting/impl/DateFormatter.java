package com.bgsoftware.superiorskyblock.core.formatting.impl;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.core.formatting.IFormatter;

import java.text.SimpleDateFormat;
import java.util.Date;

public class DateFormatter implements IFormatter<Date> {

    private static final DateFormatter INSTANCE = new DateFormatter();

    private static volatile SimpleDateFormat dateFormatter;

    public static void setDateFormatter(SuperiorSkyblockPlugin plugin, String dateFormat) {
        java.util.Locale locale = java.util.Locale.getDefault();
        if (com.bgsoftware.superiorskyblock.player.PlayerLocales.isDefaultLanguageForced()) {
            java.util.Locale configured = com.bgsoftware.superiorskyblock.player.PlayerLocales.getDefaultLocale();
            locale = configured == null ? java.util.Locale.US : configured;
        }
        dateFormatter = new SimpleDateFormat(dateFormat, locale);
        try {
            for (Island island : plugin.getGrid().getIslands()) {
                island.updateDatesFormatter();
            }
        } catch (Exception ignored) {
        }
    }

    public static DateFormatter getInstance() {
        return INSTANCE;
    }

    private DateFormatter() {

    }

    @Override
    public String format(Date value) {
        // SimpleDateFormat mutates its calendar while formatting; regions must not share that state.
        return ((SimpleDateFormat) dateFormatter.clone()).format(value);
    }

}
