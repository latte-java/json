package demo;

import module org.lattejava.json;

@JSON
public class Mixed {
  private final String name;
  private final boolean active;
  private final int count;
  public final String tag;

  @JSONConstructor
  public Mixed(String name, boolean active, int count, String tag) {
    this.name = name;
    this.active = active;
    this.count = count;
    this.tag = tag;
  }

  public String getName() {
    return name;
  }

  public boolean isActive() {
    return active;
  }

  public int count() {
    return count;
  }
}
