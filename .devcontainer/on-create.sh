#!/usr/bin/env bash
# Runs once when the dev container is created, before the editor connects.
set -euo pipefail

# Named volumes (see devcontainer.json) are created root-owned on first use. Hand them to the
# workspace user before anything else starts, or Gradle cannot write its lock files and
# Claude Code cannot save its settings.
for dir in "${GRADLE_USER_HOME:-$HOME/.gradle}" "${CLAUDE_CONFIG_DIR:-$HOME/.claude}"; do
  if [ ! -w "$dir" ]; then
    echo "==> Taking ownership of $dir"
    sudo mkdir -p "$dir"
    sudo chown -R "$(id -u):$(id -g)" "$dir"
  fi
done
