package demo;

import module org.lattejava.json;

@JSON
public record I(@JSONField(instant = InstantFormat.EPOCH_MILLIS) String notAnInstant) {
}
