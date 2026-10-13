package com.bgsoftware.superiorskyblock.pvc;

import org.junit.Test;
import java.util.Locale;
import static org.junit.Assert.*;

public class PvcPresentationLocaleTest {
    @Test public void forcedEnglishPresentationLeavesRequestedPreferenceIntact() {
        Locale saved = Locale.ITALY;
        assertSame(Locale.US, PvcPresentationLocale.resolve(saved, Locale.US, true));
        assertSame(Locale.ITALY, saved);
        assertSame(saved, PvcPresentationLocale.resolve(saved, Locale.US, false));
    }
    @Test public void startupWithoutRegisteredDefaultRetainsRequestedLocale() {
        assertSame(Locale.ITALY, PvcPresentationLocale.resolve(Locale.ITALY, null, true));
    }
}
