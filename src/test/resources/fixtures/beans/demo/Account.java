package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public class Account {
  private String id;
  private int balance;
  private List<String> aliases;
  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public int getBalance() { return balance; }
  public void setBalance(int balance) { this.balance = balance; }
  public List<String> getAliases() { return aliases; }
  public void setAliases(List<String> aliases) { this.aliases = aliases; }
  public int getFeeBps() { return balance > 100 ? 0 : 25; }  // computed, read-only
}
