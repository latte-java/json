/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Marks a {@code String} record component, {@code @JSONConstructor} parameter, or JavaBean property as the receiver of
 * the verbatim JSON text of the object being deserialized — from its opening brace through its matching closing brace,
 * with interior whitespace and key order exactly as they appeared in the input. At most one is permitted per
 * {@link JSON @JSON} type.
 *
 * <p>The member is deserialize-only. It owns no JSON key, is never matched against an incoming key, and is never
 * written by the generated {@code toJSON}. A JSON key that happens to match the member's Java name is therefore an
 * unknown key, handled by the type's usual unknown-key policy.
 *
 * <p>A {@code transient} field carrying this annotation is excluded from binding entirely and silently receives no
 * value, the same as a {@code transient} field carrying {@link JSONField @JSONField} or
 * {@link JSONCatchAll @JSONCatchAll}.
 *
 * @author Brian Pontarelli
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface JSONRaw {
}
