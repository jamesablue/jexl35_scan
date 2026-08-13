# jexl-check

Reads a Harness YAML file and asks the **real Apache Commons JEXL 3.5 engine**
whether each embedded `<+ ... >` expression compiles.

```bash
mvn -q package
java -jar target/jexl-check.jar pipelines/*.yaml
```

Exit codes: `0` clean, `1` findings, `2` file unreadable or not valid YAML.
Add `--syntax-only` to report engine verdicts and skip the lexical policy pass.

## Testing

```bash
mvn test
```

32 tests in [`JexlCheckTest`](src/test/java/local/harness/JexlCheckTest.java),
in four groups matching the four things that can go wrong:

| Group | Covers |
|---|---|
| `DelimiterResolution` | `>` as closer vs. operator — `<+a > 3>`, `>=`, `=>`, two expressions on one line, unterminated wrappers |
| `YamlSemantics` | Comments invisible, folded scalars, sequences, multi-document files, line numbers, YAML unescaping |
| `EngineVerdicts` | Real parser results: `?[` rejected, `var` as `SCRIPT`, nested as `NESTED` |
| `PolicyFlags` | Mostly **negatives** — `==`, `>=`, `!=`, `=>`, `var x = y`, and `=` inside strings and comments must *not* be flagged |

Run one group, or one test:

```bash
mvn test -Dtest='JexlCheckTest$PolicyFlags'
```

### The engine is not mocked

Tests in `EngineVerdicts` call the real parser, because a mocked parser would
test nothing worth testing — the tool's entire claim is that verdicts come from
the shipped engine. The consequence is that these expectations are tied to
`commons.jexl.version`. Under JEXL 3.0 the `?[` test legitimately fails, since
3.0 accepts that syntax; that is the regression the tool exists to find, not a
broken test.

### Why the negatives dominate the policy tests

`SYNTAX` findings cannot really be wrong — the engine either threw or it didn't.
`POLICY` findings are greps, so the risk is entirely false positives, and eight
of the eleven policy tests assert that something is **not** flagged.

### Verifying the suite actually bites

The tests were checked by mutation, not just by passing. Breaking the
longest-parse rule in `extractExpressions` (taking the first parsable span
instead of the longest) fails 3 `DelimiterResolution` tests; making
`blankComments` a no-op fails 1 `PolicyFlags` test. If you change either
mechanism, confirm the relevant group still fails when you deliberately break it.

## Why the engine and not a regex

The YAML is composed with SnakeYAML, so comments never become nodes and block
scalars arrive folded — the two things a text scan gets wrong. Each expression
then goes to `jexlEngine.createExpression(...)`, the same call the platform
makes, and failures carry the engine's own message and column.

The `<+ ... >` closing delimiter is ambiguous, since `>` is also greater-than
and part of `=>` and `>=`. Instead of guessing, the extractor asks the parser:
of every candidate `>`, it keeps the **longest span the engine can parse**. That
resolves `<+a > 3>` to `a > 3` and `<+a.b> and <+c.d>` to `a.b`, because the
alternative span is not valid JEXL in each case.

## The four finding kinds

| Kind | Source | Trust |
|---|---|---|
| `SYNTAX` | `createExpression` threw | Authoritative — the shipped parser rejected it |
| `SCRIPT` | `createExpression` threw, `createScript` succeeded | Valid JEXL, but a statement (e.g. `var x = y`). Informational; does not affect exit code |
| `NESTED` | Expression contains an inner `<+ ... >` | No engine verdict is possible. See below |
| `WRAPPER` | No closing `>` | Structural, found by the extractor. The engine cannot catch this — the text inside an unterminated wrapper usually parses fine |
| `POLICY` | Pattern match | **Not an engine verdict.** See below |

### Why `NESTED` gets no verdict

`<+ ... >` nesting is Harness syntax, not JEXL syntax. Harness resolves the
inner expression and substitutes its **value** before the parser sees anything,
so the literal text is rejected by *every* engine — 3.0 included, verified here.
Reporting that as a 3.5 failure would be an artifact of this tool rather than a
real regression, so these are flagged and left unjudged. They are still the
documented nested-subscript risk and still need the inner expression quoted.

## What the engine cannot tell you

Two of the four restrictions Harness documents for SMP 0.43.0 are **not** parser
behaviour, verified against 3.5.0 on this machine:

- **Reflection** — `''.getClass()` evaluates to `class java.lang.String` even
  under `JexlPermissions.RESTRICTED`. Harness blocks it through its own sandbox
  configuration, which cannot be reproduced from outside.
- **Global assignment** — `stage.x = "Yes"` parses cleanly. It fails only
  against a read-only `JexlContext`, again a host configuration choice.

Both are therefore reported as `POLICY` — greps against the documented rules,
with a grep's false-positive rate. The two restrictions the engine *does* catch
are nested subscripts and `?[`, both genuine grammar errors.

Treat `POLICY` assignment findings as logic review, not syntax fixes: under
JEXL 3.0 a `when` condition using `=` evaluated true on every run, so correcting
it to `==` can stop a stage that has been firing unconditionally.

## Checking a different engine version

`commons.jexl.version` in `pom.xml` is the single source of truth — it drives
both the dependency and the version the tool prints, so they cannot disagree.
Judge the same file with the old parser:

```bash
mvn -q clean package -Dcommons.jexl.version=3.0
```

Use `clean`. Without it Maven's incremental compiler silently reuses classes
built against the other version, and the jar fails at runtime instead of at
build time.

The engine is deliberately built with a bare `JexlBuilder` — no permissions or
feature configuration. This tool only compiles, never evaluates, and sandbox
settings apply at evaluation; configuring them would buy nothing and would pin
the build to JEXL 3.3+, where `JexlPermissions` was introduced.

Running both versions over the documented failure cases gives:

| Case | 3.0 | 3.5 |
|---|---|---|
| `?[` after ternary | parses | **parse error at the `:`** |
| reflection | parses | parses (blocked by host sandbox, not the parser) |
| `=` assignment | parses | parses (blocked by host context, not the parser) |
| nested `<+ >` | not judgeable | not judgeable |

Only the first row is a parser-level regression, and the 3.5 error landing on
the `:` confirms the documented mechanism: `?[""]` is consumed as null-safe
array access, orphaning the ternary's `:`.
