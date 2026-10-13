package com.bgsoftware.superiorskyblock.core.key.map;

import com.bgsoftware.common.annotations.NotNull;
import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.api.key.KeyMap;
import com.bgsoftware.superiorskyblock.core.LazyReference;
import com.bgsoftware.superiorskyblock.core.key.BaseKey;
import com.bgsoftware.superiorskyblock.core.key.types.LazyKey;
import com.google.common.base.Preconditions;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

public class CustomKeyMap<V> extends AbstractMap<Key, V> implements KeyMap<V> {

    private final LazyReference<Map<Key, V>> innerMap;

    protected CustomKeyMap(KeyMapStrategy strategy) {
        this.innerMap = new LazyReference<Map<Key, V>>() {
            @Override
            protected Map<Key, V> create() {
                return strategy.create(false);
            }
        };
    }

    @Override
    public int size() {
        return this.innerMap.getIfPresent().map(Map::size).orElse(0);
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

        V value = this.innerMap.getIfPresent().map(m -> m.get(key)).orElse(null);

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

        return this.innerMap.get().put(key, value);
    }

    @Override
    public V remove(Object key) {
        return removeNoSizeCheck(key);
    }

    private V removeNoSizeCheck(Object key) {
        if (key instanceof LazyKey) {
            return removeNoSizeCheck(((LazyKey<?>) key).getBaseKey());
        }

        return this.innerMap.getIfPresent().map(m -> m.remove(key)).orElse(null);
    }

    @Override
    public void clear() {
        this.innerMap.getIfPresent().ifPresent(Map::clear);
    }

    @NotNull
    @Override
    public Set<Entry<Key, V>> entrySet() {
        return this.innerMap.getIfPresent().map(Map::entrySet).orElse(Collections.emptySet());
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

        Map<Key, V> map = this.innerMap.getIfPresent().orElse(null);

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

        return this.innerMap.getIfPresent().map(m ->
                        m.entrySet().removeIf(entry -> predicate.test(entry.getKey())))
                .orElse(false);
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
    public Map<Key, V> asMap() {
        return this;
    }

    private Key unwrap(Key key) {
        return key instanceof LazyKey ? unwrap(((LazyKey<?>) key).getBaseKey()) : Objects.requireNonNull(key);
    }

    @Override public V merge(Key key, V value, BiFunction<? super V, ? super V, ? extends V> function) {
        return this.innerMap.get().merge(unwrap(key), value, function);
    }
    @Override public V compute(Key key, BiFunction<? super Key, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        return this.innerMap.get().compute(unwrap(key), (k, v) -> function.apply(key, v));
    }
    @Override public V computeIfAbsent(Key key, Function<? super Key, ? extends V> function) {
        Objects.requireNonNull(function);
        return this.innerMap.get().computeIfAbsent(unwrap(key), k -> function.apply(key));
    }
    @Override public V computeIfPresent(Key key, BiFunction<? super Key, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        return this.innerMap.get().computeIfPresent(unwrap(key), (k, v) -> function.apply(key, v));
    }
    @Override public V putIfAbsent(Key key, V value) {
        return this.innerMap.get().putIfAbsent(unwrap(key), value);
    }
    @Override public boolean remove(Object key, Object value) {
        return key instanceof Key && this.innerMap.getIfPresent()
                .map(m -> m.remove(unwrap((Key) key), value)).orElse(false);
    }
    @Override public boolean replace(Key key, V oldValue, V newValue) {
        return this.innerMap.getIfPresent().map(m -> m.replace(unwrap(key), oldValue, newValue)).orElse(false);
    }
    @Override public V replace(Key key, V value) {
        return this.innerMap.getIfPresent().map(m -> m.replace(unwrap(key), value)).orElse(null);
    }
    @Override public void replaceAll(BiFunction<? super Key, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        this.innerMap.getIfPresent().ifPresent(m -> m.replaceAll(function));
    }

}
