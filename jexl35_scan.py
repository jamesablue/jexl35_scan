#!/usr/bin/env python3
"""
jexl35_scan.py - Scan Harness YAML for expressions that break under JEXL 3.5.

Checks the four restrictions documented for Harness SMP 0.43.0:
  R1  reflection-based expressions        (blocked outright)
  R2  nested subscript expressions        (quote the inner expression)
  R3  global variable assignment via '='  (use '==' or 'var')
  R4  ternary immediately followed by '[' (add a space or parentheses)

Usage:
    python3 jexl35_scan.py <dir> [<dir> ...] [--json] [--quiet]

Exit codes: 0 = clean, 1 = findings, 2 = bad invocation.
"""

import argparse
import json
import os
import re
import sys

YAML_EXT = (".yaml", ".yml")

# ---------------------------------------------------------------- R1 patterns
REFLECTION = re.compile(
    r"\b("
    r"getClass|forName|newInstance|getDeclared\w*|getMethod\w*|getField\w*|"
    r"getConstructor\w*|getSuperclass|getResource\w*|getClassLoader|"
    r"java\.lang\.Runtime|ProcessBuilder|System\s*\.\s*(getenv|getProperty|exit)"
    r")\b"
)

# Operators where '=' is legitimate and must NOT be flagged as assignment.
EQ_PREFIX = set("!<>=+-*/%^$~&|")


def find_expressions(text):
    """Yield (start_offset, body) for each <+ ... > expression.

    Depth-counts '<+' openers against '>' closers so nested expressions are
    returned as one outer unit. Unbalanced expressions are yielded to EOL so
    they still get checked rather than silently skipped.
    """
    i, n = 0, len(text)
    while i < n:
        if text.startswith("<+", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if text.startswith("<+", j):
                    depth += 1
                    j += 2
                elif text[j] == ">":
                    depth -= 1
                    j += 1
                elif text[j] == "\n" and depth:
                    break
                else:
                    j += 1
            yield i, text[i:j]
            i = j
        else:
            i += 1


def strip_strings(body):
    """Return body with quoted-string contents blanked out (length preserved).

    Lets the bracket/assignment scanners ignore anything the parser treats as
    a literal - which is exactly what the documented R2 fix relies on.
    """
    out = list(body)
    quote = None
    for k, ch in enumerate(body):
        if quote:
            if ch == quote and body[k - 1] != "\\":
                quote = None
            else:
                out[k] = " "
        elif ch in "\"'":
            quote = ch
    return "".join(out)


def check_reflection(body):
    m = REFLECTION.search(body)
    if m:
        return f"reflection via '{m.group(1)}' - blocked in JEXL 3.5, no rewrite available"
    return None


def check_nested_subscript(body):
    """Flag '[' opened inside another '[', or a '<+' appearing unquoted inside '['."""
    bare = strip_strings(body)
    depth = 0
    k = 0
    while k < len(bare):
        ch = bare[k]
        if ch == "[":
            if depth:
                return "subscript nested inside another subscript - wrap the inner expression in double quotes"
            depth += 1
        elif ch == "]":
            depth = max(0, depth - 1)
        elif depth and bare.startswith("<+", k):
            return "unquoted expression inside a subscript - wrap it in double quotes"
        k += 1
    return None


def check_assignment(body):
    """Flag a bare '=' used as assignment (not ==, !=, >=, <=, =~, +=, ...)."""
    bare = strip_strings(body)
    for k, ch in enumerate(bare):
        if ch != "=":
            continue
        prev = bare[k - 1] if k else ""
        nxt = bare[k + 1] if k + 1 < len(bare) else ""
        if prev in EQ_PREFIX or nxt in ("=", "~"):
            continue
        return "single '=' is a global assignment in JEXL - use '==' to compare, or 'var' to declare"
    return None


def check_ternary_bracket(body):
    if "?[" in strip_strings(body):
        return "'?[' parses as null-safe array access - add a space after '?' or parenthesise the array"
    return None


CHECKS = [
    ("R1", "reflection", check_reflection),
    ("R2", "nested-subscript", check_nested_subscript),
    ("R3", "global-assignment", check_assignment),
    ("R4", "ternary-bracket", check_ternary_bracket),
]

RULE_NAMES = {"R1": "reflection", "R2": "nested-subscript",
              "R3": "global-assignment", "R4": "ternary-bracket",
              "R5": "bare-assignment"}

# 'when:' conditions and failure strategies may hold raw JEXL with no <+ > wrapper.
BARE_JEXL_KEY = re.compile(r"^\s*(condition|expression)\s*:\s*(.+?)\s*$")

# R5 (opt-in): NAME=<+expr> outside any wrapper - the documented
# 'ENVIRONMENT=<+env.identifier>' assignment form. Skipped when already
# prefixed with 'var'. Noisy by design: plain shell 'FOO=bar' in a Run step's
# command is valid and unaffected, so this is off unless you ask for it.
BARE_ASSIGN = re.compile(r"(?<!\w)(?<!var\s)([A-Za-z_]\w*)\s*=\s*<\+")


def scan_bare_assign(text, path):
    out = []
    for idx, raw in enumerate(text.splitlines(), start=1):
        for m in BARE_ASSIGN.finditer(raw):
            # '==' and friends are comparisons, not assignments.
            if raw[m.end(1):m.end(1) + 2].lstrip().startswith("=="):
                continue
            out.append({
                "file": path, "line": idx, "rule": "R5",
                "check": "bare-assignment", "snippet": raw.strip(),
                "detail": f"'{m.group(1)}=' assigns a global - prefix with 'var' "
                          f"(review: harmless if this is shell syntax in a command block)",
            })
    return out


def scan_text(text, path):
    findings = []
    line_starts = [0]
    for k, ch in enumerate(text):
        if ch == "\n":
            line_starts.append(k + 1)

    def line_of(offset):
        lo, hi = 0, len(line_starts) - 1
        while lo < hi:
            mid = (lo + hi + 1) // 2
            if line_starts[mid] <= offset:
                lo = mid
            else:
                hi = mid - 1
        return lo + 1

    seen = set()

    # Pass 1: wrapped <+ ... > expressions.
    for offset, body in find_expressions(text):
        for rule, name, fn in CHECKS:
            msg = fn(body)
            if msg:
                key = (line_of(offset), rule, body)
                if key not in seen:
                    seen.add(key)
                    findings.append({
                        "file": path, "line": line_of(offset), "rule": rule,
                        "check": name, "snippet": body.strip(), "detail": msg,
                    })

    # Pass 2: bare condition:/expression: values with no wrapper.
    for idx, raw in enumerate(text.splitlines(), start=1):
        m = BARE_JEXL_KEY.match(raw)
        if not m:
            continue
        val = m.group(2).strip().strip("\"'")
        if not val or val.startswith("<+"):
            continue  # already covered by pass 1
        for rule, name, fn in CHECKS:
            msg = fn(val)
            if msg:
                key = (idx, rule, val)
                if key not in seen:
                    seen.add(key)
                    findings.append({
                        "file": path, "line": idx, "rule": rule,
                        "check": name, "snippet": raw.strip(), "detail": msg,
                    })
    return findings


def scan_paths(roots, bare_assign=False):
    findings, scanned = [], 0
    for root in roots:
        if os.path.isfile(root):
            files = [root]
        else:
            files = []
            for dirpath, dirnames, filenames in os.walk(root):
                dirnames[:] = [d for d in dirnames if d not in
                               (".git", "node_modules", ".terraform", "vendor")]
                files += [os.path.join(dirpath, f) for f in filenames
                          if f.lower().endswith(YAML_EXT)]
        for path in sorted(files):
            try:
                with open(path, "r", encoding="utf-8", errors="replace") as fh:
                    text = fh.read()
            except OSError as exc:
                print(f"warn: cannot read {path}: {exc}", file=sys.stderr)
                continue
            scanned += 1
            findings += scan_text(text, path)
            if bare_assign:
                findings += scan_bare_assign(text, path)
    return findings, scanned


SEVERITY = {
    "R1": "BLOCKER  ",
    "R2": "BREAKING ",
    "R3": "BREAKING*",
    "R4": "BREAKING ",
    "R5": "REVIEW   ",
}


def main():
    ap = argparse.ArgumentParser(description="Scan Harness YAML for JEXL 3.5 breaking changes.")
    ap.add_argument("paths", nargs="+", help="directories or files to scan")
    ap.add_argument("--json", action="store_true", help="emit JSON instead of a report")
    ap.add_argument("--quiet", action="store_true", help="suppress the summary footer")
    ap.add_argument("--bare-assign", action="store_true",
                    help="also flag NAME=<+expr> outside <+ > wrappers (R5; noisy)")
    args = ap.parse_args()

    findings, scanned = scan_paths(args.paths, bare_assign=args.bare_assign)

    if args.json:
        json.dump({"scanned": scanned, "findings": findings}, sys.stdout, indent=2)
        sys.stdout.write("\n")
        return 1 if findings else 0

    if findings:
        current = None
        for f in sorted(findings, key=lambda x: (x["file"], x["line"], x["rule"])):
            if f["file"] != current:
                current = f["file"]
                print(f"\n{current}")
            print(f"  {SEVERITY[f['rule']]} {f['rule']} line {f['line']}: {f['check']}")
            print(f"      {f['snippet'][:160]}")
            print(f"      -> {f['detail']}")

    if not args.quiet:
        rules = [r for r, _, _ in CHECKS] + (["R5"] if args.bare_assign else [])
        counts = {r: sum(1 for f in findings if f["rule"] == r) for r in rules}
        print(f"\n{'-' * 68}")
        print(f"Scanned {scanned} YAML file(s). {len(findings)} finding(s).")
        for rule in rules:
            print(f"  {rule} {RULE_NAMES[rule]:<20} {counts[rule]}")
        if not args.bare_assign:
            print("  (re-run with --bare-assign to also catch NAME=<+expr> forms)")
        if counts["R3"] or counts.get("R5"):
            print("\n  * R3 needs logic review, not just a syntax fix. Under JEXL 3.0 a")
            print("    'when' condition using '=' evaluated true every run. Changing it")
            print("    to '==' may stop a stage that has been running unconditionally.")
        print("\n  Templates hold most of the risk - scan your template repo, not just")
        print("  pipelines. Also check triggers, input sets, and OPA policies.")

    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
