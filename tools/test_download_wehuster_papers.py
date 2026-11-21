import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import download_wehuster_papers as dwp


class DownloadAuditTests(unittest.TestCase):
    def test_dry_run_is_strictly_zero_write(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            out_dir = root / "missing-raw"
            manifest = root / "nested" / "manifest.json"

            code = dwp.main(["--dry-run", "--out-dir", str(out_dir), "--manifest", str(manifest)])
            self.assertEqual(0, code)
            self.assertFalse(out_dir.exists())
            self.assertFalse(manifest.parent.exists())
            self.assertFalse(manifest.exists())

    def test_non_dry_run_continues_all_entries_and_returns_1_when_missing(self):
        urls = dwp.build_urls()
        self.assertEqual(46, len(urls))
        with TemporaryDirectory() as folder:
            root = Path(folder)
            out_dir = root / "raw"
            out_dir.mkdir()
            # Provide only the first file; directory for the second; rest missing.
            (out_dir / urls[0][1]).write_bytes(b"pdf-bytes")
            (out_dir / urls[1][1]).mkdir()
            manifest = root / "audit.json"

            code = dwp.main(["--out-dir", str(out_dir), "--manifest", str(manifest)])
            self.assertEqual(1, code)
            data = json.loads(manifest.read_text(encoding="utf-8"))
            self.assertEqual(46, len(data["files"]))
            self.assertEqual(1, data["stats"]["skip"])
            self.assertEqual(45, data["stats"]["fail"])
            self.assertTrue(data["files"][0]["exists"])
            self.assertIsNotNone(data["files"][0]["sha256"])
            self.assertFalse(data["files"][1]["exists"])
            self.assertIsNone(data["files"][1]["sha256"])

    def test_non_dry_run_returns_0_when_all_24_files_present(self):
        urls = dwp.build_urls()
        with TemporaryDirectory() as folder:
            out_dir = Path(folder) / "raw"
            out_dir.mkdir()
            for _, filename in urls:
                (out_dir / filename).write_bytes(b"ok")
            code = dwp.main(["--out-dir", str(out_dir)])
            self.assertEqual(0, code)

    def test_default_audit_does_not_touch_private_exams_dir(self):
        source = Path(dwp.__file__).read_text(encoding="utf-8")
        self.assertNotIn(".tools/private-exams", source)
        self.assertNotIn("private-exams", source)

    def test_robots_policy_handles_bom_pre_html_and_fails_closed(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            robots_html = root / "robots.html"
            robots_html.write_text(
                "\ufeff<html><body><pre>User-agent: *\nDisallow: /static/\n</pre></body></html>",
                encoding="utf-8",
            )
            self.assertIsNotNone(dwp.parse_robots_policy(robots_html))
            self.assertFalse(dwp.check_robots_policy(robots_html, "CET6Study-personal-study", f"{dwp.BASE_URL}/x.pdf"))
            self.assertTrue(
                dwp.check_robots_policy(robots_html, "CET6Study-personal-study", "https://www.wehuster.com/index.html")
            )

            robots_allow = root / "robots_allow.txt"
            robots_allow.write_text("\ufeffUser-agent: *\nAllow: /\n", encoding="utf-8")
            self.assertTrue(dwp.check_robots_policy(robots_allow, "CET6Study-personal-study", f"{dwp.BASE_URL}/x.pdf"))

            empty_html = root / "empty.html"
            empty_html.write_text("<html><body>404 Not Found</body></html>", encoding="utf-8")
            self.assertFalse(dwp.check_robots_policy(empty_html, "CET6Study-personal-study", f"{dwp.BASE_URL}/x.pdf"))
            self.assertFalse(dwp.check_robots_policy(root / "missing.txt", "CET6Study-personal-study", f"{dwp.BASE_URL}/x.pdf"))


if __name__ == "__main__":
    unittest.main()
