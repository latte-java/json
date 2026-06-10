package demo;

import module java.base;
import module org.lattejava.json;

/**
 * Every component type the processor supports, one of each, so the generated companion exercises every codegen
 * branch. Nested records and collections of nested records are intentionally absent — they are unsupported and
 * would cause the whole record to be rejected (see the kitchensinknested fixture for that boundary).
 */
@JSON
public record KitchenSink(
    // primitives (each has its own narrowing/serialize branch)
    boolean pBool, byte pByte, short pShort, int pInt, long pLong, float pFloat, double pDouble,
    // boxed wrappers (null-aware serialize)
    Boolean wBool, Byte wByte, Short wShort, Integer wInt, Long wLong, Float wFloat, Double wDouble,
    // string + big numbers + uuid + enum
    String str, BigInteger big, BigDecimal money, UUID id, Color color,
    // java.time scalars (string wire form via Conversions)
    Instant instant, LocalDate localDate, LocalDateTime localDateTime,
    OffsetDateTime offsetDateTime, ZonedDateTime zonedDateTime, Duration duration, Period period,
    // collections of strings / of wrappers / set
    List<String> tags, List<Integer> nums, Set<String> names,
    // maps: string key, and a non-string-keyed map (UUID key -> wire string)
    Map<String, Integer> counts, Map<UUID, String> labels) {
}
