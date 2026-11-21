import json
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import extract_exam_pack as eep


class ExtractExamPackTests(unittest.TestCase):
    def test_anchored_regexes_prevent_inline_false_splits_and_decimals(self):
        sec_c = (
            "Passage One\n"
            "Questions 46 to 50 are based on the following passage.\n"
            "In Passage Two of the report, researchers noted 46.5% growth.\n\n"
            "46. What did\nA. Einstein or O. Henry or H2O. show?\n"
            "A. First option.\nC. Third option.\nB. Second option.\nD. Fourth option.\n"
            "47. Isolated\nO. Henry mention?\n"
            "Passage Two\n"
            "Questions 51 to 55 are based on the following passage.\n"
            "Second article body.\n"
            "51. Which point?\nA. Alpha.\nB. Beta.\nC. Gamma.\nD. Delta.\n"
        )
        passages = eep.parse_passages_and_questions(sec_c)
        self.assertEqual(2, len(passages))
        self.assertIn("In Passage Two of the report", passages[0]["content"])
        self.assertIn("46.5% growth", passages[0]["content"])
        q46 = passages[0]["questions"][0]
        self.assertIn("A. Einstein", q46["stem"])
        self.assertIn("O. Henry", q46["stem"])
        self.assertIn("H2O.", q46["stem"])
        self.assertEqual(["A", "B", "C", "D"], [o["label"] for o in q46["options"]])
        q47 = passages[0]["questions"][1]
        self.assertEqual("Isolated O. Henry mention?", q47["stem"])
        self.assertEqual([], q47["options"])

    def test_extract_keys_requires_keys_header_and_single_chars(self):
        no_keys = "46 47 48 49 50 51 52 53 54 55\nA B C D A B C D A B"
        self.assertEqual({}, eep.extract_keys(no_keys))

        bad_tokens = "KEYS\nReading Comprehension\n46 47 48 49 50 51 52 53 54 55\nAB B C D A B C D A B"
        self.assertEqual({}, eep.extract_keys(bad_tokens))

        good = "KEYS\nReading Comprehension\n46 47 48 49 50 51 52 53 54 55\nA B C D A B C D A B"
        self.assertEqual({46: "A", 47: "B", 55: "B"}, {k: v for k, v in eep.extract_keys(good).items() if k in {46, 47, 55}})

    def test_parse_pdf_computes_sha256_once_and_errors_without_pypdf(self):
        with TemporaryDirectory() as folder:
            pdf_path = Path(folder) / "cet6_2025_06_1.pdf"
            pdf_path.write_bytes(b"synthetic pdf bytes")

            fake_text = (
                "Part I Writing (30 minutes)\nDirections: Write about libraries.\n"
                "Part II Listening Comprehension\n"
                "Section C\nDirections: Read.\n"
                "Passage One\nArticle text.\n"
                "46. Question?\nA. A1\nB. B1\nC. C1\nD. D1\n"
                "47. Question?\nA. A2\nB. B2\nC. C2\nD. D2\n"
                "Part IV Translation\nDirections: Translate.\n中文翻译原文。\n"
                "KEYS\nReading Comprehension\n46 47 48 49 50 51 52 53 54 55\nA B C D A B C D A B\n"
            )

            class FakePdfReader:
                def __init__(self, _path, strict=True):
                    self.pages = [SimpleNamespace(extract_text=lambda: fake_text)]

            hash_calls = 0
            orig_sha = eep._sha256_file

            def spy_sha(path):
                nonlocal hash_calls
                hash_calls += 1
                return orig_sha(path)

            with patch.object(eep, "PdfReader", FakePdfReader), patch.object(eep, "_sha256_file", side_effect=spy_sha):
                parsed = eep.parse_pdf(pdf_path)

            self.assertEqual(1, hash_calls)
            self.assertEqual("Write about libraries.", parsed["writingPrompt"])
            self.assertEqual("中文翻译原文。", parsed["chineseText"])
            self.assertEqual("A", parsed["passages"][0]["questions"][0]["referenceAnswer"])
            self.assertEqual("B", parsed["passages"][0]["questions"][1]["referenceAnswer"])

            with patch.object(eep, "PdfReader", None), patch.dict("sys.modules", {"pypdf": None}):
                with self.assertRaisesRegex(RuntimeError, "pypdf"):
                    eep.parse_pdf(pdf_path)

    def test_extract_directory_upfront_checks_and_retains_readingless_papers(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            missing_raw = root / "missing"
            out_file = root / "out" / "pack.json"

            with self.assertRaises(FileNotFoundError):
                eep.extract_directory(missing_raw, out_file)
            self.assertFalse(out_file.parent.exists())

            raw_dir = root / "raw"
            raw_dir.mkdir()
            (raw_dir / "cet6_2025_06_1.pdf").write_bytes(b"pdf1")
            (raw_dir / "cet6_2025_06_2.pdf").write_bytes(b"pdf2")

            def fake_parse(path):
                if path.name == "cet6_2025_06_1.pdf":
                    return {
                        "chineseText": "中文翻译题。",
                        "writingPrompt": "",
                        "writingInstructions": "",
                        "passages": [],
                    }
                return {"chineseText": "", "writingPrompt": "   ", "writingInstructions": "", "passages": []}

            with patch.object(eep, "parse_pdf", side_effect=fake_parse):
                pack = eep.extract_directory(raw_dir, out_file)

            self.assertEqual(1, len(pack["papers"]))
            paper = pack["papers"][0]
            self.assertEqual("cet6-2025-06-14-1", paper["stableId"])
            self.assertEqual("", paper["referenceWriting"])
            self.assertEqual("", paper["referenceWritingSource"])
            saved = json.loads(out_file.read_text(encoding="utf-8"))
            self.assertEqual(1, len(saved["papers"]))

            # Upfront out_file.exists() check refuses before touching PDFs.
            with patch.object(eep, "parse_pdf", side_effect=AssertionError("should not parse")):
                with self.assertRaises(FileExistsError):
                    eep.extract_directory(raw_dir, out_file)


if __name__ == "__main__":
    unittest.main()
