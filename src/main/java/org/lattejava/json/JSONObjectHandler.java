/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

/**
 * Common supertype for the two things a parent observer may return from {@code beginObject}: a
 * {@link JSONObserver} for a concrete nested type, or a {@link JSONPolymorphicObserver} for a sealed
 * hierarchy. {@link JSONParser} pattern-matches the runtime kind and routes accordingly. Sealed so the
 * dispatch is exhaustively checked at compile time.
 *
 * @author Brian Pontarelli
 */
public sealed interface JSONObjectHandler permits JSONObserver, JSONPolymorphicObserver {
}
