package demo;

import module org.lattejava.json;

@JSON
public record Bad(int x) {
  @JSONConstructor public Bad { }
}
