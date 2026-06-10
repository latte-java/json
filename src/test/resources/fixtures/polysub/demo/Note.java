package demo;

import module org.lattejava.json;

@JSON
@JSONSubtype("note")
public final class Note implements Shape {
  private String text;

  public String getText() {
    return text;
  }

  public void setText(String text) {
    this.text = text;
  }
}
