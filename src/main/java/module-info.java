/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
module org.lattejava.json {
  requires java.compiler;

  exports org.lattejava.json;

  provides javax.annotation.processing.Processor with org.lattejava.json.JSONProcessor;
}
