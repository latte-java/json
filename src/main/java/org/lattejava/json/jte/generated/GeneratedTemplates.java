/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.jte.generated;

/**
 * Marker that seeds the {@code org.lattejava.json.jte.generated} package so it exists at {@code compileMain} time. The
 * package holds the JTE-precompiled template classes written by {@code org.lattejava.json.jte.Generate} during the
 * build; {@code module-info} {@code opens} it to {@code gg.jte.runtime}. Without a real class here the package would be
 * absent and the module descriptor would be rejected as invalid when the build reads it to run the generator.
 *
 * @author Brian Pontarelli
 */
final class GeneratedTemplates {
  private GeneratedTemplates() {
  }
}
