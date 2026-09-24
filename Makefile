GRADLE_IMAGE := gradle:9.1.0-jdk25

# How to run Gradle. Default: the wrapper inside a throw-away JDK 25 container, so nothing but
# Docker is needed on the host. Set GRADLE=./gradlew to use a local JDK 25 instead (the dev
# container does this for you).
GRADLE ?= docker run --rm -v "$(CURDIR):/workspace" -v moonsama-gradle-cache:/home/gradle/.gradle -w /workspace $(GRADLE_IMAGE) ./gradlew

.PHONY: wrapper refresh-paper build test up up-latest down logs clean

wrapper:
	$(GRADLE) wrapper --gradle-version 9.1.0

refresh-paper:
	python3 scripts/refresh-paper.py

build:
	$(GRADLE) build

test:
	$(GRADLE) test --rerun

up: build
	test -f .env || (echo "Copy .env.example to .env and add sandbox credentials first."; exit 1)
	docker compose up --build

up-latest: refresh-paper
	$(MAKE) up

down:
	docker compose down

logs:
	docker compose logs --follow paper

clean:
	$(GRADLE) clean
