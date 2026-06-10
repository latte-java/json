package demo;

import module org.lattejava.json;

@JSON
@JSONSubtype("square")
public final class Square implements Shape {
  private final int side;

  @JSONConstructor
  public Square(int side) {
    this.side = side;
  }

  public int getSide() {
    return side;
  }
}
