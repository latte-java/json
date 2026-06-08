/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

/**
 * Wire representation for an {@code Instant} {@code @JSON} record component. {@code ISO} (the default) is the
 * ISO-8601 string form (or the {@link JSONField#format()} pattern when set); {@code EPOCH_SECONDS} and
 * {@code EPOCH_MILLIS} are JSON integers counting from the epoch.
 *
 * @author Brian Pontarelli
 */
public enum InstantFormat {
  EPOCH_MILLIS,
  EPOCH_SECONDS,
  ISO
}
