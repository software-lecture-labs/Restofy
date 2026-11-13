package com.restofy.menu.application.service;

import java.util.Objects;

/**
 * One field of a partial update that can be set <i>or cleared</i>. The three states of a PATCH field are
 * told apart by the holder itself: a {@code null} reference means the client did not send the field (keep the
 * stored value), {@link #clear()} means an explicit JSON {@code null} (remove the value) and {@link #set} is a
 * new value. A plain {@code null} component cannot express this, because absent and explicit-null look alike.
 */
public record FieldChange<T>(T value) {

    public static <T> FieldChange<T> set(T value) {
        return new FieldChange<>(Objects.requireNonNull(value, "value"));
    }

    public static <T> FieldChange<T> clear() {
        return new FieldChange<>(null);
    }
}
