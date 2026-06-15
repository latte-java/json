/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Streaming JSON writer over a single growable UTF-8 byte buffer. Generated companion code acquires one
 * writer per serialization call and every nested companion writes into the same buffer, so a payload is
 * encoded exactly once regardless of nesting. {@link #finishString()} / {@link #finishBytes()} produce the
 * result and release the writer.
 *
 * <p>Buffers are recycled per thread: {@link #acquire(boolean)} reuses a thread-local instance (falling
 * back to a fresh one if the cached instance is mid-serialization, which only happens when a component
 * accessor itself serializes). Buffers larger than 1 MB are not retained.
 *
 * <p>Keyed member methods follow {@link JSON @JSON} null semantics: a {@code null} value is omitted when
 * the writer was acquired with {@code omitNulls = true}, else written as {@code null}. Array element
 * methods always write {@code null} elements — elements are positional.
 *
 * @author Brian Pontarelli
 */
public final class JSONWriter {
  private static final ThreadLocal<JSONWriter> CACHED = ThreadLocal.withInitial(JSONWriter::new);
  private static final char[] HEX = "0123456789abcdef".toCharArray();
  private static final int INITIAL_CAPACITY = 512;
  private static final int MAX_CAPACITY = Integer.MAX_VALUE - 8;
  private static final boolean[] NEEDS_ESCAPE = new boolean[128];
  private static final int RETAIN_LIMIT = 1 << 20;

  static {
    for (int i = 0; i < 0x20; i++) {
      NEEDS_ESCAPE[i] = true;
    }
    NEEDS_ESCAPE['"'] = true;
    NEEDS_ESCAPE['\\'] = true;
  }
  private byte[] buf = new byte[INITIAL_CAPACITY];
  private int depth;
  private final StringBuilder doubleScratch = new StringBuilder(32);
  private long[] firstBits = new long[1];
  private boolean inUse;
  private boolean omitNulls = true;
  private boolean pendingValue;
  private int pos;

  private JSONWriter() {
  }

  public static JSONWriter acquire(boolean omitNulls) {
    JSONWriter w = CACHED.get();
    if (w.inUse) {
      w = new JSONWriter();
    }
    w.inUse = true;
    w.omitNulls = omitNulls;
    w.pendingValue = false;
    w.depth = 0;
    w.pos = 0;
    return w;
  }

  /**
   * Writes {@code value} at {@code key} as its natural JSON shape, recursing into {@code Map}/{@code List}. Used to
   * spread a {@code @JSONCatchAll} map; throws on a value type outside the natural shapes that the parser produces.
   */
  public JSONWriter any(String key, Object value) {
    switch (value) {
      case null -> nullValue(key);
      case String s -> string(key, s);
      case Boolean b -> bool(key, b.booleanValue());
      case BigInteger bi -> bigInteger(key, bi);
      case BigDecimal bd -> decimal(key, bd);
      case Double d -> decimal(key, d);
      case Float f -> decimal(key, f);
      case Number n -> integer(key, n);
      case Map<?, ?> m -> {
        key(key);
        beginObject();
        for (Map.Entry<?, ?> e : m.entrySet()) {
          any(String.valueOf(e.getKey()), e.getValue());
        }
        endObject();
      }
      case List<?> list -> {
        key(key);
        beginArray();
        for (Object element : list) {
          anyElement(element);
        }
        endArray();
      }
      default -> throw new JSONProcessingException("Unsupported catch-all value type [" + value.getClass() + "]");
    }
    return this;
  }

  /** Array-element variant of {@link #any(String, Object)}. */
  public JSONWriter anyElement(Object value) {
    switch (value) {
      case null -> nullElement();
      case String s -> stringElement(s);
      case Boolean b -> boolElement(b.booleanValue());
      case BigInteger bi -> bigIntegerElement(bi);
      case BigDecimal bd -> decimalElement(bd);
      case Double d -> decimalElement(d);
      case Float f -> decimalElement(f);
      case Number n -> integerElement(n.longValue());
      case Map<?, ?> m -> {
        beginObject();
        for (Map.Entry<?, ?> e : m.entrySet()) {
          any(String.valueOf(e.getKey()), e.getValue());
        }
        endObject();
      }
      case List<?> list -> {
        beginArray();
        for (Object element : list) {
          anyElement(element);
        }
        endArray();
      }
      default -> throw new JSONProcessingException("Unsupported catch-all value type [" + value.getClass() + "]");
    }
    return this;
  }

  public JSONWriter beginArray() {
    valueStart();
    push();
    ensure(1);
    buf[pos++] = '[';
    return this;
  }

  public JSONWriter beginObject() {
    valueStart();
    push();
    ensure(1);
    buf[pos++] = '{';
    return this;
  }

  public JSONWriter bigInteger(String key, BigInteger value) {
    if (value == null) {
      return nullValue(key);
    }
    writeKey(key);
    writeAscii(value.toString());
    return this;
  }

  public JSONWriter bigIntegerElement(BigInteger value) {
    if (value == null) {
      return nullElement();
    }
    valueStart();
    writeAscii(value.toString());
    return this;
  }

  public JSONWriter bool(String key, boolean value) {
    writeKey(key);
    writeAscii(value ? "true" : "false");
    return this;
  }

  public JSONWriter bool(String key, Boolean value) {
    if (value == null) {
      return nullValue(key);
    }
    return bool(key, value.booleanValue());
  }

  public JSONWriter boolElement(boolean value) {
    valueStart();
    writeAscii(value ? "true" : "false");
    return this;
  }

  public JSONWriter boolElement(Boolean value) {
    if (value == null) {
      return nullElement();
    }
    return boolElement(value.booleanValue());
  }

  public JSONWriter decimal(String key, BigDecimal value) {
    if (value == null) {
      return nullValue(key);
    }
    writeKey(key);
    writeBigDecimal(value);
    return this;
  }

  public JSONWriter decimal(String key, double value) {
    writeKey(key);
    writeDouble(value);
    return this;
  }

  public JSONWriter decimal(String key, Double value) {
    if (value == null) {
      return nullValue(key);
    }
    writeKey(key);
    writeDouble(value.doubleValue());
    return this;
  }

  public JSONWriter decimal(String key, Float value) {
    if (value == null) {
      return nullValue(key);
    }
    writeKey(key);
    writeDouble(value.doubleValue());
    return this;
  }

  public JSONWriter decimalElement(BigDecimal value) {
    if (value == null) {
      return nullElement();
    }
    valueStart();
    writeBigDecimal(value);
    return this;
  }

  public JSONWriter decimalElement(double value) {
    valueStart();
    writeDouble(value);
    return this;
  }

  public JSONWriter decimalElement(Double value) {
    if (value == null) {
      return nullElement();
    }
    return decimalElement(value.doubleValue());
  }

  public JSONWriter decimalElement(Float value) {
    if (value == null) {
      return nullElement();
    }
    return decimalElement(value.doubleValue());
  }

  public JSONWriter endArray() {
    depth--;
    ensure(1);
    buf[pos++] = ']';
    return this;
  }

  public JSONWriter endObject() {
    depth--;
    ensure(1);
    buf[pos++] = '}';
    return this;
  }

  public byte[] finishBytes() {
    byte[] result = Arrays.copyOf(buf, pos);
    release();
    return result;
  }

  public String finishString() {
    String result = new String(buf, 0, pos, StandardCharsets.UTF_8);
    release();
    return result;
  }

  /** Writes an Instant as an ISO-8601 string with output identical to {@code Instant.toString()}. */
  public JSONWriter instant(String key, Instant value) {
    if (value == null) {
      return nullValue(key);
    }
    writeKey(key);
    writeInstant(value);
    return this;
  }

  public JSONWriter instantElement(Instant value) {
    if (value == null) {
      return nullElement();
    }
    valueStart();
    writeInstant(value);
    return this;
  }

  public JSONWriter integer(String key, long value) {
    writeKey(key);
    writeLong(value);
    return this;
  }

  public JSONWriter integer(String key, Number value) {
    if (value == null) {
      return nullValue(key);
    }
    writeKey(key);
    writeAscii(value.toString());
    return this;
  }

  public JSONWriter integerElement(long value) {
    valueStart();
    writeLong(value);
    return this;
  }

  public JSONWriter integerElement(Long value) {
    if (value == null) {
      return nullElement();
    }
    return integerElement(value.longValue());
  }

  /** Writes the member key and separator, leaving the writer expecting the member value next. */
  public JSONWriter key(String key) {
    writeKey(key);
    pendingValue = true;
    return this;
  }

  public JSONWriter nullElement() {
    valueStart();
    writeAscii("null");
    return this;
  }

  public JSONWriter nullValue(String key) {
    if (omitNulls) {
      return this;
    }
    writeKey(key);
    writeAscii("null");
    return this;
  }

  /**
   * Swaps the null-omission flag and returns the previous value. Generated {@code write} methods swap in
   * their own type's {@code omitNulls} on entry and restore on exit, since nested types may differ.
   */
  public boolean omitNulls(boolean omitNulls) {
    boolean previous = this.omitNulls;
    this.omitNulls = omitNulls;
    return previous;
  }

  public JSONWriter string(String key, String value) {
    if (value == null) {
      return nullValue(key);
    }
    writeKey(key);
    writeStringValue(value);
    return this;
  }

  public JSONWriter stringElement(String value) {
    if (value == null) {
      return nullElement();
    }
    valueStart();
    writeStringValue(value);
    return this;
  }

  /** Writes a UUID as a string with output identical to {@code UUID.toString()}. */
  public JSONWriter uuid(String key, UUID value) {
    if (value == null) {
      return nullValue(key);
    }
    writeKey(key);
    writeUUID(value);
    return this;
  }

  public JSONWriter uuidElement(UUID value) {
    if (value == null) {
      return nullElement();
    }
    valueStart();
    writeUUID(value);
    return this;
  }

  private void ensure(int needed) {
    // Compute the requirement in long so a large output can't wrap pos + needed into a negative int
    // and silently skip the grow (which would later index past the buffer). Growth doubles but is
    // clamped to the max array size; an output that genuinely needs more fails loudly.
    long required = (long) pos + needed;
    if (required <= buf.length) {
      return;
    }
    if (required > MAX_CAPACITY) {
      throw new JSONProcessingException("JSON output exceeds the maximum supported size [" + MAX_CAPACITY + " bytes]");
    }
    buf = Arrays.copyOf(buf, (int) Math.min(Math.max((long) buf.length << 1, required), MAX_CAPACITY));
  }

  private void push() {
    int idx = depth >> 6;
    if (idx >= firstBits.length) {
      firstBits = Arrays.copyOf(firstBits, idx + 1);
    }
    firstBits[idx] |= 1L << (depth & 63);
    depth++;
  }

  private void release() {
    inUse = false;
    if (buf.length > RETAIN_LIMIT) {
      buf = new byte[INITIAL_CAPACITY];
    }
  }

  private void valueStart() {
    if (pendingValue) {
      pendingValue = false;
      return;
    }
    if (depth == 0) {
      return;
    }
    int top = depth - 1;
    int idx = top >> 6;
    long bit = 1L << (top & 63);
    if ((firstBits[idx] & bit) != 0) {
      firstBits[idx] &= ~bit;
    } else {
      ensure(1);
      buf[pos++] = ',';
    }
  }

  private void writeAscii(String s) {
    int len = s.length();
    ensure(len);
    for (int i = 0; i < len; i++) {
      buf[pos++] = (byte) s.charAt(i);
    }
  }

  /**
   * Writes a BigDecimal in plain notation. {@code toString()} (which BigDecimal caches) is used when its
   * output is provably plain-form — scale ≥ 0 and adjusted exponent ≥ -6 per its contract — so repeated
   * serialization of the same value allocates nothing; otherwise {@code toPlainString()}.
   */
  private void writeBigDecimal(BigDecimal value) {
    int scale = value.scale();
    if (scale >= 0 && value.precision() - 1 - scale >= -6) {
      writeAscii(value.toString());
    } else {
      writeAscii(value.toPlainString());
    }
  }

  /**
   * Writes a double with the wire format of {@code BigDecimal.valueOf(d).toPlainString()}. The digits
   * come from a reused {@code StringBuilder.append(double)} — the same shortest-round-trip algorithm as
   * {@code Double.toString} without the String allocation. When that form needs no exponent the two
   * formats are identical (BigDecimal preserves the literal); exponent forms fall back to BigDecimal.
   */
  private void writeDouble(double value) {
    if (!Double.isFinite(value)) {
      // Same rejection (and exception) BigDecimal.valueOf has always thrown for NaN/Infinity.
      writeAscii(BigDecimal.valueOf(value).toPlainString());
      return;
    }
    if (value == 0.0) {
      // BigDecimal has no signed zero: -0.0 has always serialized as [0.0].
      writeAscii("0.0");
      return;
    }
    StringBuilder sb = doubleScratch;
    sb.setLength(0);
    sb.append(value);
    int length = sb.length();
    for (int i = 0; i < length; i++) {
      if (sb.charAt(i) == 'E') {
        writeAscii(BigDecimal.valueOf(value).toPlainString());
        return;
      }
    }
    ensure(length);
    for (int i = 0; i < length; i++) {
      buf[pos++] = (byte) sb.charAt(i);
    }
  }

  /** Capacity is the caller's responsibility — {@link #writeStringValue} reserves the 6-byte worst case before each call. */
  private void writeEscape(char c) {
    buf[pos++] = '\\';
    switch (c) {
      case '"' -> buf[pos++] = '"';
      case '\\' -> buf[pos++] = '\\';
      case '\b' -> buf[pos++] = 'b';
      case '\f' -> buf[pos++] = 'f';
      case '\n' -> buf[pos++] = 'n';
      case '\r' -> buf[pos++] = 'r';
      case '\t' -> buf[pos++] = 't';
      default -> {
        buf[pos++] = 'u';
        buf[pos++] = '0';
        buf[pos++] = '0';
        buf[pos++] = (byte) HEX[(c >> 4) & 0xF];
        buf[pos++] = (byte) HEX[c & 0xF];
      }
    }
  }

  /**
   * Writes {@code "yyyy-MM-ddTHH:mm:ss[.fff[fff[fff]]]Z"} exactly as {@code Instant.toString()} does:
   * civil date from the epoch day (Hinnant's algorithm), the year sign-prefixed outside 0000–9999, and
   * the nano fraction in trailing-zero-trimmed groups of three digits.
   */
  private void writeInstant(Instant value) {
    long epochSecond = value.getEpochSecond();
    int nano = value.getNano();
    long epochDay = Math.floorDiv(epochSecond, 86400L);
    int secondOfDay = (int) Math.floorMod(epochSecond, 86400L);

    long z = epochDay + 719468;
    long era = Math.floorDiv(z, 146097L);
    long dayOfEra = z - era * 146097;
    long yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365;
    long dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100);
    long mp = (5 * dayOfYear + 2) / 153;
    int day = (int) (dayOfYear - (153 * mp + 2) / 5 + 1);
    int month = (int) (mp < 10 ? mp + 3 : mp - 9);
    long year = yearOfEra + era * 400 + (month <= 2 ? 1 : 0);

    ensure(48);
    buf[pos++] = '"';
    if (year < 0) {
      buf[pos++] = '-';
      writePaddedYear(-year);
    } else if (year > 9999) {
      buf[pos++] = '+';
      writeLong(year);
    } else {
      writePaddedYear(year);
    }
    buf[pos++] = '-';
    writeTwoDigits(month);
    buf[pos++] = '-';
    writeTwoDigits(day);
    buf[pos++] = 'T';
    writeTwoDigits(secondOfDay / 3600);
    buf[pos++] = ':';
    writeTwoDigits((secondOfDay / 60) % 60);
    buf[pos++] = ':';
    writeTwoDigits(secondOfDay % 60);
    if (nano != 0) {
      buf[pos++] = '.';
      int digits;
      if (nano % 1_000_000 == 0) {
        digits = 3;
        nano /= 1_000_000;
      } else if (nano % 1_000 == 0) {
        digits = 6;
        nano /= 1_000;
      } else {
        digits = 9;
      }
      for (int i = digits - 1, scaled = nano; i >= 0; i--) {
        buf[pos + i] = (byte) ('0' + scaled % 10);
        scaled /= 10;
      }
      pos += digits;
    }
    buf[pos++] = 'Z';
    buf[pos++] = '"';
  }

  private void writeKey(String key) {
    int top = depth - 1;
    int idx = top >> 6;
    long bit = 1L << (top & 63);
    if ((firstBits[idx] & bit) != 0) {
      firstBits[idx] &= ~bit;
    } else {
      ensure(1);
      buf[pos++] = ',';
    }
    writeStringValue(key);
    ensure(1);
    buf[pos++] = ':';
  }

  private void writeLong(long value) {
    if (value == Long.MIN_VALUE) {
      writeAscii("-9223372036854775808");
      return;
    }
    ensure(20);
    long v = value;
    if (v < 0) {
      buf[pos++] = '-';
      v = -v;
    }
    int start = pos;
    do {
      buf[pos++] = (byte) ('0' + (v % 10));
      v /= 10;
    } while (v != 0);
    for (int i = start, j = pos - 1; i < j; i++, j--) {
      byte t = buf[i];
      buf[i] = buf[j];
      buf[j] = t;
    }
  }

  private void writePaddedYear(long year) {
    if (year > 9999) {
      writeLong(year);
      return;
    }
    ensure(4);
    buf[pos++] = (byte) ('0' + (year / 1000) % 10);
    buf[pos++] = (byte) ('0' + (year / 100) % 10);
    buf[pos++] = (byte) ('0' + (year / 10) % 10);
    buf[pos++] = (byte) ('0' + year % 10);
  }

  @SuppressWarnings("deprecation") // String.getBytes(int,int,byte[],int) truncates chars — correct for verified-ASCII runs.
  private void writeStringValue(String s) {
    int len = s.length();
    // Reserve capacity per branch rather than the 6-bytes-per-char worst case upfront. An all-ASCII
    // string is one byte per char, so reserving 6x bloated the recycled buffer — and a large payload
    // could exceed RETAIN_LIMIT and be discarded every call, defeating the recycling. Each branch
    // below reserves only what it writes; the clean-ASCII run reserves its whole span once before the
    // bulk copy, so the hot path still pays one capacity check per run rather than per char.
    ensure(1);
    buf[pos++] = '"';
    int i = 0;
    while (i < len) {
      char c = s.charAt(i);
      if (c < 0x80) {
        if (NEEDS_ESCAPE[c]) {
          ensure(6);
          writeEscape(c);
          i++;
          continue;
        }
        // Clean ASCII run: getBytes bottoms out in the arraycopy intrinsic, which beats a manual loop
        // even for short runs.
        int run = i + 1;
        while (run < len && (c = s.charAt(run)) < 0x80 && !NEEDS_ESCAPE[c]) {
          run++;
        }
        ensure(run - i);
        s.getBytes(i, run, buf, pos);
        pos += run - i;
        i = run;
      } else {
        ensure(4);
        if (c < 0x800) {
          buf[pos++] = (byte) (0xC0 | (c >> 6));
          buf[pos++] = (byte) (0x80 | (c & 0x3F));
          i++;
        } else if (Character.isHighSurrogate(c)) {
          if (i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
            int cp = Character.toCodePoint(c, s.charAt(i + 1));
            buf[pos++] = (byte) (0xF0 | (cp >> 18));
            buf[pos++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
            buf[pos++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
            buf[pos++] = (byte) (0x80 | (cp & 0x3F));
            i += 2;
          } else {
            // Match String.getBytes(UTF_8): an unpaired surrogate encodes as [?].
            buf[pos++] = '?';
            i++;
          }
        } else if (Character.isLowSurrogate(c)) {
          buf[pos++] = '?';
          i++;
        } else {
          buf[pos++] = (byte) (0xE0 | (c >> 12));
          buf[pos++] = (byte) (0x80 | ((c >> 6) & 0x3F));
          buf[pos++] = (byte) (0x80 | (c & 0x3F));
          i++;
        }
      }
    }
    ensure(1);
    buf[pos++] = '"';
  }

  private void writeTwoDigits(int value) {
    ensure(2);
    buf[pos++] = (byte) ('0' + value / 10);
    buf[pos++] = (byte) ('0' + value % 10);
  }

  private void writeUUID(UUID value) {
    ensure(38);
    buf[pos++] = '"';
    long msb = value.getMostSignificantBits();
    long lsb = value.getLeastSignificantBits();
    writeUUIDHex(msb >>> 32, 8);
    buf[pos++] = '-';
    writeUUIDHex(msb >>> 16, 4);
    buf[pos++] = '-';
    writeUUIDHex(msb, 4);
    buf[pos++] = '-';
    writeUUIDHex(lsb >>> 48, 4);
    buf[pos++] = '-';
    writeUUIDHex(lsb, 12);
    buf[pos++] = '"';
  }

  private void writeUUIDHex(long value, int digits) {
    for (int i = digits - 1; i >= 0; i--) {
      buf[pos + i] = (byte) HEX[(int) (value & 0xF)];
      value >>>= 4;
    }
    pos += digits;
  }
}
