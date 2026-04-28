#!/usr/bin/env bash
# Blocks read-side access to .tickets/ to enforce use of the knot CLI.
# Write/Edit are intentionally NOT blocked — direct edits remain a fallback
# for ticket-body shapes the CLI doesn't yet support.

set -euo pipefail

input=$(cat)
tool=$(jq -r '.tool_name // ""' <<<"$input")

blocked=0
case "$tool" in
  Read)
    p=$(jq -r '.tool_input.file_path // ""' <<<"$input")
    [[ "$p" == *"/.tickets/"* || "$p" == .tickets/* ]] && blocked=1
    ;;
  Glob)
    pat=$(jq -r '.tool_input.pattern // ""' <<<"$input")
    pth=$(jq -r '.tool_input.path // ""' <<<"$input")
    [[ "$pat" == *".tickets"* || "$pth" == *".tickets"* ]] && blocked=1
    ;;
  Grep)
    pth=$(jq -r '.tool_input.path // ""' <<<"$input")
    glb=$(jq -r '.tool_input.glob // ""' <<<"$input")
    [[ "$pth" == *".tickets"* || "$glb" == *".tickets"* ]] && blocked=1
    ;;
  Bash)
    cmd=$(jq -r '.tool_input.command // ""' <<<"$input")
    if [[ "$cmd" == *".tickets"* ]] && \
       [[ "$cmd" =~ (^|[[:space:]\;\|\&\(\`])(cat|bat|less|more|head|tail|grep|rg|ag|egrep|fgrep|ls|find|fd|tree|wc|awk|sed|file|diff|xxd|od)([[:space:]]|$) ]]; then
      blocked=1
    fi
    ;;
esac

if (( blocked == 1 )); then
  jq -n '{
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: "deny",
      permissionDecisionReason: "Read-side access to .tickets/ is blocked here. Use the knot CLI: `bb knot show <id>` for a ticket, `bb knot ls` to list, `bb knot ready` for ready work. (Write/Edit remain allowed for CLI-bug fallback.)"
    }
  }'
fi
