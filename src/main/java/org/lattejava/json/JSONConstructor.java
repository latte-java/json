/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Marks the constructor the annotation processor should use to deserialize a non-record class. JSON-key
 * mapping is taken from the constructor's parameter names. Not used on records; records have a canonical
 * constructor.
 * <p>
 * TODO: Not implemented yet.
 *
 * @author Brian Pontarelli
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.CONSTRUCTOR)
public @interface JSONConstructor {
}
