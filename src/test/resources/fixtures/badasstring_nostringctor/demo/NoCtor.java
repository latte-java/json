package demo;

import module java.base;

/** Declares toString() but no public single-String constructor, so the deserialize half is missing. */
public class NoCtor {
  private final int value;

  public NoCtor(int value) {
    this.value = value;
  }

  @Override
  public String toString() {
    return Integer.toString(value);
  }
}
