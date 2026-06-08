package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Times(
    @JSONField(format = "MM/dd/yyyy") LocalDate date,
    @JSONField(format = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime stamp,
    @JSONField(instant = InstantFormat.EPOCH_MILLIS) Instant millis,
    @JSONField(instant = InstantFormat.EPOCH_SECONDS) Instant seconds) {
}
