#!/bin/sh
# Installed by compliance-gate/bin/install-gate.
# Bypass for one commit: SPINE_GATE_DISABLE=1 git commit ...
# Uninstall: <spine>/.claude/skills/compliance-gate/bin/install-gate <project> --uninstall

SPINE_ROOT="${SPINE_ROOT:-__SPINE_ROOT__}"
GATE="$SPINE_ROOT/.claude/skills/compliance-gate/bin/compliance-gate"

if [ ! -x "$GATE" ]; then
  echo "compliance-gate: skip — $GATE not found" >&2
  exit 0
fi

"$GATE" --hook pre-commit --project-root "$(git rev-parse --show-toplevel)"
status=$?
# Exit code 3 = SPINE_GATE_DISABLE=1; treat as commit-allowed so the bypass works.
if [ "$status" -eq 3 ]; then
  exit 0
fi
exit "$status"
