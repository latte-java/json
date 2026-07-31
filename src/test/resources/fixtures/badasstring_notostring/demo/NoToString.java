package demo;

import module java.base;

/**
 * A class (not a record — a record would get a compiler-generated toString() that the check cannot tell from a
 * hand-written one) with the String constructor but no toString(), so it would serialize as demo.NoToString@1a2b3c.
 */
public class NoToString {
  private final String value;

  public NoToString(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }
}
