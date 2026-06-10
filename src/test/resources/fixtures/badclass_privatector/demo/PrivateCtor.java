package demo;

import module org.lattejava.json;

@JSON
public class PrivateCtor {
  private final int x;

  @JSONConstructor
  PrivateCtor(int x) {
    this.x = x;
  }

  public int getX() {
    return x;
  }
}
