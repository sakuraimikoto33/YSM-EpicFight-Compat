#!/usr/bin/env python3
"""Decide whether a pushed Minecraft branch needs a mod build."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import re
import subprocess
from typing import Iterable


OID_PATTERN = re.compile(r"(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})")
ZERO_OIDS = {"0" * 40, "0" * 64}

# These paths contain repository automation, documentation, or contributor
# guidance. Everything else is treated as build-relevant so that a newly added
# source or build directory cannot silently bypass CI.
NON_BUILD_FILES = {
    ".gitignore",
    "AGENTS.md",
    "LICENSE",
    "README.md",
    "README.ja.md",
}
NON_BUILD_PREFIXES = (
    ".agents/",
    ".github/",
    "assets/",
    "docs/",
)


@dataclass(frozen=True)
class ChangeDecision:
    mod_changed: bool
    commits_scanned: int
    relevant_paths: tuple[str, ...]
    reason: str


class GitInspectionError(RuntimeError):
    """Raised when a pushed range cannot be inspected reliably."""


def _run_git(repository: Path, *arguments: str) -> bytes:
    completed = subprocess.run(
        ["git", *arguments],
        cwd=repository,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if completed.returncode != 0:
        stderr = completed.stderr.decode("utf-8", errors="replace").strip()
        raise GitInspectionError(
            f"git {' '.join(arguments)} failed with exit code "
            f"{completed.returncode}: {stderr}"
        )
    return completed.stdout


def _validate_oid(value: str, label: str) -> str:
    if OID_PATTERN.fullmatch(value) is None:
        raise GitInspectionError(f"{label} is not a full Git object ID")
    return value.lower()


def _commit_exists(repository: Path, oid: str) -> bool:
    completed = subprocess.run(
        ["git", "cat-file", "-e", f"{oid}^{{commit}}"],
        cwd=repository,
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return completed.returncode == 0


def _fetch_commit(repository: Path, remote: str, oid: str) -> None:
    _run_git(repository, "fetch", "--no-tags", remote, oid)


def _decode_nul_paths(output: bytes) -> tuple[str, ...]:
    return tuple(
        entry.decode("utf-8", errors="surrogateescape").replace("\\", "/")
        for entry in output.split(b"\0")
        if entry
    )


def is_build_relevant_path(path: str) -> bool:
    normalized = path.replace("\\", "/")
    while normalized.startswith("./"):
        normalized = normalized[2:]
    if normalized.lower().endswith(".md"):
        return False
    if normalized in NON_BUILD_FILES:
        return False
    return not normalized.startswith(NON_BUILD_PREFIXES)


def _newly_reachable_commits(
    repository: Path, before: str, after: str
) -> tuple[str, ...]:
    arguments = ["rev-list", "--reverse", after]
    if before not in ZERO_OIDS:
        arguments.extend(["--not", before])
    output = _run_git(repository, *arguments)
    return tuple(line for line in output.decode("ascii").splitlines() if line)


def _commit_changed_paths(repository: Path, commit: str) -> tuple[str, ...]:
    output = _run_git(
        repository,
        "diff-tree",
        "--root",
        "--no-commit-id",
        "--name-only",
        "-z",
        "-r",
        "-m",
        commit,
        "--",
    )
    return _decode_nul_paths(output)


def _endpoint_changed_paths(
    repository: Path, before: str, after: str
) -> tuple[str, ...]:
    output = _run_git(
        repository,
        "diff",
        "--name-only",
        "-z",
        before,
        after,
        "--",
    )
    return _decode_nul_paths(output)


def detect_changes(
    repository: Path,
    before: str,
    after: str,
    fetch_missing_before: str | None = None,
) -> ChangeDecision:
    """Inspect every commit newly reachable from the pushed branch.

    The endpoint diff is inspected as well. This catches a force push that
    removes mod changes even when it introduces no new commit.
    """

    before = _validate_oid(before, "before")
    after = _validate_oid(after, "after")

    if after in ZERO_OIDS:
        return ChangeDecision(False, 0, (), "branch deletion")
    if not _commit_exists(repository, after):
        raise GitInspectionError("the post-push commit is unavailable")

    if before not in ZERO_OIDS and not _commit_exists(repository, before):
        if fetch_missing_before is not None:
            _fetch_commit(repository, fetch_missing_before, before)
        if not _commit_exists(repository, before):
            raise GitInspectionError("the pre-push commit is unavailable")

    commits = _newly_reachable_commits(repository, before, after)
    changed_paths: set[str] = set()
    for commit in commits:
        changed_paths.update(_commit_changed_paths(repository, commit))

    if before not in ZERO_OIDS:
        changed_paths.update(_endpoint_changed_paths(repository, before, after))

    relevant_paths = tuple(
        sorted(path for path in changed_paths if is_build_relevant_path(path))
    )
    if relevant_paths:
        reason = "build-relevant paths changed in the pushed history"
    elif changed_paths:
        reason = "only documentation, agent, or automation paths changed"
    else:
        reason = "the pushed history contains no file changes"

    return ChangeDecision(bool(relevant_paths), len(commits), relevant_paths, reason)


def conservative_decision(error: Exception) -> ChangeDecision:
    return ChangeDecision(
        True,
        0,
        (),
        f"history inspection failed; building conservatively: {error}",
    )


def _append_outputs(path: Path, decision: ChangeDecision) -> None:
    with path.open("a", encoding="utf-8", newline="\n") as output:
        output.write(f"mod_changed={str(decision.mod_changed).lower()}\n")
        output.write(f"commits_scanned={decision.commits_scanned}\n")


def _markdown_code(value: str) -> str:
    sanitized = "".join(character if character >= " " else "?" for character in value)
    return sanitized.replace("`", "\\`")


def _append_summary(path: Path, decision: ChangeDecision) -> None:
    with path.open("a", encoding="utf-8", newline="\n") as summary:
        summary.write("## Mod build decision\n\n")
        summary.write(
            f"- Build required: **{str(decision.mod_changed).lower()}**\n"
        )
        summary.write(f"- Pushed commits inspected: {decision.commits_scanned}\n")
        summary.write(f"- Reason: {_markdown_code(decision.reason)}\n")
        if decision.relevant_paths:
            summary.write("- Build-relevant paths:\n")
            for changed_path in decision.relevant_paths[:20]:
                summary.write(f"  - `{_markdown_code(changed_path)}`\n")
            remaining = len(decision.relevant_paths) - 20
            if remaining > 0:
                summary.write(f"  - …and {remaining} more\n")
        summary.write("\n")


def _decision_json(decision: ChangeDecision) -> str:
    return json.dumps(
        {
            "mod_changed": decision.mod_changed,
            "commits_scanned": decision.commits_scanned,
            "relevant_paths": decision.relevant_paths,
            "reason": decision.reason,
        },
        ensure_ascii=False,
    )


def main(arguments: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    parser.add_argument("--before", required=True)
    parser.add_argument("--after", required=True)
    parser.add_argument("--fetch-missing-before")
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--github-step-summary", type=Path)
    options = parser.parse_args(arguments)

    try:
        decision = detect_changes(
            options.repository,
            options.before,
            options.after,
            options.fetch_missing_before,
        )
    except (GitInspectionError, OSError) as error:
        decision = conservative_decision(error)

    if options.github_output is not None:
        _append_outputs(options.github_output, decision)
    if options.github_step_summary is not None:
        _append_summary(options.github_step_summary, decision)
    print(_decision_json(decision))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
