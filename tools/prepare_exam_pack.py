"""Local-only PDF/DOCX extraction and explicit-marker conversion. Never guesses exam content.

Run extract first; edit extracted text to add markers described in docs/EXAM_IMPORT.md.
Then run convert with a metadata JSON. No network calls or OCR.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile
from xml.etree import ElementTree as ET


def extract(path):
    if path.stat().st_size > 32 * 1024 * 1024:
        raise ValueError("Input exceeds 32 MiB")
    suffix = path.suffix.lower()
    if suffix == ".pdf":
        from pypdf import PdfReader
        reader = PdfReader(path)
        if len(reader.pages) > 150:
            raise ValueError("More than 150 pages; split locally first")
        text = "\n\n".join(p.extract_text() or "" for p in reader.pages)
        if len(text.strip()) < 50:
            raise ValueError("No usable text layer. Scan/OCR needs manual verification; no pack created")
        return text
    if suffix == ".docx":
        with zipfile.ZipFile(path) as archive:
            info = archive.getinfo("word/document.xml")
            if info.file_size > 8 * 1024 * 1024:
                raise ValueError("DOCX XML too large")
            root = ET.fromstring(archive.read(info))
        ns = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}
        return "\n".join("".join(p.itertext()) for p in root.findall(".//w:p", ns))
    if suffix == ".txt":
        return path.read_text(encoding="utf-8-sig")
    raise ValueError("Use text PDF, DOCX or UTF-8 TXT; export legacy .doc to DOCX first")


def convert(text, metadata):
    # Explicit human-reviewed boundaries prevent cross-section or cross-paper inference.
    matches = list(re.finditer(r"(?m)^\[([A-Z0-9_ ]+)\]\s*$", text))
    blocks = {}
    for index, match in enumerate(matches):
        key = match[1]
        if key in blocks:
            raise ValueError(f"Duplicate marker {key}; possible mixed papers")
        blocks[key] = text[match.end():matches[index + 1].start() if index + 1 < len(matches) else len(text)].strip()
    allowed = {"READING 1", "READING 2", "WRITING", "INSTRUCTIONS", "TRANSLATION", "REFERENCE_TRANSLATION", "REFERENCE_SOURCE", "REFERENCE_WRITING", "REFERENCE_WRITING_SOURCE"} | {f"Q{n}" for n in range(46, 56)}
    if set(blocks) - allowed:
        raise ValueError(f"Unknown markers: {set(blocks) - allowed}")
    passages = []
    warnings = []
    for code, numbers in (("1", range(46, 51)), ("2", range(51, 56))):
        questions = []
        for number in numbers:
            block = blocks.get(f"Q{number}", "")
            options = list(re.finditer(r"(?m)^([A-D])[.)] ?", block))
            questions.append({"number": number, "stem": block[:options[0].start()].strip() if options else block,
                "options": [{"label": m[1], "text": block[m.end():options[i + 1].start() if i + 1 < len(options) else len(block)].strip()} for i, m in enumerate(options)],
                "referenceAnswer": "", "answerSource": "", "evidence": ""})
            if len(options) != 4 or {m[1] for m in options} != set("ABCD"):
                warnings.append(f"Q{number}: incomplete options")
        content = blocks.get(f"READING {code}", "")
        if not content:
            warnings.append(f"Passage {code}: missing content")
        passages.append({"code": code, "title": "", "content": content, "questions": questions})
    paper = dict(metadata)
    paper.update(passages=passages, writingPrompt=blocks.get("WRITING", ""), writingInstructions=blocks.get("INSTRUCTIONS", ""),
                 chineseText=blocks.get("TRANSLATION", ""), referenceTranslation=blocks.get("REFERENCE_TRANSLATION", ""), referenceSource=blocks.get("REFERENCE_SOURCE", ""),
                 referenceWriting=blocks.get("REFERENCE_WRITING", ""), referenceWritingSource=blocks.get("REFERENCE_WRITING_SOURCE", ""), reviewed=False)
    for name in ("writingPrompt", "writingInstructions", "chineseText"):
        if not paper[name]:
            warnings.append(f"{name}: missing")
    # User must verify answer provenance and explicitly set reviewed=true after conversion.
    return {"format": "CET6ExamPack", "version": 1, "papers": [paper]}, warnings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("extract", "convert"))
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--metadata", type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output exists; choose a new path to preserve your work")
    if args.action == "extract":
        result = extract(args.input)
    else:
        if args.metadata is None:
            parser.error("convert requires --metadata")
        pack, warnings = convert(args.input.read_text(encoding="utf-8-sig"), json.loads(args.metadata.read_text(encoding="utf-8-sig")))
        result = json.dumps(pack, ensure_ascii=False, indent=2)
        print("Pending manual review:", *warnings, sep="\n")
    args.output.write_text(result, encoding="utf-8")
    digest = hashlib.sha256(args.input.read_bytes()).hexdigest()
    print(f"Written locally: {args.output}; input SHA256: {digest}")


if __name__ == "__main__":
    main()
