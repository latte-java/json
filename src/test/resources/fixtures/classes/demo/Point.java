package demo;

import module org.lattejava.json;

@JSON
public class Point {
  private final int x;
  private final int y;

  @JSONConstructor
  public Point(int x, int y) {
    this.x = x;
    this.y = y;
  }

  public int getX() {
    return x;
  }

  public int getY() {
    return y;
  }
}
