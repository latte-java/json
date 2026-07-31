package demo;

import module java.base;

/** Satisfies the string-convertible contract structurally — the member below just never opts in. */
public record SemVer(int major, int minor, int patch) {
  public SemVer(String value) {
    this(value.split("\\.", -1));
  }

  private SemVer(String[] parts) {
    this(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
  }

  @Override
  public String toString() {
    return major + "." + minor + "." + patch;
  }
}
