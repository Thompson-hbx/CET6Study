from datetime import date
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import content_status as cs


class ContentStatusTests(unittest.TestCase):
    def test_parses_utf8_bom_and_uses_strip_and_removes_hardcoded_notes(self):
        pack = {
            "format": "CET6ExamPack",
            "version": 1,
            "papers": [
                {
                    "stableId": "cet6-2025-06-14-1",
                    "examDate": "2025-06-14",
                    "setCode": "1",
                    "revision": 1,
                    "writingPrompt": "   ",
                    "writingInstructions": "Write 150 words.",
                    "chineseText": "\t",
                    "referenceWriting": "Essay",
                    "referenceWritingSource": "   ",
                    "referenceTranslation": "Trans",
                    "referenceSource": "local",
                    "passages": [
                        {
                            "code": "1",
                            "questions": [
                                {"number": 46, "referenceAnswer": "A", "answerSource": "  ", "evidence": " \n "}
                            ],
                        }
                    ],
                }
            ],
        }
        with TemporaryDirectory() as folder:
            pack_path = Path(folder) / "pack.json"
            pack_path.write_bytes(b"\xef\xbb\xbf" + json.dumps(pack, ensure_ascii=False).encode("utf-8"))

            text = cs.report(pack_path, date(2026, 11, 21))
            self.assertIn("| 2025-06-14 / 1（r1） | 1 / 1 / 0 | 缺 | 缺 | 缺 | 有 | 0 |", text)
            self.assertNotIn("2023-03", text)
            self.assertNotIn("2024-06", text)
            self.assertNotIn("所有阅读证据字段仍为空", text)

    def test_rejects_invalid_pack_with_value_error_even_under_o(self):
        with TemporaryDirectory() as folder:
            bad = Path(folder) / "bad.json"
            bad.write_text(json.dumps({"format": "Other", "version": 1, "papers": []}), encoding="utf-8")
            with self.assertRaises(ValueError):
                cs.report(bad, date(2026, 11, 21))

            bad_ver = Path(folder) / "bad_ver.json"
            bad_ver.write_text(json.dumps({"format": "CET6ExamPack", "version": True, "papers": []}), encoding="utf-8")
            with self.assertRaises(ValueError):
                cs.report(bad_ver, date(2026, 11, 21))

    def test_refuses_to_overwrite_existing_output(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            sample = Path(__file__).resolve().parent / "exam-sample.json"
            out = root / "status.md"
            out.write_text("existing", encoding="utf-8")

            with self.assertRaises(SystemExit) as ctx:
                cs.main([str(sample), "--checked-on", "2026-11-21", "--output", str(out)])
            self.assertNotEqual(0, ctx.exception.code)
            self.assertEqual("existing", out.read_text(encoding="utf-8"))

    def test_exam_sample_schema_and_reference_writing(self):
        sample_path = Path(__file__).resolve().parent / "exam-sample.json"
        data = json.loads(sample_path.read_text(encoding="utf-8-sig"))
        self.assertEqual("CET6ExamPack", data["format"])
        self.assertEqual(1, data["version"])
        paper = data["papers"][0]
        self.assertTrue(paper["sample"])
        self.assertEqual("local:tools/exam-sample.json", paper["sourceUrl"])
        self.assertTrue(paper["referenceWriting"].strip())
        self.assertEqual("项目原创参考范文；非官方答案", paper["referenceWritingSource"])


if __name__ == "__main__":
    unittest.main()
