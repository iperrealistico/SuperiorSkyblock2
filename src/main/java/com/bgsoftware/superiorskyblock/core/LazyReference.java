package com.bgsoftware.superiorskyblock.core;

import java.util.Optional;

public abstract class LazyReference<E> {

    private volatile E instance;

    public E get() {
        E value = instance;
        if (value == null) {
            synchronized (this) {
                value = instance;
                if (value == null)
                    instance = value = create();
            }
        }
        return value;
    }

    protected abstract E create();

    public Optional<E> getIfPresent() {
        return Optional.ofNullable(this.instance);
    }

}
