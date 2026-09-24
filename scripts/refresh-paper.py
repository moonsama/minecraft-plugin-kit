#!/usr/bin/env python3

import json
import pathlib
import re
import urllib.request


ROOT = pathlib.Path(__file__).resolve().parents[1]
MINECRAFT_VERSION = "26.3"
BUILDS_URL = (
    "https://fill.papermc.io/v3/projects/paper/versions/"
    f"{MINECRAFT_VERSION}/builds"
)


def fetch_latest() -> dict:
    request = urllib.request.Request(
        BUILDS_URL,
        headers={"User-Agent": "moonsama-minecraft-plugin-kit/0.1"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        builds = json.load(response)
    if not builds:
        raise RuntimeError(f"Paper has no builds for Minecraft {MINECRAFT_VERSION}")
    return max(builds, key=lambda build: int(build["id"]))


def replace(path: pathlib.Path, pattern: str, replacement: str) -> None:
    original = path.read_text()
    updated, count = re.subn(pattern, replacement, original, flags=re.MULTILINE)
    if count != 1:
        raise RuntimeError(f"Expected one match in {path}, found {count}")
    path.write_text(updated)


def main() -> None:
    build = fetch_latest()
    build_number = int(build["id"])
    channel = str(build["channel"]).lower()
    download = build["downloads"]["server:default"]
    paper_url = download["url"]
    sha256 = download["checksums"]["sha256"]

    gradle_properties = ROOT / "gradle.properties"
    replace(
        gradle_properties,
        r"^paperApiVersion=.*$",
        f"paperApiVersion={MINECRAFT_VERSION}.build.{build_number}-{channel}",
    )
    replace(gradle_properties, r"^paperBuild=.*$", f"paperBuild={build_number}")
    replace(
        gradle_properties,
        r"^paperServerSha256=.*$",
        f"paperServerSha256={sha256}",
    )

    compose = ROOT / "compose.yaml"
    replace(compose, r"^(\s*PAPER_URL:).*$", rf"\1 {paper_url}")
    replace(compose, r"^(\s*PAPER_SHA256:).*$", rf"\1 {sha256}")

    print(
        f"Pinned Paper {MINECRAFT_VERSION} build {build_number} "
        f"({channel}, sha256 {sha256})."
    )


if __name__ == "__main__":
    main()
