#!/usr/bin/env bash
# PreToolUse hook (Write|Edit und Bash Matcher).
#
# Zweck: Schreibzugriffe auf typische Secret-/Zugangsdaten-Dateien nicht
# stillschweigend durchlaufen lassen (CLAUDE.md: "Keine Secrets im Code oder
# in Git").
#
# Geschützte Dateien (per Basename-Muster, unabhängig vom Pfad):
#   local.properties, .env, *.keystore, *.jks, google-services.json
#
# Wirkung: Bei Treffer erzwingt der Hook eine Rückfrage (permissionDecision
# "ask") – unabhängig vom aktuellen Permission-Mode. Kein Treffer -> kein
# Output, normale Permission-Prüfung greift wie gewohnt.
# Nur Schreibzugriffe werden geprüft: Write/Edit immer, bei Bash nur
# Kommandos mit erkennbarem Schreib-Indikator (>, >>, tee, cp, mv, dd,
# sed -i, touch). Reines Lesen (cat, grep, less, ...) wird nicht angefasst.
set -euo pipefail

input="$(cat)"
tool="$(printf '%s' "$input" | jq -r '.tool_name // empty')"

sensitive_re='(^|/)(local\.properties|\.env|[^/]*\.keystore|[^/]*\.jks|google-services\.json)$'

ask() {
  jq -n --arg reason "$1" '{
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: "ask",
      permissionDecisionReason: $reason
    }
  }'
}

case "$tool" in
  Write|Edit)
    path="$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty')"
    if [ -n "$path" ] && printf '%s' "$path" | grep -qE "$sensitive_re"; then
      ask "Schreibzugriff auf sensible Datei ($path) erkannt. Bitte ausdrücklich bestätigen (Secrets-/Zugangsdaten-Schutz)."
    fi
    ;;
  Bash)
    cmd="$(printf '%s' "$input" | jq -r '.tool_input.command // empty')"
    if [ -n "$cmd" ] \
      && printf '%s' "$cmd" | grep -qE '(local\.properties|(^|[^a-zA-Z0-9_])\.env([^a-zA-Z0-9_]|$)|\.keystore|\.jks|google-services\.json)' \
      && printf '%s' "$cmd" | grep -qE '(>>?|(^|[;&|[:space:]])tee([[:space:]]|$)|(^|[;&|[:space:]])cp([[:space:]]|$)|(^|[;&|[:space:]])mv([[:space:]]|$)|(^|[;&|[:space:]])dd([[:space:]]|$)|sed[[:space:]]+-i|(^|[;&|[:space:]])touch([[:space:]]|$))'; then
      ask "Schreibender Bash-Befehl auf sensible Datei erkannt. Bitte ausdrücklich bestätigen (Secrets-/Zugangsdaten-Schutz)."
    fi
    ;;
esac

exit 0
