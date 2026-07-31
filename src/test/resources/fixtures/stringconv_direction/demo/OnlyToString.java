package demo;

import module java.base;

/** Serialize half only: a declared toString(), no public single-String constructor. */
public class OnlyToString {
  private final int value;

  public OnlyToString(int value) {
    this.value = value;
  }

  @Override
  public String toString() {
    return Integer.toString(value);
  }
}
