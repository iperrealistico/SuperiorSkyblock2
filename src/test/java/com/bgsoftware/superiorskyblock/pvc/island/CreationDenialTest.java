package com.bgsoftware.superiorskyblock.pvc.island;
import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.CompletionException;
import static org.junit.Assert.*;
public class CreationDenialTest {
    @Test public void expectedDenialRetainsReasonAndLimitThroughFutureWrapping() {
        PvcCreationDeniedException quota = new PvcCreationDeniedException(PvcCreationDeniedException.Reason.QUOTA_REACHED, 3);
        assertSame(quota, PvcCreationDeniedException.find(new CompletionException(quota)));
        assertEquals(3, quota.getLimit()); assertEquals(PvcCreationDeniedException.Reason.QUOTA_REACHED, quota.getReason());
        assertNull(PvcCreationDeniedException.find(new IOException("Real native storage failure")));
    }
    @Test public void cyclicProviderCausesCannotHangDenialClassification() {
        RuntimeException a = new RuntimeException(), b = new RuntimeException(); a.initCause(b); b.initCause(a);
        assertNull(PvcCreationDeniedException.find(a));
    }
}
