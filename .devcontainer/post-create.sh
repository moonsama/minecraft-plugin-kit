#!/usr/bin/env bash
# Runs once after the dev container is created. Warms the Gradle cache so the first
# `make build` / IDE import is fast, and prints the next steps.
set -euo pipefail

cd "$(dirname "$0")/.."

# The Gradle cache is a named volume (see devcontainer.json). Docker creates it root-owned on
# first use; hand it to the workspace user or the wrapper cannot write its lock files.
GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
if [ ! -w "$GRADLE_HOME" ]; then
  echo "==> Taking ownership of $GRADLE_HOME"
  sudo chown -R "$(id -u):$(id -g)" "$GRADLE_HOME"
fi

echo "==> Java"
java -version

echo "==> Docker (in-container daemon)"
docker version --format 'client {{.Client.Version}} / server {{.Server.Version}}' || echo "docker daemon not ready yet; it starts with the container"

echo "==> Gradle: downloading the wrapper distribution and dependencies"
./gradlew build -x test --console=plain -q

if [ ! -f .env ]; then
  cat <<'EOF'

==> No .env yet.
    cp .env.example .env
    then paste your Portal sandbox credentials (see docs/local-development.md).
EOF
fi

cat <<'EOF'

==> Ready. Common commands:
    make build      build all plugins + resource pack (runs unit tests)
    make up         build, then start the Paper 26.3 dev server (ports 25565 / 8080)
    make logs       follow the server log
    make down       stop the server
EOF
