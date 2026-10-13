package com.bgsoftware.superiorskyblock.core.messages;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class MessageDelayCacheTest {
    @Test public void competingRegionsClaimTheSameRecipientOnlyOnce() throws Exception {
        Cache<UUID, Boolean> cache = MessageDelayCache.create(1000L);
        UUID recipient = UUID.randomUUID();
        AtomicInteger accepted = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(6);
        for (int i = 0; i < 6; ++i) new Thread(() -> {
            try {
                start.await();
                if (MessageDelayCache.claim(cache, recipient)) accepted.incrementAndGet();
                assertTrue(MessageDelayCache.claim(cache, UUID.randomUUID()));
            } catch (Throwable error) { failure.compareAndSet(null, error); }
            finally { done.countDown(); }
        }, "message-delay-correctness-" + i).start();
        start.countDown(); assertTrue(done.await(10L, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(1, accepted.get());
    }

    @Test public void rejectedMessagesDoNotSlideTheWriteExpiryAndDisabledOrReloadedCachesRemainIndependent() {
        AtomicLong clock = new AtomicLong();
        Ticker ticker = new Ticker() { @Override public long read() { return clock.get(); } };
        Cache<UUID, Boolean> cache = MessageDelayCache.create(100L, ticker);
        UUID recipient = UUID.randomUUID();
        assertTrue(MessageDelayCache.claim(cache, recipient));
        clock.set(TimeUnit.MILLISECONDS.toNanos(99L)); assertFalse(MessageDelayCache.claim(cache, recipient));
        clock.set(TimeUnit.MILLISECONDS.toNanos(101L)); assertTrue(MessageDelayCache.claim(cache, recipient));
        assertTrue(MessageDelayCache.claim(null, recipient));
        Cache<UUID, Boolean> reloaded = MessageDelayCache.create(100L, ticker);
        assertTrue(MessageDelayCache.claim(reloaded, recipient));
        assertFalse(MessageDelayCache.claim(cache, recipient));
    }

    @Test public void offlineRecipientsCannotGrowTheCacheWithoutBound() {
        Cache<UUID, Boolean> cache = MessageDelayCache.create(100000L);
        for (int i = 0; i < 4100; ++i) MessageDelayCache.claim(cache, new UUID(0L, i));
        cache.cleanUp(); assertTrue(cache.size() <= 4096L);
    }
}
