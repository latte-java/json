package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record C(@JSONField(instant = InstantFormat.EPOCH_MILLIS, format = "x") Instant both) {
}
