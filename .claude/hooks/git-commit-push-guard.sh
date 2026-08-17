#!/usr/bin/env bash
# PreToolUse hook (Bash matcher).
#
# Zweck: git commit / git push nicht automatisch durchlaufen lassen.
# Setzt CLAUDE.md-Regel technisch durch:
#   "Nichts committen oder pushen, sofern nicht ausdrücklich verlangt."
#
# Wirkung: Erkennt der Hook ein git commit/push im Bash-Kommando, erzwingt er
# eine Rückfrage (permissionDecision "ask") – unabhängig vom aktuellen
# Permission-Mode (auch in acceptEdits/auto). Kein Treffer -> kein Output,
# normale Permission-Prüfung greift wie gewohnt.
set -euo pipefail

input="$(cat)"
cmd="$(printf '%s' "$input" | jq -r '.tool_input.command // empty')"

if [ -z "$cmd" ]; then
  exit 0
fi

if printf '%s' "$cmd" | grep -qE '(^|[;&|]|[[:space:]])git[[:space:]]+(commit|push)([[:space:]]|$)'; then
  jq -n '{
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: "ask",
      permissionDecisionReason: "git commit/push erkannt. Laut CLAUDE.md nur nach ausdrücklicher Anweisung ausführen – bitte bestätigen."
    }
  }'
fi

exit 0
