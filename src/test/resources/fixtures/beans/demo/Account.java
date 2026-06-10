package demo;

import module org.lattejava.json;

@JSON
public class Account {
  private String id;
  private int balance;
  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public int getBalance() { return balance; }
  public void setBalance(int balance) { this.balance = balance; }
  public int getFeeBps() { return balance > 100 ? 0 : 25; }  // computed, read-only
}
