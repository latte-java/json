package demo;

import module org.lattejava.json;

@JSON
public class NoReader {
  private final String secret;
  @JSONConstructor public NoReader(String secret) { this.secret = secret; }
}
