package demo;

import module java.base;

/** Deserialize half only: a public single-String constructor, but toString() is inherited from Object. */
public class OnlyCtor {
  private final String value;

  public OnlyCtor(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }
}
