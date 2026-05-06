---
paths:
  - "**/*.java"
  - project.latte
---

# Copyright Header

Every Java file — including `module-info.java` — starts with the SPDX-tagged copyright header as the very first thing in the file (before the `package` or `module` declaration). No blank line above it.

## The header

```java
/*
 * Copyright (c) <year> The Latte Project
 * SPDX-License-Identifier: MIT
 */
```

That is the only accepted form in this project. Do not invent variants — no prose paraphrases, no "see LICENSE for details" line, no `All Rights Reserved`, no Javadoc-style `/** */`.

## Why this exact form

- **SPDX is the modern, machine-readable standard.** The `SPDX-License-Identifier:` tag is defined by the SPDX spec and is the form authoritative compliance tooling (FOSSA, REUSE, `licensee`, GitHub's license detector) parses directly. No prose is needed alongside it.
- **No `All Rights Reserved`.** The phrase contradicts the MIT grant (which gives everyone broad rights), creates legal ambiguity, and is a vestige of pre-1989 copyright treaty law that's no longer required. Microsoft removed it from VS Code's headers in 2019; the OpenStack Legal FAQ, Liferay, and Šuklje's widely-cited "How and why to properly write copyright statements in your code" all recommend against it. The canonical MIT license text on opensource.org omits it.
- **Block comment, not Javadoc.** `/* */` keeps legal boilerplate out of generated API docs. `/** */` is for API documentation only.

The MIT license itself does not mandate any per-file header — the only hard requirement is reproducing the full license text "in all copies or substantial portions of the Software," satisfied by `LICENSE` at the repo root. The SPDX header is an unambiguous convention layered on top.

## Copyright line format

```
Copyright (c) <year> The Latte Project
```

- `(c)` lowercase in parens. Don't use `©` — keep it ASCII.
- No comma between year and holder. (The canonical MIT text has no comma.)
- Holder is always exactly `The Latte Project`.

## Year handling

- New file: single year — `Copyright (c) 2026 The Latte Project`.
- File modified in a later year than it was created: extend to a range — `Copyright (c) 2025-2026 The Latte Project`. Bump the upper bound on substantive changes, not whitespace edits. Don't list years individually.

## Placement

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Class-level Javadoc — separate block, not part of the file header.
 *
 * @author ...
 */
public class Example {
}
```

The file header is not the class Javadoc. They are distinct blocks separated by the `package` declaration and imports. `module-info.java` follows the same pattern — file header at the top, then the `module` declaration.

## Upstream files

Some files in this project might be copied from upstream projects. These files will have an Apache license header or the holder will be `FusionAuth` or `Inversoft`. You *MUST* preserve the copyright header in those files.

## Sources

- [opensource.org — MIT License canonical text](https://opensource.org/license/MIT)
- [SPDX — Using SPDX short identifiers in source files](https://spdx.github.io/spdx-spec/v2.3/using-SPDX-short-identifiers-in-source-files/)
- [Choose a License — MIT](https://choosealicense.com/licenses/mit/)
- [Matija Šuklje — How and why to properly write copyright statements in your code](https://matija.suklje.name/how-and-why-to-properly-write-copyright-statements-in-your-code)
- [microsoft/vscode#96747 — Remove "All rights reserved" from copyright headers](https://github.com/microsoft/vscode/issues/96747)
