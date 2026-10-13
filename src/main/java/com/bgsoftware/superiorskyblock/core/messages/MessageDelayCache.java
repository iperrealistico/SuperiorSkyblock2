package com.bgsoftware.superiorskyblock.core.messages;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** One atomic claim per recipient and message; failed claims do not extend the configured write TTL. */
final class MessageDelayCache {
    private MessageDelayCache() { }
    static Cache<UUID, Boolean> create(long delayMillis) { return create(delayMillis, Ticker.systemTicker()); }
    static Cache<UUID, Boolean> create(long delayMillis, Ticker ticker) {
        return CacheBuilder.newBuilder().maximumSize(4096).expireAfterWrite(delayMillis, TimeUnit.MILLISECONDS)
                .ticker(ticker).build();
    }
    static boolean claim(Cache<UUID, Boolean> snapshot, UUID recipient) {
        return snapshot == null || snapshot.asMap().putIfAbsent(recipient, Boolean.TRUE) == null;
    }
}
