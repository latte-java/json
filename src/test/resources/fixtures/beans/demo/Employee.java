package demo;

import module org.lattejava.json;

class Person {
  private String name;
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
}

@JSON
public class Employee extends Person {
  private int id;
  public int getId() { return id; }
  public void setId(int id) { this.id = id; }
}
