#!/usr/bin/env python3

from pathlib import Path
import tempfile
import unittest

from release_version import (
    SemVer,
    decide_release,
    history_url,
    read_gradle_property,
)


class SemVerTest(unittest.TestCase):
    def test_semver_precedence_examples(self) -> None:
        ordered = [
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0",
            "1.0.1",
            "1.1.0",
            "2.0.0",
        ]
        parsed = [SemVer.parse(value) for value in ordered]
        for older, newer in zip(parsed, parsed[1:]):
            with self.subTest(older=older.source, newer=newer.source):
                self.assertLess(older, newer)

    def test_build_metadata_does_not_change_precedence(self) -> None:
        self.assertEqual(SemVer.parse("1.0.0+build.1"), SemVer.parse("1.0.0+build.2"))

    def test_prerelease_detection(self) -> None:
        self.assertTrue(SemVer.parse("0.2.0-beta.1").is_prerelease)
        self.assertFalse(SemVer.parse("0.2.0").is_prerelease)

    def test_invalid_versions_are_rejected(self) -> None:
        invalid = ["1", "1.0", "01.0.0", "1.01.0", "1.0.01", "1.0.0-01"]
        for value in invalid:
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    SemVer.parse(value)


class ReleaseDecisionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tags = [
            "0.1.0-mc1.20.1",
            "0.1.1-mc1.20.1",
            "0.9.0-mc1.21.1",
        ]

    def test_newer_version_is_released(self) -> None:
        decision = decide_release("0.1.2", "1.20.1", self.tags)
        self.assertTrue(decision.should_release)
        self.assertEqual("0.1.2-mc1.20.1", decision.tag)
        self.assertEqual("0.1.1-mc1.20.1", decision.previous_tag)

    def test_equal_and_older_versions_are_skipped(self) -> None:
        self.assertFalse(
            decide_release("0.1.1", "1.20.1", self.tags).should_release
        )
        self.assertFalse(
            decide_release("0.1.0", "1.20.1", self.tags).should_release
        )

    def test_other_minecraft_versions_do_not_interfere(self) -> None:
        decision = decide_release("0.2.0", "1.20.1", self.tags)
        self.assertTrue(decision.should_release)
        self.assertEqual("0.1.1-mc1.20.1", decision.previous_tag)

    def test_prerelease_and_stable_promotion(self) -> None:
        prerelease = decide_release("0.2.0-beta.1", "1.20.1", self.tags)
        self.assertTrue(prerelease.should_release)
        self.assertTrue(prerelease.prerelease)

        stable_tags = [*self.tags, "0.2.0-beta.1-mc1.20.1"]
        stable = decide_release("0.2.0", "1.20.1", stable_tags)
        self.assertTrue(stable.should_release)
        self.assertFalse(stable.prerelease)

    def test_build_metadata_with_equal_precedence_is_skipped(self) -> None:
        tags = ["1.0.0+build.1-mc1.20.1"]
        decision = decide_release("1.0.0+build.2", "1.20.1", tags)
        self.assertFalse(decision.should_release)

    def test_first_version_is_released(self) -> None:
        decision = decide_release("0.1.0", "1.20.1", [])
        self.assertTrue(decision.should_release)
        self.assertIsNone(decision.previous_tag)

    def test_malformed_matching_tag_is_ignored(self) -> None:
        decision = decide_release(
            "0.1.2", "1.20.1", [*self.tags, "latest-mc1.20.1"]
        )
        self.assertEqual(("latest-mc1.20.1",), decision.ignored_tags)
        self.assertTrue(decision.should_release)


class InputOutputTest(unittest.TestCase):
    def test_gradle_property_must_be_unique(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "gradle.properties"
            path.write_text("mod_version=0.1.0\n", encoding="utf-8")
            self.assertEqual("0.1.0", read_gradle_property(path, "mod_version"))
            path.write_text(
                "mod_version=0.1.0\nmod_version=0.1.1\n", encoding="utf-8"
            )
            with self.assertRaises(ValueError):
                read_gradle_property(path, "mod_version")

    def test_history_links(self) -> None:
        self.assertEqual(
            "https://github.com/owner/repo/compare/0.1.1-mc1.20.1...abc123",
            history_url("owner/repo", "abc123", "0.1.1-mc1.20.1"),
        )
        self.assertEqual(
            "https://github.com/owner/repo/commits/abc123",
            history_url("owner/repo", "abc123", None),
        )


if __name__ == "__main__":
    unittest.main()
