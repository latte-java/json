package demo;

import module org.lattejava.json;

@JSON(naming = NamingStrategy.SNAKE_CASE)
public class Configured {
  private final String userName;
  private final String secret;

  @JSONConstructor
  public Configured(String userName, @JSONField(writeOnly = true) String secret) {
    this.userName = userName;
    this.secret = secret;
  }

  public String getUserName() {
    return userName;
  }
}
