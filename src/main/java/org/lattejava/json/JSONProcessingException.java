/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

/**
 * Runtime exception thrown by the JSON parser, builder, and generated companion classes on any parse or
 * serialization failure. Messages wrap runtime values in {@code [brackets]} and include the JSON-path of
 * the failure when known.
 *
 * @author Brian Pontarelli
 */
public class JSONProcessingException extends RuntimeException {
  public JSONProcessingException(String message) {
    super(message);
  }

  public JSONProcessingException(String message, Throwable cause) {
    super(message, cause);
  }
}
