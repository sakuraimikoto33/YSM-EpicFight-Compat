#!/usr/bin/env python3

from pathlib import Path
import subprocess
import tempfile
import unittest

from detect_mod_changes import detect_changes, is_build_relevant_path


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
