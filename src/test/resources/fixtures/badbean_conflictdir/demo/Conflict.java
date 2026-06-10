package demo;

import module org.lattejava.json;

@JSON
public class Conflict {
  private int balance;

  public Conflict() {
  }

  public int getBalance() {
    return balance;
  }

  public void setBalance(int balance) {
    this.balance = balance;
  }

  @JSONField(writeOnly = true)
  public int getComputed() {
    return balance * 2;
  }
}
