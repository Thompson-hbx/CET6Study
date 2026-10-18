import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
import zipfile
from prepare_exam_pack import extract, convert


class ImportTests(unittest.TestCase):
    def test_docx_preserves_paragraph_order(self):
        with TemporaryDirectory() as folder:
            path = Path(folder) / "owned.docx"
            with zipfile.ZipFile(path, "w") as z:
                z.writestr("word/document.xml", '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Reading</w:t></w:r></w:p><w:p><w:r><w:t>中文</w:t></w:r></w:p></w:body></w:document>')
            self.assertEqual("Reading\n中文", extract(path))

    def test_explicit_boundaries_convert_and_remain_unreviewed(self):
        text = "[READING 1]\nAn original passage.\n[Q46]\nWhat?\nA. One\nB. Two\nC. Three\nD. Four\n[TRANSLATION]\n中文。"
        pack, warnings = convert(text, {"reviewed": True})
        p = pack["papers"][0]
        self.assertFalse(p["reviewed"])
        self.assertEqual("An original passage.", p["passages"][0]["content"])
        self.assertEqual("Four", p["passages"][0]["questions"][0]["options"][3]["text"])
        self.assertEqual("", p["passages"][0]["questions"][0]["referenceAnswer"])
        self.assertTrue(any("Q47" in warning for warning in warnings))

    def test_duplicate_boundaries_reject_mixed_papers(self):
        with self.assertRaises(ValueError):
            convert("[READING 1]\nA\n[READING 1]\nB", {})

    def test_pdf_text_layer(self):
        # Existing bundled libraries only; this fixture is original text, no exam content.
        from reportlab.pdfgen import canvas
        with TemporaryDirectory() as folder:
            path = Path(folder) / "original.pdf"
            c = canvas.Canvas(str(path))
            c.drawString(40, 800, "Original extraction fixture. Libraries provide quiet rooms for individual study.")
            c.save()
            self.assertIn("Libraries provide quiet rooms", extract(path))

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
