#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path


EXPECTED_BUILD = '''plugins {
    kotlin("jvm") version "2.4.10"
    id("org.springframework.boot") version "4.1.0"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("me.ahoo.wow:wow-spring-boot-starter:8.9.6")
}

kotlin {
    jvmToolchain(17)
}
'''

EXPECTED_CONFIG = '''wow:
  eventsourcing:
    store:
      storage: mongo
    snapshot:
      storage: mongo

spring:
  data:
    mongodb:
      database: wow-v6-fixture
'''


def main() -> None:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    build_file = root / "build.gradle.kts"
    config_file = root / "src/main/resources/application.yml"
    try:
        actual_build = build_file.read_text(encoding="utf-8")
        actual_config = config_file.read_text(encoding="utf-8")
    except OSError as error:
        print(f"Local platform oracle could not read fixture: {error}", file=sys.stderr)
        raise SystemExit(1) from error
    if actual_build != EXPECTED_BUILD:
        print(
            "build.gradle.kts must equal the exact released v8.9.6 platform contract; "
            "comments, strings, duplicates, and unrelated rewrites are rejected.",
            file=sys.stderr,
        )
        raise SystemExit(1)
    if actual_config != EXPECTED_CONFIG:
        print(
            "application.yml changed; the v6.21.5 store/snapshot keys must remain exact.",
            file=sys.stderr,
        )
        raise SystemExit(1)
    print("Local v6.21.5 to v8.9.6 platform oracle passed.")


if __name__ == "__main__":
    main()
