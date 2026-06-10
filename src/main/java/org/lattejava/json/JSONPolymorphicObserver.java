/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

/**
 * Observer used by {@link JSONParser} to dispatch a polymorphic sealed-type hierarchy to one of its
 * permitted subtype observers. The parser scans ahead in the JSON object for the discriminator key,
 * rewinds, and parses normally into the concrete child observer returned by {@link #observerFor(String)}.
 *
 * @param <T> the sealed parent type
 * @author Brian Pontarelli
 */
public non-sealed interface JSONPolymorphicObserver<T> extends JSONObjectHandler {
  String discriminatorKey();

  JSONObserver<? extends T> observerFor(String discriminatorValue);
}
