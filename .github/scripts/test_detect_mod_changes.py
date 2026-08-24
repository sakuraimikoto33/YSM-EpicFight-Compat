#!/usr/bin/env python3

from pathlib import Path
import subprocess
import tempfile
import unittest

from detect_mod_changes import (
    ArtifactReference,
    ChangeDecision,
    _append_summary,
    artifact_name_prefix,
    detect_changes,
    is_build_relevant_path,
    select_latest_artifact,
)


class TemporaryRepository:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.git("init", "--initial-branch=main")
        self.git("config", "user.name", "Workflow Test")
        self.git("config", "user.email", "workflow-test@example.invalid")

    def git(self, *arguments: str) -> str:
        completed = subprocess.run(
            ["git", *arguments],
            cwd=self.root,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        return completed.stdout.strip()

    def commit_file(self, path: str, contents: str, message: str) -> str:
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(contents, encoding="utf-8")
        self.git("add", "--", path)
        self.git("commit", "-m", message)
        return self.git("rev-parse", "HEAD")

    def remove_file(self, path: str, message: str) -> str:
        (self.root / path).unlink()
        self.git("add", "-A", "--", path)
        self.git("commit", "-m", message)
        return self.git("rev-parse", "HEAD")


class PathClassificationTest(unittest.TestCase):
    def test_repository_metadata_does_not_require_a_build(self) -> None:
        for path in (
            ".agents/skills/example/SKILL.md",
            ".github/workflows/build.yml",
            "docs/implementation.md",
            "assets/logo.png",
            "AGENTS.md",
            "README.md",
            "README.ja.md",
            "CHANGELOG.md",
            "src/main/AGENTS.md",
            "LICENSE",
            ".gitignore",
        ):
            with self.subTest(path=path):
                self.assertFalse(is_build_relevant_path(path))

    def test_unknown_and_mod_paths_require_a_build(self) -> None:
        for path in (
            "src/main/java/example/Compat.java",
            "gradle/wrapper/gradle-wrapper.properties",
            "build.gradle",
            "gradle.properties",
            "new-build-system/config.toml",
        ):
            with self.subTest(path=path):
                self.assertTrue(is_build_relevant_path(path))


class ArtifactReferenceTest(unittest.TestCase):
    def test_artifact_name_prefix_matches_upload_name(self) -> None:
        self.assertEqual(
            "ysm-epicfight-compat-mc-1.20.1-",
            artifact_name_prefix("mc/1.20.1"),
        )

    def test_latest_matching_unexpired_branch_artifact_is_selected(self) -> None:
        artifacts = [
            {},
            {
                "id": 10,
                "name": "ysm-epicfight-compat-mc-1.20.1-old",
                "expired": False,
                "created_at": "2026-01-01T00:00:00Z",
                "workflow_run": {"id": 100, "head_branch": "mc/1.20.1"},
            },
            {
                "id": 11,
                "name": "ysm-epicfight-compat-mc-1.20.1-expired",
                "expired": True,
                "created_at": "2026-03-01T00:00:00Z",
                "workflow_run": {"id": 101, "head_branch": "mc/1.20.1"},
            },
            {
                "id": 12,
                "name": "ysm-epicfight-compat-mc-1.21.1-other",
                "expired": False,
                "created_at": "2026-04-01T00:00:00Z",
                "workflow_run": {"id": 102, "head_branch": "mc/1.21.1"},
            },
            {
                "id": 13,
                "name": "ysm-epicfight-compat-mc-1.20.1-latest",
                "expired": False,
                "created_at": "2026-02-01T00:00:00Z",
                "workflow_run": {"id": 103, "head_branch": "mc/1.20.1"},
            },
        ]

        artifact = select_latest_artifact(artifacts, "mc/1.20.1")

        self.assertIsNotNone(artifact)
        assert artifact is not None
        self.assertEqual(13, artifact.artifact_id)
        self.assertEqual(
            "https://github.com/example/repository/actions/runs/103/artifacts/13",
            artifact.web_url("https://github.com", "example/repository"),
        )

    def test_skipped_build_summary_contains_artifact_link(self) -> None:
        decision = ChangeDecision(False, 2, (), "documentation only")
        artifact = ArtifactReference(
            13,
            "ysm-epicfight-compat-mc-1.20.1-latest",
            103,
            "2026-02-01T00:00:00Z",
        )
        with tempfile.TemporaryDirectory() as directory:
            summary_path = Path(directory) / "summary.md"
            _append_summary(
                summary_path,
                decision,
                artifact,
                artifact.web_url("https://github.com", "example/repository"),
            )

            summary = summary_path.read_text(encoding="utf-8")

        self.assertIn("## Mod build decision", summary)
        self.assertIn("- Build required: **false**", summary)
        self.assertIn(
            "[ysm-epicfight-compat-mc-1.20.1-latest]"
            "(https://github.com/example/repository/actions/runs/103/artifacts/13)",
            summary,
        )

    def test_required_build_summary_does_not_contain_artifact_entry(self) -> None:
        decision = ChangeDecision(True, 1, ("src/Test.java",), "mod changed")
        with tempfile.TemporaryDirectory() as directory:
            summary_path = Path(directory) / "summary.md"
            _append_summary(summary_path, decision, artifact_note="unused")

            summary = summary_path.read_text(encoding="utf-8")

        self.assertNotIn("Latest artifact", summary)


class PushedHistoryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.repository = TemporaryRepository(
            Path(self.temporary_directory.name).resolve()
        )

    def test_multiple_documentation_commits_skip_build(self) -> None:
        before = self.repository.commit_file("README.md", "initial\n", "initial")
        self.repository.commit_file("README.md", "updated\n", "readme")
        after = self.repository.commit_file(
            "docs/implementation.md", "details\n", "documentation"
        )

        decision = detect_changes(self.repository.root, before, after)

        self.assertFalse(decision.mod_changed)
        self.assertEqual(2, decision.commits_scanned)

    def test_each_pushed_commit_is_checked_even_when_mod_change_is_reverted(self) -> None:
        before = self.repository.commit_file("README.md", "initial\n", "initial")
        self.repository.commit_file("src/main/Test.java", "class Test {}\n", "code")
        after = self.repository.remove_file("src/main/Test.java", "revert code")

        decision = detect_changes(self.repository.root, before, after)

        self.assertTrue(decision.mod_changed)
        self.assertEqual(2, decision.commits_scanned)
        self.assertIn("src/main/Test.java", decision.relevant_paths)

    def test_merge_is_compared_with_target_branch_first_parent(self) -> None:
        self.repository.commit_file("README.md", "initial\n", "initial")
        self.repository.git("switch", "-c", "mc/test")
        before = self.repository.commit_file(
            "src/main/Test.java", "class Test {}\n", "existing mod code"
        )
        self.repository.git("switch", "main")
        self.repository.commit_file(
            ".github/workflows/build.yml", "name: test\n", "automation"
        )
        self.repository.git("switch", "mc/test")
        self.repository.git("merge", "--no-ff", "main", "-m", "merge main")
        after = self.repository.git("rev-parse", "HEAD")

        decision = detect_changes(self.repository.root, before, after)

        self.assertFalse(decision.mod_changed)
        self.assertEqual(2, decision.commits_scanned)

    def test_force_push_removing_mod_content_requires_build(self) -> None:
        after = self.repository.commit_file("README.md", "initial\n", "initial")
        before = self.repository.commit_file(
            "src/main/Test.java", "class Test {}\n", "code"
        )

        decision = detect_changes(self.repository.root, before, after)

        self.assertTrue(decision.mod_changed)
        self.assertEqual(0, decision.commits_scanned)
        self.assertIn("src/main/Test.java", decision.relevant_paths)

    def test_new_branch_checks_all_reachable_commits(self) -> None:
        self.repository.commit_file("README.md", "initial\n", "initial")
        after = self.repository.commit_file(
            "src/main/Test.java", "class Test {}\n", "code"
        )

        decision = detect_changes(self.repository.root, "0" * 40, after)

        self.assertTrue(decision.mod_changed)
        self.assertEqual(2, decision.commits_scanned)

    def test_deleted_branch_does_not_require_build(self) -> None:
        before = self.repository.commit_file("README.md", "initial\n", "initial")

        decision = detect_changes(self.repository.root, before, "0" * 40)

        self.assertFalse(decision.mod_changed)
        self.assertEqual("branch deletion", decision.reason)


if __name__ == "__main__":
    unittest.main()
