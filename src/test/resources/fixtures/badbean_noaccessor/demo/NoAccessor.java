package demo;

import module org.lattejava.json;

@JSON
public class NoAccessor {
  @JSONField(name = "x") private String secret;
  public NoAccessor() {}
}
