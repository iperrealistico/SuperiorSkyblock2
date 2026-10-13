package com.bgsoftware.superiorskyblock.core.key.map;

import com.bgsoftware.common.annotations.NotNull;
import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.api.key.KeyMap;
import com.bgsoftware.superiorskyblock.core.key.types.EntityTypeKey;
import com.bgsoftware.superiorskyblock.core.key.types.LazyKey;
import com.bgsoftware.superiorskyblock.core.key.types.MaterialKey;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public class LazyLoadedKeyMap<V> extends AbstractMap<Key, V> implements KeyMap<V> {

    private final KeyMapStrategy strategy;
    @Nullable
    private volatile KeyMap<V> delegate;
    @Nullable
    private volatile KeyMap<V> pendingCustomKeys;
    private final Set<Entry<Key, V>> entries = new AbstractSet<Entry<Key, V>>() {
        @Override public int size() { return LazyLoadedKeyMap.this.size(); }
        @Override public void clear() { LazyLoadedKeyMap.this.clear(); }
        @Override public Iterator<Entry<Key, V>> iterator() {
            Iterator<Entry<Key, V>> backend = runOnMap(m -> m.entrySet().iterator(), Collections.emptyIterator());
            return new Iterator<Entry<Key, V>>() {
                private Entry<Key, V> last;
                @Override public boolean hasNext() { return backend.hasNext(); }
                @Override public Entry<Key, V> next() {
                    Entry<Key, V> current = backend.next();
                    last = current;
                    return new SimpleEntry<Key, V>(current) {
                        @Override public V setValue(V value) {
                            V previous = LazyLoadedKeyMap.this.put(getKey(), value);
                            super.setValue(value);
                            return previous;
                        }
                    };
                }
                @Override public void remove() {
                    if (last == null) throw new IllegalStateException();
                    LazyLoadedKeyMap.this.remove(last.getKey());
                    last = null;
                }
            };
        }
    };

    public LazyLoadedKeyMap(KeyMapStrategy strategy) {
        this.strategy = strategy;
    }

    @Override
    public int size() {
        return runOnMap(KeyMap::size, 0);
    }

    @Override
    public boolean containsKey(Object o) {
        return runOnMap(m -> m.containsKey(o), false);
    }

    @Override
    public V get(Object obj) {
        return runOnMap(m -> m.get(obj), null);
    }

    @Override
    public synchronized V put(Key key, V value) {
        if (this.delegate != null)
            return this.delegate.put(key, value);

        return putNoDelegate(key, value);
    }

    private V putNoDelegate(Key key, V value) {
        return writableMap(key).put(key, value);
    }

    private KeyMap<V> writableMap(Key key) {
        if (this.delegate != null) return this.delegate;
        if (key instanceof LazyKey) {
            return writableMap(((LazyKey<?>) key).getBaseKey());
        }

        if (key instanceof EntityTypeKey) {
            this.delegate = new EntityTypeKeyMap<>(this.strategy);
            addPendingCustomKeys();
        } else if (key instanceof MaterialKey) {
            this.delegate = new MaterialKeyMap<>(this.strategy);
            addPendingCustomKeys();
        } else {
            if (this.pendingCustomKeys == null)
                this.pendingCustomKeys = new CustomKeyMap<>(this.strategy);

            return this.pendingCustomKeys;
        }

        return this.delegate;
    }

    @Override
    public V remove(Object key) {
        return runOnMap(m -> m.remove(key), null);
    }

    @Override
    public void clear() {
        runOnMap(KeyMap::clear);
    }

    @NotNull
    @Override
    public Set<Entry<Key, V>> entrySet() {
        return entries;
    }

    @Override
    public String toString() {
        return runOnMap(KeyMap::toString, "LazyLoadedKeyMap{}");
    }

    @Nullable
    @Override
    public Key getKey(Key original) {
        return runOnMap(m -> m.getKey(original), null);
    }

    @Override
    public Key getKey(Key original, @Nullable Key def) {
        return runOnMap(m -> m.getKey(original, def), def);
    }


    @Override
    public boolean removeIf(Predicate<Key> predicate) {
        return runOnMap(m -> m.removeIf(predicate), false);
    }

    @Override
    public V getRaw(Key key, V def) {
        return runOnMap(m -> m.getRaw(key, def), def);
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        return runOnMap(m -> m.getOrDefault(key, defaultValue), defaultValue);
    }

    @Override
    public synchronized V merge(Key key, @NotNull V value, @NotNull BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        Objects.requireNonNull(value);
        return writableMap(key).merge(key, value, remappingFunction);
    }

    @Override public synchronized V compute(Key key, BiFunction<? super Key, ? super V, ? extends V> function) {
        return writableMap(key).compute(key, function);
    }
    @Override public synchronized V computeIfAbsent(Key key, Function<? super Key, ? extends V> function) {
        return writableMap(key).computeIfAbsent(key, function);
    }
    @Override public synchronized V computeIfPresent(Key key, BiFunction<? super Key, ? super V, ? extends V> function) {
        return runOnMap(m -> m.computeIfPresent(key, function), null);
    }
    @Override public synchronized V putIfAbsent(Key key, V value) {
        return writableMap(key).putIfAbsent(key, value);
    }
    @Override public synchronized boolean remove(Object key, Object value) {
        return runOnMap(m -> m.remove(key, value), false);
    }
    @Override public synchronized boolean replace(Key key, V oldValue, V newValue) {
        return runOnMap(m -> m.replace(key, oldValue, newValue), false);
    }
    @Override public synchronized V replace(Key key, V value) {
        return runOnMap(m -> m.replace(key, value), null);
    }
    @Override public synchronized void replaceAll(BiFunction<? super Key, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        runOnMap(m -> m.replaceAll(function));
    }

    @Override
    public Map<Key, V> asMap() {
        // Do not expose a pending custom bucket that may later be replaced during typed promotion.
        return this;
    }

    private synchronized <T> T runOnMap(Function<KeyMap<V>, T> function, T def) {
        if (this.delegate != null)
            return function.apply(this.delegate);

        else if (this.pendingCustomKeys != null)
            return function.apply(this.pendingCustomKeys);

        else
            return def;
    }

    private synchronized void runOnMap(Consumer<KeyMap<V>> consumer) {
        if (this.delegate != null)
            consumer.accept(this.delegate);

        else if (this.pendingCustomKeys != null)
            consumer.accept(this.pendingCustomKeys);
    }

    private void addPendingCustomKeys() {
        if (this.pendingCustomKeys == null)
            return;

        this.delegate.putAll(this.pendingCustomKeys);
        this.pendingCustomKeys = null;
    }

}
