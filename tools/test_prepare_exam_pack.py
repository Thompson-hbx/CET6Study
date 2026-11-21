import importlib.util
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

from prepare_exam_pack import convert, extract, main

_HAS_PDF_DEPS = (
    importlib.util.find_spec("pypdf") is not None
    and importlib.util.find_spec("reportlab") is not None
)


def valid_metadata(**overrides):
    data = {
        "stableId": "cet6-2025-06-14-1",
        "examDate": "2025-06-14",
        "setCode": "1",
        "revision": 1,
        "licenseStatus": "original",
        "sourceName": "测试样卷",
        "sourceUrl": "local:test",
    }
    data.update(overrides)
    return data


def _make_docx(path, document_xml):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("word/document.xml", document_xml)


class ImportTests(unittest.TestCase):
    def test_writing_reference_keeps_separate_source_and_requires_review(self):
        pack, warnings = convert(
            "[WRITING]\nOriginal task\n[INSTRUCTIONS]\nWrite briefly\n"
            "[REFERENCE_WRITING]\nOriginal sample essay.\n"
            "[REFERENCE_WRITING_SOURCE]\nlocal:essay\n[REFERENCE_SOURCE]\nlocal:translation",
            valid_metadata(reviewed=True),
        )
        paper = pack["papers"][0]
        self.assertEqual("Original sample essay.", paper["referenceWriting"])
        self.assertEqual("local:essay", paper["referenceWritingSource"])
        self.assertEqual("local:translation", paper["referenceSource"])
        self.assertFalse(paper["reviewed"])
        self.assertEqual([], paper["passages"])
        self.assertFalse(any("Q46" in w or "Passage" in w for w in warnings))
        self.assertEqual(["chineseText: missing"], warnings)

    def test_docx_preserves_paragraph_order_and_breaks_and_tabs(self):
        with TemporaryDirectory() as folder:
            path = Path(folder) / "owned.docx"
            _make_docx(
                path,
                '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
                "<w:body>"
                "<w:p><w:r><w:t>Line1</w:t><w:br/><w:t>Line2</w:t><w:tab/><w:t>Col2</w:t><w:cr/><w:t>Line3</w:t></w:r></w:p>"
                "<w:p><w:r><w:t>中文</w:t></w:r></w:p>"
                "</w:body></w:document>",
            )
            self.assertEqual("Line1\nLine2\tCol2\nLine3\n中文", extract(path))

    def test_docx_rejects_zip_bomb_during_streaming_read(self):
        import io
        from prepare_exam_pack import _parse_docx_xml_stream

        big_body = "A" * (8 * 1024 * 1024 + 128 * 1024)
        xml = (
            '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
            f"<w:body><w:p><w:r><w:t>{big_body}</w:t></w:r></w:p></w:body></w:document>"
        )
        with self.assertRaisesRegex(ValueError, "8 MiB"):
            _parse_docx_xml_stream(io.BytesIO(xml.encode("utf-8")))

        with TemporaryDirectory() as folder:
            path = Path(folder) / "bomb.docx"
            _make_docx(path, xml)
            with self.assertRaisesRegex(ValueError, "8 MiB"):
                extract(path)

    def test_docx_rejects_dtd_and_entity_declarations(self):
        with TemporaryDirectory() as folder:
            path = Path(folder) / "xxe.docx"
            _make_docx(
                path,
                '<?xml version="1.0"?>'
                '<!DOCTYPE foo [<!ENTITY x "y">]>'
                '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
                "<w:body><w:p><w:r><w:t>&x;</w:t></w:r></w:p></w:body></w:document>",
            )
            with self.assertRaisesRegex(ValueError, "DTD|entity"):
                extract(path)

    def test_extract_rejects_directory_and_oversized_file(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            with self.assertRaises(ValueError):
                extract(root)
            big = root / "big.txt"
            big.write_bytes(b"a" * (32 * 1024 * 1024 + 1))
            with self.assertRaisesRegex(ValueError, "32 MiB"):
                extract(big)

    def test_explicit_boundaries_convert_and_remain_unreviewed(self):
        text = "  \n\n[READING 1]\nAn original passage.\n[Q46]\nWhat?\nA. One\nB. Two\nC. Three\nD. Four\n[TRANSLATION]\n中文。"
        pack, warnings = convert(text, valid_metadata(reviewed=True))
        p = pack["papers"][0]
        self.assertFalse(p["reviewed"])
        self.assertEqual("An original passage.", p["passages"][0]["content"])
        self.assertEqual("Four", p["passages"][0]["questions"][0]["options"][3]["text"])
        self.assertEqual("", p["passages"][0]["questions"][0]["referenceAnswer"])
        self.assertTrue(any("Q47" in warning for warning in warnings))

    def test_pre_marker_and_empty_text_rejected(self):
        with self.assertRaises(ValueError):
            convert("stray preamble\n[WRITING]\nTask", valid_metadata())
        with self.assertRaises(ValueError):
            convert("plain text without markers", valid_metadata())
        with self.assertRaises(ValueError):
            convert("   \n\t ", valid_metadata())

    def test_prevents_a_einstein_and_am_stem_splitting(self):
        text = (
            "[READING 1]\nPassage.\n"
            "[Q46]\nWhat did\nA. Einstein say at 9\nA.M. in the lecture?\n"
            "A. Time is relative.\nB. Space is flat.\nC. Light stops.\nD. Nothing.\n"
            "[Q47]\nWho was\nA. Einstein?\n"
            "[WRITING]\nTopic"
        )
        pack, warnings = convert(text, valid_metadata())
        q46 = pack["papers"][0]["passages"][0]["questions"][0]
        self.assertIn("A. Einstein say at 9\nA.M.", q46["stem"])
        self.assertEqual(["Time is relative.", "Space is flat.", "Light stops.", "Nothing."], [o["text"] for o in q46["options"]])

        q47 = pack["papers"][0]["passages"][0]["questions"][1]
        self.assertEqual("Who was\nA. Einstein?", q47["stem"])
        self.assertEqual([], q47["options"])
        self.assertIn("Q47: incomplete options", warnings)

    def test_duplicate_boundaries_reject_mixed_papers(self):
        with self.assertRaises(ValueError):
            convert("[READING 1]\nA\n[READING 1]\nB", valid_metadata())

    def test_metadata_validation_parity_with_exam_packs(self):
        base_text = "[WRITING]\nPrompt"
        bad_cases = [
            [],
            valid_metadata(stableId="cet6-2025-06-14-01", setCode="01"),
            valid_metadata(stableId="cet6-2025-06-14-٢", setCode="2"),
            valid_metadata(stableId="cet6-2025-06-14-2", setCode="1"),
            valid_metadata(examDate="2025-02-30"),
            valid_metadata(setCode=1),
            valid_metadata(setCode="0", stableId="cet6-2025-06-14-0"),
            valid_metadata(revision=0),
            valid_metadata(revision=True),
            valid_metadata(licenseStatus="public-domain"),
            valid_metadata(sourceName="   "),
            valid_metadata(sample="yes"),
        ]
        for meta in bad_cases:
            with self.subTest(meta=meta):
                with self.assertRaises(ValueError):
                    convert(base_text, meta)

        # Empty writingPrompt & chineseText rejected even if instructions present.
        with self.assertRaises(ValueError):
            convert("[INSTRUCTIONS]\nOnly instructions", valid_metadata())

        # sample=true allows sample-cet6-... and emits a warning.
        pack, warnings = convert(base_text, valid_metadata(sample=True, stableId="sample-cet6-2025-06-14-1"))
        self.assertTrue(pack["papers"][0]["sample"])
        self.assertTrue(any("sample is true" in w for w in warnings))

        # Serialized pack > 4 MiB rejected.
        huge_prompt = "X" * (4 * 1024 * 1024)
        with self.assertRaisesRegex(ValueError, "4 MiB"):
            convert(f"[WRITING]\n{huge_prompt}", valid_metadata())

    def test_cli_convert_atomic_x_output_and_streaming_sha256(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            inp = root / "in.txt"
            meta = root / "meta.json"
            out = root / "pack.json"
            inp.write_text("\ufeff[WRITING]\nWrite an essay.\n", encoding="utf-8")
            import json
            meta.write_text(json.dumps(valid_metadata()), encoding="utf-8")

            main(["convert", str(inp), str(out), "--metadata", str(meta)])
            self.assertTrue(out.is_file())
            parsed = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual("CET6ExamPack", parsed["format"])
            self.assertFalse(parsed["papers"][0]["reviewed"])

            with self.assertRaises(SystemExit) as ctx:
                main(["convert", str(inp), str(out), "--metadata", str(meta)])
            self.assertNotEqual(0, ctx.exception.code)

    def test_pdf_extract_mocked_branches(self):
        with TemporaryDirectory() as folder:
            pdf = Path(folder) / "sample.pdf"
            pdf.write_bytes(b"%PDF-1.4\n")

            class FakeReaderValid:
                def __init__(self, _path):
                    self.pages = [SimpleNamespace(extract_text=lambda: "Original extraction fixture. Libraries provide quiet rooms for individual study.")]

            with patch.dict(sys.modules, {"pypdf": SimpleNamespace(PdfReader=FakeReaderValid)}):
                self.assertIn("Libraries provide quiet rooms", extract(pdf))

            class FakeReaderBlank:
                def __init__(self, _path):
                    self.pages = [SimpleNamespace(extract_text=lambda: "   ")]

            with patch.dict(sys.modules, {"pypdf": SimpleNamespace(PdfReader=FakeReaderBlank)}):
                with self.assertRaisesRegex(ValueError, "usable text layer"):
                    extract(pdf)

            class FakeReaderTooManyPages:
                def __init__(self, _path):
                    self.pages = [SimpleNamespace(extract_text=lambda: "page")] * 151

            with patch.dict(sys.modules, {"pypdf": SimpleNamespace(PdfReader=FakeReaderTooManyPages)}):
                with self.assertRaisesRegex(ValueError, "150 pages"):
                    extract(pdf)

            with patch.dict(sys.modules, {"pypdf": None}):
                with self.assertRaises(ModuleNotFoundError):
                    extract(pdf)

    @unittest.skipUnless(_HAS_PDF_DEPS, "pypdf and reportlab are required for real PDF round-trip tests")
    def test_pdf_text_layer(self):
        # Existing bundled libraries only; this fixture is original text, no exam content.
        from reportlab.pdfgen import canvas
        with TemporaryDirectory() as folder:
            path = Path(folder) / "original.pdf"
            c = canvas.Canvas(str(path))
            c.drawString(40, 800, "Original extraction fixture. Libraries provide quiet rooms for individual study.")
            c.save()
            self.assertIn("Libraries provide quiet rooms", extract(path))

    @unittest.skipUnless(_HAS_PDF_DEPS, "pypdf and reportlab are required for real PDF round-trip tests")
    def test_image_only_pdf_is_not_fabricated(self):
        from pypdf import PdfWriter
        with TemporaryDirectory() as folder:
            path = Path(folder) / "blank.pdf"
            writer = PdfWriter()
            writer.add_blank_page(width=300, height=300)
            writer.write(path)
            with self.assertRaises(ValueError):
                extract(path)


if __name__ == "__main__":
    unittest.main()
