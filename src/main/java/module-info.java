/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
module org.lattejava.json {
  requires gg.jte.runtime;
  requires java.compiler;
  requires static gg.jte;

  exports org.lattejava.json;

  opens org.lattejava.json.jte.generated to gg.jte.runtime;

  provides javax.annotation.processing.Processor with org.lattejava.json.JSONProcessor;
}
