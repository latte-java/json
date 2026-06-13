# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Documentation

- `docs/design/` — all design documents and specs (filenames prefixed with `YYYY-MM-DD-` creation date)
- `docs/implementation/` — all implementation plans (filenames prefixed with `YYYY-MM-DD-` creation date)

## Build system

Built with **Latte** (`project.latte`), not Maven/Gradle. Targets are invoked via the `latte` CLI from the repo root:

- `latte clean` — clean build outputs
- `latte build` — compile + JAR (no tests)
- `latte test` — runs TestNG suite (depends on `build`)
- `latte int` — local integration publish (depends on `test`)
- `latte release` — full release (depends on `clean` + `test`)
- `latte idea` — regenerate the IntelliJ `.iml`
- `latte print-dependency-tree`

Requires **Java 25** on the PATH. The `latte` CLI will tell you if it isn't.

### Running a single test

Use the `--test` flag on the `test` target:

```
latte test --test=JSONProcessorTest
```

## Git worktrees

Create all git worktrees inside the project directory under `.worktrees/` (e.g. `git worktree add .worktrees/<branch-name> <branch>`). Never create worktrees in the home directory or anywhere else outside the repo.

## Architecture

This module publishes one public class plus its exception:

- `org.lattejava.json.JSONProcessor` — combined serializer + deserializer
- `org.lattejava.json.JSONProcessingException` — runtime exception thrown by both paths

The parser is a private inner class `JSONProcessor.Parser` (single-pass recursive descent). There is no streaming API and no POJO binding — the surface is just `byte[] ⇄ Map<String, Object>`.

### Top-level-object constraint

`deserialize(byte[])` **rejects any top-level value that is not a JSON object**. Top-level arrays, strings, numbers, booleans, and `null` all throw. This is intentional — the module is designed around JWT payloads/headers (RFC 7519 §7.2 guarantees objects). Don't "fix" this by relaxing it; it's load-bearing for downstream callers.

### Parse-time defenses (all configurable on the constructor)

| Knob | Default | Effect |
|------|---------|--------|
| `maxNestingDepth` | 16 | Object/array depth — counted across both kinds together |
| `maxNumberLength` | 1000 | Digit-run length (integer + decimal + exponent digits; sign chars excluded) |
| `maxObjectMembers` | 1000 | Members per object — duplicate-key updates do not consume budget |
| `maxArrayElements` | 10000 | Elements per array |
| `allowDuplicateJSONKeys` | `false` | When `false`, duplicate keys throw |

The constructor rejects non-positive values for all four cap parameters — a 0/negative cap would silently disable the defense, so this is enforced. New caps should follow the same pattern.

### Type mapping

Deserialization:
- JSON object → `LinkedHashMap<String, Object>` (insertion-order preserving — tests assert this)
- JSON array → `ArrayList<Object>`
- JSON integer (no `.`, no `e`/`E`): `Long` if digit-run ≤ 18, else `BigInteger`. The 18-digit cutoff is deliberate (`Long.MAX_VALUE` is 19 digits) and avoids overflow checks.
- JSON number with `.` or exponent → `BigDecimal`
- string/boolean/null → `String` / `Boolean` / `null`

Serialization accepts: `String`, `Boolean`, `Integer`/`Long`/`Short`/`Byte`/`BigInteger`, `BigDecimal` (via `toPlainString()`), `Float`/`Double` (NaN/Infinity rejected), `Map`, `List`, `null`. Anything else throws.

`JSONProcessor` is documented and tested as **thread-safe** — keep it that way (all state is final and per-parse `Parser` instances are local).

## Java module system

Both main and test source roots have a `module-info.java`. The test module is `org.lattejava.json.tests` and `opens` itself to `org.testng` for reflection. Tests use `import module java.base;` / `import module org.testng;` — JDK 25 module imports, not class imports. New code should follow the same style.

## Code conventions

Authoritative rules live in `.claude/rules/` (auto-loaded for `**/*.java`):

- `code-conventions.md` — acronyms stay uppercase (`JSONProcessor`, not `JsonProcessor`), alphabetization defaults, in-class member order, prefer module imports
- `error-messages.md` — wrap runtime values in `[brackets]` in exception messages, log lines, and `toString()` output (not single or double quotes)

Don't reintroduce title-cased acronyms or quoted error values — both are inconsistent with the existing code.
