package com.bgsoftware.superiorskyblock.core.key.map;

import com.bgsoftware.common.annotations.NotNull;
import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.api.key.KeyMap;
import com.bgsoftware.superiorskyblock.core.LazyReference;
import com.bgsoftware.superiorskyblock.core.key.BaseKey;
import com.bgsoftware.superiorskyblock.core.key.types.CustomKey;
import com.bgsoftware.superiorskyblock.core.key.types.LazyKey;
import com.google.common.base.Preconditions;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

public class AbstractKeyMap<K extends Key, V> extends AbstractMap<Key, V> implements KeyMap<V> {

    private final LazyReference<Map<K, V>> innerMap;
    private final LazyReference<Map<CustomKey, V>> customInnerMap;
    private final Class<K> keyType;

    private final EntrySet entrySet = new EntrySet();

    protected AbstractKeyMap(KeyMapStrategy strategy, Class<K> keyType) {
        this.innerMap = new LazyReference<Map<K, V>>() {
            @Override
            protected Map<K, V> create() {
                return strategy.create(false);
            }
        };
        this.customInnerMap = new LazyReference<Map<CustomKey, V>>() {
            @Override
            protected Map<CustomKey, V> create() {
                return strategy.create(true);
            }
        };
        this.keyType = keyType;
    }

    @Override
    public int size() {
        return this.innerMap.getIfPresent().map(Map::size).orElse(0) +
                this.customInnerMap.getIfPresent().map(Map::size).orElse(0);
    }

    @Override
    public boolean containsKey(Object key) {
        return this.get(key) != null;
    }

    @Override
    public V get(Object obj) {
        if (obj instanceof LazyKey) {
            return get(((LazyKey<?>) obj).getBaseKey());
        }

        return obj instanceof Key ? getInternal((Key) obj, true) : null;
    }

    private V getInternal(Key key, boolean tryGlobal) {
        Preconditions.checkArgument(!(key instanceof LazyKey), "Cannot call getInternal on LazyKey directly.");

        V value;

        if (this.keyType.isInstance(key)) {
            value = this.innerMap.getIfPresent().map(m -> m.get(key)).orElse(null);
        } else if (key instanceof CustomKey) {
            value = this.customInnerMap.getIfPresent().map(m -> m.get(key)).orElse(null);
        } else {
            value = null;
        }

        if (!tryGlobal || value != null)
            return value;

        Key globalKey = ((BaseKey<?>) key).toGlobalKey();

        return globalKey == key ? null : getInternal(globalKey, false);
    }

    @Override
    public V put(Key key, V value) {
        if (key instanceof LazyKey) {
            return put(((LazyKey<?>) key).getBaseKey(), value);
        }

        V oldValue;

        if (this.keyType.isInstance(key)) {
            oldValue = this.innerMap.get().put(this.keyType.cast(key), value);
        } else if (key instanceof CustomKey) {
            oldValue = this.customInnerMap.get().put((CustomKey) key, value);
        } else {
            throw new IllegalArgumentException("key " + key.getClass() + " is not of type " + this.keyType);
        }

        return oldValue;
    }

    @Override
    public V remove(Object key) {
        return removeNoSizeCheck(key);
    }

    private V removeNoSizeCheck(Object key) {
        if (key instanceof LazyKey) {
            return removeNoSizeCheck(((LazyKey<?>) key).getBaseKey());
        }

        V oldValue;

        if (this.keyType.isInstance(key)) {
            oldValue = this.innerMap.getIfPresent().map(m -> m.remove(key)).orElse(null);
        } else if (key instanceof CustomKey) {
            oldValue = this.customInnerMap.getIfPresent().map(m -> m.remove(key)).orElse(null);
        } else {
            return null;
        }

        return oldValue;
    }

    @Override
    public void clear() {
        this.innerMap.getIfPresent().ifPresent(Map::clear);
        this.customInnerMap.getIfPresent().ifPresent(Map::clear);
    }

    @NotNull
    @Override
    public Set<Entry<Key, V>> entrySet() {
        return this.entrySet;
    }

    @Nullable
    @Override
    public Key getKey(Key original) {
        return this.getKey(original, null);
    }

    @Override
    public Key getKey(Key original, @Nullable Key def) {
        if (original instanceof LazyKey) {
            return getKey(((LazyKey<?>) original).getBaseKey(), def);
        }

        Map<? extends Key, V> map;

        if (this.keyType.isInstance(original)) {
            map = this.innerMap.getIfPresent().orElse(null);
        } else if (original instanceof CustomKey) {
            map = this.customInnerMap.getIfPresent().orElse(null);
        } else {
            map = null;
        }

        if (map != null) {
            if (map.containsKey(original))
                return original;
            Key globalKey = ((BaseKey<?>) original).toGlobalKey();
            if (globalKey != original && map.containsKey(globalKey))
                return globalKey;
        }

        return def;
    }

    @Override
    public boolean removeIf(Predicate<Key> predicate) {
        if (isEmpty())
            return false;

        boolean removed = false;

        {
            Map<K, V> innerMap = this.innerMap.getIfPresent().orElse(null);
            if (innerMap != null) {
                removed |= innerMap.entrySet().removeIf(entry -> predicate.test(entry.getKey()));
            }
        }

        {
            Map<CustomKey, V> customInnerMap = this.customInnerMap.getIfPresent().orElse(null);
            if (customInnerMap != null) {
                removed |= customInnerMap.entrySet().removeIf(entry -> predicate.test(entry.getKey()));
            }
        }

        return removed;
    }

    @Override
    public V getRaw(Key key, V def) {
        return getRawNoSizeCheck(key, def);
    }

    private V getRawNoSizeCheck(Key key, V def) {
        if (key instanceof LazyKey) {
            return getRawNoSizeCheck(((LazyKey<?>) key).getBaseKey(), def);
        }

        return Optional.ofNullable(getInternal(key, false)).orElse(def);
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        V value = get(key);
        return value == null ? defaultValue : value;
    }

    @Override
    public V merge(Key key, @NotNull V value, @NotNull BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        Objects.requireNonNull(value);
        Key rawKey = unwrap(key);
        return rawMap(rawKey).merge(rawKey, value, remappingFunction);
    }

    /** Mutations are exact-key operations: a global fallback must never be updated by accident. */
    @Override public V compute(Key key, BiFunction<? super Key, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        Key rawKey = unwrap(key);
        return rawMap(rawKey).compute(rawKey, (k, v) -> function.apply(key, v));
    }

    @Override public V computeIfAbsent(Key key, Function<? super Key, ? extends V> function) {
        Objects.requireNonNull(function);
        Key rawKey = unwrap(key);
        return rawMap(rawKey).computeIfAbsent(rawKey, k -> function.apply(key));
    }

    @Override public V computeIfPresent(Key key, BiFunction<? super Key, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        Key rawKey = unwrap(key);
        return rawMap(rawKey).computeIfPresent(rawKey, (k, v) -> function.apply(key, v));
    }

    @Override public V putIfAbsent(Key key, V value) {
        Key rawKey = unwrap(key);
        return rawMap(rawKey).putIfAbsent(rawKey, value);
    }

    @Override public boolean remove(Object key, Object value) {
        if (!(key instanceof Key)) return false;
        Key rawKey = unwrap((Key) key);
        return existingRawMap(rawKey).map(m -> m.remove(rawKey, value)).orElse(false);
    }

    @Override public boolean replace(Key key, V oldValue, V newValue) {
        Key rawKey = unwrap(key);
        return existingRawMap(rawKey).map(m -> m.replace(rawKey, oldValue, newValue)).orElse(false);
    }

    @Override public V replace(Key key, V value) {
        Key rawKey = unwrap(key);
        return existingRawMap(rawKey).map(m -> m.replace(rawKey, value)).orElse(null);
    }

    @Override public void replaceAll(BiFunction<? super Key, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        this.innerMap.getIfPresent().ifPresent(m -> m.replaceAll(function));
        this.customInnerMap.getIfPresent().ifPresent(m -> m.replaceAll(function));
    }

    private Key unwrap(Key key) {
        return key instanceof LazyKey ? unwrap(((LazyKey<?>) key).getBaseKey()) : Objects.requireNonNull(key);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<Key, V> rawMap(Key key) {
        if (this.keyType.isInstance(key)) return (Map) this.innerMap.get();
        if (key instanceof CustomKey) return (Map) this.customInnerMap.get();
        throw new IllegalArgumentException("key " + key.getClass() + " is not of type " + this.keyType);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Optional<Map<Key, V>> existingRawMap(Key key) {
        if (this.keyType.isInstance(key)) return (Optional) this.innerMap.getIfPresent();
        if (key instanceof CustomKey) return (Optional) this.customInnerMap.getIfPresent();
        return Optional.empty();
    }

    @Override
    public Map<Key, V> asMap() {
        return this;
    }

    private class EntrySet extends AbstractSet<Entry<Key, V>> {

        @Override
        public int size() {
            return AbstractKeyMap.this.size();
        }

        @Override
        public boolean isEmpty() {
            return AbstractKeyMap.this.isEmpty();
        }

        @Override
        public boolean contains(Object o) {
            Object key;
            Object value;

            if (o instanceof Entry) {
                Entry<?, ?> entry = (Entry<?, ?>) o;
                key = entry.getKey();
                value = entry.getValue();
            } else {
                key = o;
                value = null;
            }

            V realValue = AbstractKeyMap.this.get(key);
            return realValue != null && (value == null || Objects.equals(realValue, value));
        }

        @NotNull
        @Override
        public Iterator<Entry<Key, V>> iterator() {
            return new EntryIterator();
        }

        @Override
        public void clear() {
            AbstractKeyMap.this.clear();
        }

    }

    private class EntryIterator implements Iterator<Entry<Key, V>> {

        private Iterator<Entry> currIterator;
        private final Iterator<Entry> customIterator;
        private Iterator<Entry> lastIterator;
        private boolean customStarted;

        EntryIterator() {
            Map innerMap = AbstractKeyMap.this.innerMap.getIfPresent().orElse(null);
            Map customInnerMap = AbstractKeyMap.this.customInnerMap.getIfPresent().orElse(null);
            this.currIterator = innerMap == null ? Collections.emptyIterator() : innerMap.entrySet().iterator();
            this.customIterator = customInnerMap == null ? Collections.emptyIterator() : customInnerMap.entrySet().iterator();
        }

        @Override
        public boolean hasNext() {
            return this.currIterator.hasNext() || (!customStarted && this.customIterator.hasNext());
        }

        @Override
        public Entry<Key, V> next() {
            if (!this.currIterator.hasNext()) {
                if (customStarted) throw new NoSuchElementException();
                this.currIterator = this.customIterator;
                this.customStarted = true;
            }
            Entry<Key, V> entry = this.currIterator.next();
            this.lastIterator = this.currIterator;
            return entry;
        }

        @Override
        public void remove() {
            if (this.lastIterator == null) throw new IllegalStateException();
            this.lastIterator.remove();
            this.lastIterator = null;
        }

    }

}
