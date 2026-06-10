package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public class Unsupported {
  private Thread worker;
  public Unsupported() {}
  public Thread getWorker() { return worker; }
  public void setWorker(Thread worker) { this.worker = worker; }
}
