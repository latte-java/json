/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.json;

/**
 * Thrown by {@link JSONProcessor} implementations when JSON serialization or deserialization fails. The
 * {@code JSONProcessor} interface itself declares this in its {@code throws} clause for documentation; the
 * encoder/decoder catch and propagate these directly.
 *
 * @author Daniel DeGroff
 */
public class JSONProcessingException extends RuntimeException {
  public JSONProcessingException(String message) {
    super(message);
  }

  public JSONProcessingException(String message, Throwable cause) {
    super(message, cause);
  }

  public JSONProcessingException(Throwable cause) {
    super(cause == null ? null : cause.getMessage(), cause);
  }
}
