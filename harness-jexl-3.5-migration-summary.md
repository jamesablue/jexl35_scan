# Harness SMP — JEXL 3.0 → 3.5 Migration Summary

**Source:** developer.harness.io/release-notes/self-managed-enterprise-edition
**Applies from:** SMP 0.43.0 (released July 3, 2026)
**Nature of change:** Platform-side. Harness upgrades the JEXL engine; you fix your expressions.

---

## What changed

Harness upgraded JEXL from 3.0 to 3.5 to improve platform security. This introduces
stricter validation on JEXL expressions. Four categories of expression will now fail.

### 1. Reflection-based expressions are blocked

Any expression using reflection to reach classes, methods, or fields is rejected outright.

Fails:
```
<+''.getClass().forName("java.lang.Runtime")>
```

No rewrite offered — these are simply not permitted.

### 2. Nested subscript expressions must be rewritten

A subscript (square bracket) accessor nested directly inside another subscript fails.

Fails:
```
<+pipeline.variables[<+stage.variables['test']>]>
```

Rewrite — wrap the inner expression in double quotes:
```
<+pipeline.variables["<+stage.variables['test']>"]>
```

### 3. Global variable assignments are disallowed

In JEXL, a single `=` is assignment, not equality. JEXL 3.5 rejects global assignments.

Fails:
```
ENVIRONMENT=<+env.identifier>,REGION=<+pipeline.variables.region>
```

Rewrite using local variables (`var` prefix):
```
var ENVIRONMENT=<+env.identifier>,var REGION=<+pipeline.variables.region>
```

**This is the dangerous one.** It also affects `when` conditions:

```yaml
when:
  stageStatus: Success
  condition: <+stage.variables.shouldRun="Yes">    # WRONG
```

Under JEXL 3.0 this silently *always passed*. JEXL parsed it as a global assignment,
the expression evaluated to the assigned value ("Yes"), and the `when` condition coerced
that to boolean — true for any non-empty, non-null string, regardless of the variable's
actual value. Under 3.5 it fails with an error instead.

Fix — use `==` for an equality check:
```yaml
when:
  stageStatus: Success
  condition: <+stage.variables.shouldRun == "Yes">
```

Note the behavioural consequence: conditions that appeared to work may have been
firing unconditionally. Fixing them may change which stages actually run.

### 4. Ternary expressions followed immediately by `[`

JEXL 3.5 treats `?[` as the null-safe array access operator, so the parser consumes
`?[...]` as one token and the ternary fails.

Fails:
```
<+pipeline.variables.BUILD_ENVS=="dev"?[""]:"qa">
```

Fix A — add a space after `?`:
```
<+pipeline.variables.BUILD_ENVS=="dev"? [""]:"qa">
```

Fix B — wrap the array in parentheses:
```
<+pipeline.variables.BUILD_ENVS=="QAdf"?([""]):"abcds">
```

---

## Harness guidance

Monitor pipeline executions after upgrading and contact Harness Support if issues
arise or remediation assistance is needed.

---

## Related upgrade constraints on the path to 0.43.x

The JEXL change cannot be taken in isolation — reaching 0.43.0 may require passing
through mandatory intermediate versions.

**TimescaleDB → PostgreSQL (0.36.x) — mandatory stop**
- Migration runs automatically during upgrade to 0.36.x; no manual customer action during migration.
- You must be on 0.35.x before upgrading to 0.36.x.
- TimescaleDB is fully removed from 0.37.0 onward. **You cannot skip 0.36.x.**
- CRITICAL: do not re-run `helm upgrade` for 0.36.0 after a successful upgrade — re-running can
  disrupt the migration and cause data loss. Contact Support before further action if issues occur.

**PostgreSQL 16 (0.43.0)**
- 0.43.0 introduces PostgreSQL 16 support. If on PostgreSQL 14, follow the 14→16 upgrade guide.
- PostgreSQL 14 is deprecated and will be removed in a future release.

**MongoDB upgrade path**
- MongoDB 4.0 in ≤0.16.x; 5.0 in 0.17.x–0.21.x; 6.0 from 0.22.x; 7.0 from 0.33.x.
- From ≤0.16.x, upgrade to at least 0.17.0 first (no direct 4.0→6.0 path).
- From ≤0.21.x, upgrade to at least 0.22.0 first (no direct 5.0→7.0 path).
- Helm users get an automatic FCV upgrade job. **Argo CD users must run the FCV job manually
  before the main upgrade.**

**Bitnami repository move**
- Affects anyone pulling Bitnami images directly from docker.io on versions <0.32.0.
- Images moved to the `bitnamilegacy` repository; overrides must be updated for mongodb,
  postgresql, minio, clickhouse, timescaledb archive, and the mongoFCVUpgrade job.
- Private or air-gapped image sources are unaffected.

---

## Remediation checklist

- [ ] Grep all pipeline, stage, step, and template YAML for `=` inside `<+...>` — especially
      in `when.condition` and failure-strategy conditions.
- [ ] Grep for `?[` (ternary immediately followed by a bracket).
- [ ] Grep for `][` and `[<+` (nested subscripts).
- [ ] Grep for `getClass`, `forName`, `.class` (reflection).
- [ ] Check **templates separately** — pipelines that are thin template references hide their
      expressions in the template body, which is where the risk actually lives.
- [ ] Check triggers, input sets, and OPA policies — not just pipelines.
- [ ] Re-verify the *logic* of any `when` condition fixed from `=` to `==`; it may not have been
      evaluating the way anyone assumed.
- [ ] Confirm current SMP version and map the required intermediate hops (0.35.x → 0.36.x → 0.43.x).
