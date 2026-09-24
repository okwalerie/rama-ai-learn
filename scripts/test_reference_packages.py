"""Static and real-snapshot isolation checks for imported reference packages."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from isolate_solver import snapshot

ROOT = Path(__file__).resolve().parents[1]
PACKAGES = ("family-tree", "collaborative-document-editor", "who-to-follow",
            "timed-notifications", "content-moderation", "profile-module",
            "top-users-module", "rest-api-integration-module", "music-catalog-migration")


class ReferencePackageTests(unittest.TestCase):
    def test_each_solver_snapshot_contains_contract_not_answer(self):
        for slug in PACKAGES:
            with self.subTest(package=slug), tempfile.TemporaryDirectory() as temp:
                target = Path(temp)
                snapshot(ROOT, target, slug)
                package = target / "challenges" / slug
                self.assertTrue((package / "README.md").is_file())
                self.assertTrue(list((package / "src").rglob("protocol.clj")))
                for forbidden in ("docs/atlas", "review", ".git"):
                    self.assertFalse((target / forbidden).exists())
                for forbidden in ("test-private", "test-resources", "test-harness"):
                    self.assertFalse((package / forbidden).exists())
                for file in [package / "README.md", *(package / "src").rglob("*.clj")]:
                    text = file.read_text()
                    for answer in ("(defmodule ", "(declare-pstate ", "[nlb.", "[rama.gallery."):
                        self.assertNotIn(answer, text, str(file))
                # Candidate acceptance must not import the upstream implementation.
                for file in (ROOT / "challenges" / slug / "test-private").rglob("*.clj"):
                    self.assertNotIn("[nlb.", file.read_text(), str(file))
                    self.assertNotIn("[rama.gallery.", file.read_text(), str(file))

    def test_candidate_and_reference_classpaths_are_disjoint(self):
        code = '''(require '[clojure.edn :as edn] '[cheshire.core :as json])
          (println (json/generate-string
            (into {} (for [slug *command-line-args*]
              [slug (edn/read-string (slurp (str "challenges/" slug "/deps.edn")))]))))'''
        data = json.loads(subprocess.check_output(["bb", "-e", code, *PACKAGES], cwd=ROOT))
        for slug, deps in data.items():
            with self.subTest(package=slug):
                aliases = deps["aliases"]
                candidate = deps["paths"] + aliases["test-private"].get("extra-paths", [])
                harness = aliases["test-private-harness"]["replace-paths"]
                self.assertTrue(any("implementations/" in p for p in candidate))
                self.assertFalse(any("test-resources" in p for p in candidate))
                self.assertIn("test-resources", harness)
                self.assertFalse(any("implementations/" in p for p in harness))


if __name__ == "__main__":
    unittest.main()
