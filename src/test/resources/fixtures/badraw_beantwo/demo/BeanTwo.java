package demo;

import module org.lattejava.json;

@JSON
public class BeanTwo {
  @JSONRaw
  private String a;
  @JSONRaw
  private String b;

  public String getA() {
    return a;
  }

  public void setA(String a) {
    this.a = a;
  }

  public String getB() {
    return b;
  }

  public void setB(String b) {
    this.b = b;
  }
}
