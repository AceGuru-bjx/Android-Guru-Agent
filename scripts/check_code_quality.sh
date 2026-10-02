#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# check_code_quality.sh — targeted anti-pattern gates
# ═══════════════════════════════════════════════════════════════════════════
#
# Three targeted checks (NOT a full linter — Gradle handles compilation and
# tests; this catches the anti-patterns that compile fine but rot code):
#
#  1. GATE  `javaClass.getMethod(...)` in main sources — reflective dispatch
#     on an object you already hold a typed reference to. Legitimate uses of
#     reflection (optional runtime deps via Class.forName, hidden Android
#     APIs) are NOT flagged; this pattern is — it hides type errors until
#     runtime and breaks silently on rename. Fix: define an interface and
#     do a type-safe `as?` cast (see ConfirmationSink for the pattern).
#
#  2. GATE  `printStackTrace()` in main sources — stdout stack traces are
#     invisible in production (no logcat routing, no tag). Use the
#     structured logger (AppLogger in core, android.util.Log in app).
#
#  3. AUDIT empty catch blocks — reported (not gated): swallowing errors
#     silently is sometimes correct (best-effort logging) but should be
#     visible in review.
#
# Scope: ALL main sources in the repo (find-based, module-enumeration-free).
# The old hardcoded module list (core/* app platform/* terminal-emulator)
# left plugins/, plugin-sdk/, terminal-view and terminal-native outside the
# gates — the workflow plugin's fake-success stubs lived exactly in that
# blind spot. Any new module is now covered automatically.
#
# Usage: ./scripts/check_code_quality.sh  (from repo root; exit 0 = pass, 1 = fail)
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

SUMMARY_FILE="${GITHUB_STEP_SUMMARY:-/dev/null}"
FAIL=0

# ── Whole-repo main-source manifest (print0: safe for any path shape) ──────
MAIN_KT_LIST=$(find . -path "*/src/main/*" -name "*.kt" -not -path "*/build/*" -not -path "./.git/*" -print0 2>/dev/null | xargs -0 -r printf '%s\n' || true)

# ── Gate 1: reflective dispatch on held references (all main sources) ──────
# grep -v ':[0-9]*: *\*' skips KDoc/block-comment continuation lines (docs may
# legitimately SHOW the anti-pattern, e.g. ConfirmationSink.kt's rationale)
REFLECT_HITS=""
if [ -n "$MAIN_KT_LIST" ]; then
    REFLECT_HITS=$(printf '%s\n' "$MAIN_KT_LIST" \
        | xargs grep -n "javaClass\.getMethod" 2>/dev/null \
        | grep -v ':[0-9]*: *\*' || true)
fi

if [ -n "$REFLECT_HITS" ]; then
    echo "❌ GATE 1 — reflective method dispatch on held references (use a typed interface instead):"
    echo "$REFLECT_HITS"
    echo ""
    echo "   Pattern fix: extract the needed methods into an interface, have the"
    echo "   target implement it, then 'target as? MyInterface' (see ConfirmationSink.kt)."
    FAIL=1
else
    echo "✅ GATE 1 — no 'javaClass.getMethod' reflective dispatch in main sources"
fi

# ── Gate 2: printStackTrace in main sources ────────────────────────────────
STACK_HITS=""
if [ -n "$MAIN_KT_LIST" ]; then
    STACK_HITS=$(printf '%s\n' "$MAIN_KT_LIST" \
        | xargs grep -n "printStackTrace" 2>/dev/null || true)
fi

if [ -n "$STACK_HITS" ]; then
    echo "❌ GATE 2 — printStackTrace() in main sources (route through the logger instead):"
    echo "$STACK_HITS"
    FAIL=1
else
    echo "✅ GATE 2 — no printStackTrace() in main sources"
fi

# ── Audit: empty catch blocks (report-only) ────────────────────────────────
EMPTY_CATCH_COUNT=0
if [ -n "$MAIN_KT_LIST" ]; then
    EMPTY_CATCH_COUNT=$(printf '%s\n' "$MAIN_KT_LIST" \
        | xargs grep -nE 'catch \([a-zA-Z. :]+\) \{ *\}' 2>/dev/null \
        | wc -l || true)
fi

TODO_COUNT=0
if [ -n "$MAIN_KT_LIST" ]; then
    TODO_COUNT=$(printf '%s\n' "$MAIN_KT_LIST" \
        | xargs grep -n "TODO\|FIXME\|XXX" 2>/dev/null \
        | wc -l || true)
fi

echo "📋 AUDIT — empty catch blocks in main sources: $EMPTY_CATCH_COUNT (review-only, not gated)"
echo "📋 AUDIT — TODO/FIXME/XXX markers in main sources: $TODO_COUNT (review-only, not gated)"

{
    echo ""
    echo "## 🧹 Code-quality audit"
    echo ""
    echo "| Check | Result |"
    echo "|-------|--------|"
    echo "| Reflective dispatch (\`javaClass.getMethod\`) | $([ -z "$REFLECT_HITS" ] && echo '✅ none' || echo '❌ found') |"
    echo "| \`printStackTrace()\` in main sources | $([ -z "$STACK_HITS" ] && echo '✅ none' || echo '❌ found') |"
    echo "| Empty catch blocks (audit-only) | $EMPTY_CATCH_COUNT |"
    echo "| TODO/FIXME markers (audit-only) | $TODO_COUNT |"
} >> "$SUMMARY_FILE" 2>/dev/null || true

exit $FAIL
