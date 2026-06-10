package demo;

import module org.lattejava.json;

@JSON
public class TwoCtor {
  private final int x;
  @JSONConstructor public TwoCtor(int x) { this.x = x; }
  @JSONConstructor public TwoCtor(int x, int y) { this.x = x; }
  public int getX() { return x; }
}
