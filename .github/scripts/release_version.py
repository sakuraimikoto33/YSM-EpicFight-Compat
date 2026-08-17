#!/usr/bin/env python3
"""Select a Minecraft-specific release using strict SemVer precedence."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from functools import total_ordering
import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Iterable
from urllib.parse import quote


SEMVER_PATTERN = re.compile(
    r"^(?P<major>0|[1-9][0-9]*)\."
    r"(?P<minor>0|[1-9][0-9]*)\."
    r"(?P<patch>0|[1-9][0-9]*)"
    r"(?:-(?P<prerelease>[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"(?:\+(?P<build>[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)
MINECRAFT_VERSION_PATTERN = re.compile(r"^[0-9A-Za-z._-]+$")


@total_ordering
@dataclass(frozen=True, eq=False)
class SemVer:
    major: int
    minor: int
    patch: int
    prerelease: tuple[int | str, ...] | None
    build: str | None
    source: str

    @classmethod
    def parse(cls, value: str) -> "SemVer":
        match = SEMVER_PATTERN.fullmatch(value)
        if match is None:
            raise ValueError(f"not a strict SemVer: {value}")

        prerelease_text = match.group("prerelease")
        prerelease: tuple[int | str, ...] | None = None
        if prerelease_text is not None:
            identifiers: list[int | str] = []
            for identifier in prerelease_text.split("."):
                if identifier.isdigit():
                    if len(identifier) > 1 and identifier.startswith("0"):
                        raise ValueError(
                            f"numeric prerelease identifier has a leading zero: {value}"
                        )
                    identifiers.append(int(identifier))
                else:
                    identifiers.append(identifier)
            prerelease = tuple(identifiers)

        return cls(
            major=int(match.group("major")),
            minor=int(match.group("minor")),
            patch=int(match.group("patch")),
            prerelease=prerelease,
            build=match.group("build"),
            source=value,
        )

    @property
    def is_prerelease(self) -> bool:
        return self.prerelease is not None

    def compare(self, other: "SemVer") -> int:
        own_core = (self.major, self.minor, self.patch)
        other_core = (other.major, other.minor, other.patch)
        if own_core != other_core:
            return -1 if own_core < other_core else 1

        if self.prerelease is None or other.prerelease is None:
            if self.prerelease is other.prerelease:
                return 0
            return 1 if self.prerelease is None else -1

        for own_identifier, other_identifier in zip(
            self.prerelease, other.prerelease
        ):
            if own_identifier == other_identifier:
                continue
            own_numeric = isinstance(own_identifier, int)
            other_numeric = isinstance(other_identifier, int)
            if own_numeric and not other_numeric:
                return -1
            if not own_numeric and other_numeric:
                return 1
            return -1 if own_identifier < other_identifier else 1

        if len(self.prerelease) == len(other.prerelease):
            return 0
        return -1 if len(self.prerelease) < len(other.prerelease) else 1

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, SemVer):
            return NotImplemented
        return self.compare(other) == 0

    def __lt__(self, other: "SemVer") -> bool:
        if not isinstance(other, SemVer):
            return NotImplemented
        return self.compare(other) < 0


@dataclass(frozen=True)
class TaggedVersion:
    tag: str
    version: SemVer


@dataclass(frozen=True)
class ReleaseDecision:
    mod_version: str
    minecraft_version: str
    tag: str
    previous_tag: str | None
    should_release: bool
    prerelease: bool
    ignored_tags: tuple[str, ...]


def read_gradle_property(path: Path, key: str) -> str:
    values: list[str] = []
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        candidate_key, candidate_value = line.split("=", 1)
        if candidate_key.strip() == key:
            value = candidate_value.strip()
            if value:
                values.append(value)

    if len(values) != 1:
        raise ValueError(f"{path} must define {key} exactly once")
    return values[0]


def find_previous_release(
    tags: Iterable[str], minecraft_version: str
) -> tuple[TaggedVersion | None, tuple[str, ...]]:
    suffix = f"-mc{minecraft_version}"
    previous: TaggedVersion | None = None
    ignored: list[str] = []

    for tag in tags:
        if not tag.endswith(suffix):
            continue
        version_text = tag[: -len(suffix)]
        try:
            version = SemVer.parse(version_text)
        except ValueError:
            ignored.append(tag)
            continue

        candidate = TaggedVersion(tag=tag, version=version)
        if previous is None or previous.version < candidate.version:
            previous = candidate
        elif previous.version == candidate.version and previous.tag < candidate.tag:
            previous = candidate

    return previous, tuple(sorted(ignored))


def decide_release(
    mod_version: str, minecraft_version: str, tags: Iterable[str]
) -> ReleaseDecision:
    version = SemVer.parse(mod_version)
    if not MINECRAFT_VERSION_PATTERN.fullmatch(minecraft_version):
        raise ValueError(f"invalid minecraft_version: {minecraft_version}")

    tag = f"{mod_version}-mc{minecraft_version}"
    previous, ignored = find_previous_release(tags, minecraft_version)
    should_release = previous is None or previous.version < version
    return ReleaseDecision(
        mod_version=mod_version,
        minecraft_version=minecraft_version,
        tag=tag,
        previous_tag=None if previous is None else previous.tag,
        should_release=should_release,
        prerelease=version.is_prerelease,
        ignored_tags=ignored,
    )


def history_url(repository: str, target: str, previous_tag: str | None) -> str:
    encoded_target = quote(target, safe="")
    if previous_tag is None:
        return f"https://github.com/{repository}/commits/{encoded_target}"
    encoded_previous = quote(previous_tag, safe="")
    return (
        f"https://github.com/{repository}/compare/"
        f"{encoded_previous}...{encoded_target}"
    )


def git_tags() -> list[str]:
    result = subprocess.run(
        ["git", "tag", "--list"],
        check=True,
        capture_output=True,
        text=True,
    )
    return [line for line in result.stdout.splitlines() if line]


def write_github_output(path: Path, values: dict[str, str]) -> None:
    with path.open("a", encoding="utf-8", newline="\n") as output:
        for key, value in values.items():
            if "\n" in value or "\r" in value:
                raise ValueError(f"multiline GitHub output is not supported: {key}")
            output.write(f"{key}={value}\n")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--properties", type=Path, default=Path("gradle.properties"))
    parser.add_argument("--github-output", type=Path, required=True)
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--target", default=os.environ.get("GITHUB_SHA"))
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if not args.repository or not args.target:
            raise ValueError("repository and target are required")

        mod_version = read_gradle_property(args.properties, "mod_version")
        minecraft_version = read_gradle_property(args.properties, "minecraft_version")
        decision = decide_release(mod_version, minecraft_version, git_tags())

        for ignored_tag in decision.ignored_tags:
            print(
                f"::warning::Ignoring non-SemVer release tag for this Minecraft version: {ignored_tag}",
                file=sys.stderr,
            )

        previous = decision.previous_tag or ""
        output_values = {
            "mod_version": decision.mod_version,
            "minecraft_version": decision.minecraft_version,
            "tag": decision.tag,
            "previous_tag": previous,
            "should_release": str(decision.should_release).lower(),
            "prerelease": str(decision.prerelease).lower(),
            "history_url": history_url(
                args.repository, args.target, decision.previous_tag
            ),
        }
        write_github_output(args.github_output, output_values)

        if decision.should_release:
            print(
                f"Release selected: {decision.tag}"
                + (f" (previous: {previous})" if previous else " (first release)")
            )
        else:
            print(
                f"Release skipped: {decision.mod_version} is not newer than {previous}"
            )
        return 0
    except (OSError, subprocess.CalledProcessError, ValueError) as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
