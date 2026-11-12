#!/usr/bin/env bash
# Runs once after the dev container is created and prints the next steps. It deliberately does
# not build: the Java extension imports the project (and downloads dependencies) at the same
# time, and running both at once is what used to make the container hang on first open.
set -euo pipefail

cd "$(dirname "$0")/.."

echo "==> Java"
java -version

echo "==> Docker (in-container daemon)"
docker version --format 'client {{.Client.Version}} / server {{.Server.Version}}' || echo "docker daemon not ready yet; it starts with the container"

echo "==> Gradle wrapper"
./gradlew --version --console=plain -q

if [ ! -f .env ]; then
  cat <<'EOF'

==> No .env yet.
    cp .env.example .env
    then paste your Portal sandbox credentials (see docs/local-development.md).
EOF
fi

cat <<'EOF'

==> Ready. Common commands:
    make build      build all plugins + resource pack (runs unit tests; the first run downloads dependencies)
    make up         build, then start the Paper 26.3 dev server (ports 25565 / 8080)
    make logs       follow the server log
    make down       stop the server
EOF
