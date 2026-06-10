package demo;

import module org.lattejava.json;

@JSON
public record Box(String label, Account account) {
}
