GRADLE_IMAGE := gradle:9.1.0-jdk25

.PHONY: wrapper refresh-paper build up up-latest down logs clean

wrapper:
	docker run --rm -v "$(CURDIR):/workspace" -v moonsama-gradle-cache:/home/gradle/.gradle -w /workspace $(GRADLE_IMAGE) \
		gradle wrapper --gradle-version 9.1.0

refresh-paper:
	python3 scripts/refresh-paper.py

build:
	docker run --rm -v "$(CURDIR):/workspace" -v moonsama-gradle-cache:/home/gradle/.gradle -w /workspace $(GRADLE_IMAGE) \
		./gradlew build

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
	docker run --rm -v "$(CURDIR):/workspace" -v moonsama-gradle-cache:/home/gradle/.gradle -w /workspace $(GRADLE_IMAGE) \
		./gradlew clean
