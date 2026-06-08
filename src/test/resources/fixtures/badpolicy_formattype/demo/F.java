package demo;

import module org.lattejava.json;

@JSON
public record F(@JSONField(format = "MM/dd/yyyy") String notATime) {
}
