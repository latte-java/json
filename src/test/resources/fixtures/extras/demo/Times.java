package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Times(Instant instant, LocalDate localDate, LocalDateTime localDateTime,
                    OffsetDateTime offsetDateTime, ZonedDateTime zonedDateTime,
                    Duration duration, Period period) {
}
