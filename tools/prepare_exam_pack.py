"""Local-only PDF/DOCX extraction and explicit-marker conversion. Never guesses exam content.

Run extract first; edit extracted text to add markers described in docs/EXAM_IMPORT.md.
Then run convert with a metadata JSON. No network calls or OCR.
"""
import argparse
from datetime import date
import hashlib
import json
from pathlib import Path
import re
from xml.etree import ElementTree as ET
import xml.parsers.expat as expat
import zipfile

MAX_INPUT_BYTES = 32 * 1024 * 1024
MAX_DOCX_XML_BYTES = 8 * 1024 * 1024
MAX_PACK_BYTES = 4 * 1024 * 1024
ALLOWED_LICENSES = {"personal-use", "redistributable", "original"}
W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"


def _sha256_file(path):
    with open(path, "rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def _check_input_file(path, max_bytes, label="Input"):
    if not path.is_file():
        raise ValueError(f"{label} file not found: {path}")
    if path.stat().st_size > max_bytes:
        raise ValueError(f"{label} exceeds {max_bytes // (1024 * 1024)} MiB")


def _forbid_dtd_or_entity(*_args, **_kwargs):
    raise ValueError("XML DTD/entity declarations are forbidden")


def _parse_docx_xml_stream(stream, max_bytes=MAX_DOCX_XML_BYTES):
    builder = ET.TreeBuilder()
    parser = expat.ParserCreate(namespace_separator="}")
    parser.StartElementHandler = lambda name, attrs: builder.start(
        "{" + name if "}" in name else name,
        {("{" + k if "}" in k else k): v for k, v in attrs.items()},
    )
    parser.EndElementHandler = lambda name: builder.end("{" + name if "}" in name else name)
    parser.CharacterDataHandler = builder.data
    parser.StartDoctypeDeclHandler = _forbid_dtd_or_entity
    parser.EntityDeclHandler = _forbid_dtd_or_entity
    parser.UnparsedEntityDeclHandler = _forbid_dtd_or_entity
    parser.NotationDeclHandler = _forbid_dtd_or_entity
    parser.ExternalEntityRefHandler = lambda *_a, **_kw: _forbid_dtd_or_entity()

    total = 0
    try:
        while True:
            chunk = stream.read(64 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if total > max_bytes:
                raise ValueError("DOCX XML exceeds 8 MiB")
            parser.Parse(chunk, False)
        parser.Parse(b"", True)
    except expat.ExpatError as exc:
        raise ValueError(f"Malformed DOCX XML: {exc}") from exc
    return builder.close()


def _paragraph_text(elem):
    parts = []
    if elem.text:
        parts.append(elem.text)
    if elem.tag in (f"{{{W_NS}}}br", f"{{{W_NS}}}cr"):
        parts.append("\n")
    elif elem.tag == f"{{{W_NS}}}tab":
        parts.append("\t")
    for child in elem:
        parts.append(_paragraph_text(child))
    if elem.tail:
        parts.append(elem.tail)
    return "".join(parts)


def extract(path):
    _check_input_file(path, MAX_INPUT_BYTES, "Input")
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
            with archive.open("word/document.xml", "r") as stream:
                root = _parse_docx_xml_stream(stream, MAX_DOCX_XML_BYTES)
        return "\n".join(_paragraph_text(p) for p in root.findall(f".//{{{W_NS}}}p"))
    if suffix == ".txt":
        return path.read_text(encoding="utf-8-sig")
    raise ValueError("Use text PDF, DOCX or UTF-8 TXT; export legacy .doc to DOCX first")


def _split_stem_and_options(block):
    matches = list(re.finditer(r"(?m)^([A-D])[.)][ \t]+", block))
    if not matches:
        return block, []
    # Select the longest strictly increasing trailing sequence of >= 2 option labels.
    start_idx = len(matches) - 1
    while start_idx > 0 and ord(matches[start_idx - 1][1]) < ord(matches[start_idx][1]):
        start_idx -= 1
    chosen = matches[start_idx:]
    if len(chosen) < 2:
        return block, []
    stem = block[:chosen[0].start()].strip()
    options = [
        {
            "label": m[1],
            "text": block[m.end():chosen[i + 1].start() if i + 1 < len(chosen) else len(block)].strip(),
        }
        for i, m in enumerate(chosen)
    ]
    return stem, options


def _req_trimmed_str(metadata, field):
    value = metadata.get(field)
    if not isinstance(value, str):
        raise ValueError(f"{field} must be a string")
    return value.strip()


def _validate_metadata(metadata):
    if not isinstance(metadata, dict):
        raise ValueError("Metadata must be a JSON object")

    sample = metadata.get("sample", False)
    if not isinstance(sample, bool):
        raise ValueError("sample must be a boolean")

    stable_id = _req_trimmed_str(metadata, "stableId")
    id_regex = r"(?:sample-)?cet6-(\d{4}-\d{2}-\d{2})-([1-9]\d?)" if sample else r"cet6-(\d{4}-\d{2}-\d{2})-([1-9]\d?)"
    id_match = re.fullmatch(id_regex, stable_id, re.ASCII)
    if not id_match:
        raise ValueError("stableId must match cet6-yyyy-MM-dd-N (no leading zeros)")

    exam_date = _req_trimmed_str(metadata, "examDate")
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", exam_date, re.ASCII):
        raise ValueError("examDate must be yyyy-MM-dd")
    try:
        date.fromisoformat(exam_date)
    except ValueError as exc:
        raise ValueError("examDate is not a valid calendar date") from exc

    set_code = _req_trimmed_str(metadata, "setCode")
    if not re.fullmatch(r"[1-9]\d*", set_code, re.ASCII):
        raise ValueError("setCode must be a positive integer string without leading zeros")

    if id_match.group(1) != exam_date or int(id_match.group(2)) != int(set_code):
        raise ValueError("stableId does not match examDate and setCode")

    revision = metadata.get("revision")
    if isinstance(revision, bool) or not isinstance(revision, int) or revision < 1:
        raise ValueError("revision must be an integer >= 1")

    license_status = _req_trimmed_str(metadata, "licenseStatus")
    if license_status not in ALLOWED_LICENSES:
        raise ValueError(f"licenseStatus must be one of {sorted(ALLOWED_LICENSES)}")

    source_name = _req_trimmed_str(metadata, "sourceName")
    if not source_name:
        raise ValueError("sourceName must not be empty")

    source_url_raw = metadata.get("sourceUrl", "")
    if not isinstance(source_url_raw, str):
        raise ValueError("sourceUrl must be a string")
    source_url = source_url_raw.strip()

    normalized = dict(metadata)
    normalized["stableId"] = stable_id
    normalized["examDate"] = exam_date
    normalized["setCode"] = set_code
    normalized["revision"] = revision
    normalized["licenseStatus"] = license_status
    normalized["sourceName"] = source_name
    normalized["sourceUrl"] = source_url
    if "sample" in metadata:
        normalized["sample"] = sample
    return normalized, sample


def convert(text, metadata):
    if not isinstance(text, str):
        raise ValueError("Input text must be a string")
    if len(text.encode("utf-8")) > MAX_INPUT_BYTES:
        raise ValueError("Input text exceeds 32 MiB")

    # Explicit human-reviewed boundaries prevent cross-section or cross-paper inference.
    matches = list(re.finditer(r"(?m)^\[([A-Z0-9_ ]+)\]\s*$", text))
    if not matches:
        if text.strip():
            raise ValueError("Unexpected text before first [MARKER]; no [MARKER] sections found")
        raise ValueError("No [MARKER] sections found")
    if text[:matches[0].start()].strip():
        raise ValueError("Unexpected text before the first [MARKER]; add an explicit marker or remove stray text")

    blocks = {}
    for index, match in enumerate(matches):
        key = match[1]
        if key in blocks:
            raise ValueError(f"Duplicate marker {key}; possible mixed papers")
        blocks[key] = text[match.end():matches[index + 1].start() if index + 1 < len(matches) else len(text)].strip()
    allowed = {
        "READING 1", "READING 2", "WRITING", "INSTRUCTIONS", "TRANSLATION",
        "REFERENCE_TRANSLATION", "REFERENCE_SOURCE", "REFERENCE_WRITING", "REFERENCE_WRITING_SOURCE",
    } | {f"Q{n}" for n in range(46, 56)}
    if set(blocks) - allowed:
        raise ValueError(f"Unknown markers: {set(blocks) - allowed}")

    paper, is_sample = _validate_metadata(metadata)
    warnings = []
    if is_sample:
        warnings.append("sample is true; Inkwell will skip this paper on import")

    has_reading_markers = any(k in {"READING 1", "READING 2"} or k.startswith("Q") for k in blocks)
    passages = []
    if has_reading_markers:
        for code, numbers in (("1", range(46, 51)), ("2", range(51, 56))):
            questions = []
            for number in numbers:
                block = blocks.get(f"Q{number}", "")
                stem, options = _split_stem_and_options(block)
                questions.append({
                    "number": number,
                    "stem": stem,
                    "options": options,
                    "referenceAnswer": "",
                    "answerSource": "",
                    "evidence": "",
                })
                if len(options) != 4 or {o["label"] for o in options} != set("ABCD"):
                    warnings.append(f"Q{number}: incomplete options")
            content = blocks.get(f"READING {code}", "")
            if not content:
                warnings.append(f"Passage {code}: missing content")
            passages.append({"code": code, "title": "", "content": content, "questions": questions})

    paper.update(
        passages=passages,
        writingPrompt=blocks.get("WRITING", ""),
        writingInstructions=blocks.get("INSTRUCTIONS", ""),
        chineseText=blocks.get("TRANSLATION", ""),
        referenceTranslation=blocks.get("REFERENCE_TRANSLATION", ""),
        referenceSource=blocks.get("REFERENCE_SOURCE", ""),
        referenceWriting=blocks.get("REFERENCE_WRITING", ""),
        referenceWritingSource=blocks.get("REFERENCE_WRITING_SOURCE", ""),
        reviewed=False,
    )
    if not paper["writingPrompt"].strip() and not paper["chineseText"].strip():
        raise ValueError("Paper must have non-empty writingPrompt or chineseText")

    for name in ("writingPrompt", "writingInstructions", "chineseText"):
        if not paper[name]:
            warnings.append(f"{name}: missing")

    pack = {"format": "CET6ExamPack", "version": 1, "papers": [paper]}
    serialized = json.dumps(pack, ensure_ascii=False, indent=2)
    if len(serialized.encode("utf-8")) > MAX_PACK_BYTES:
        raise ValueError("Generated pack JSON exceeds 4 MiB limit")
    # User must verify answer provenance and explicitly set reviewed=true after conversion.
    return pack, warnings


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("extract", "convert"))
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--metadata", type=Path)
    args = parser.parse_args(argv)
    if args.output.exists():
        parser.error("Output exists; choose a new path to preserve your work")
    if args.action == "extract":
        result = extract(args.input)
    else:
        if args.metadata is None:
            parser.error("convert requires --metadata")
        _check_input_file(args.input, MAX_INPUT_BYTES, "Input")
        _check_input_file(args.metadata, MAX_PACK_BYTES, "Metadata")
        pack, warnings = convert(
            args.input.read_text(encoding="utf-8-sig"),
            json.loads(args.metadata.read_text(encoding="utf-8-sig")),
        )
        result = json.dumps(pack, ensure_ascii=False, indent=2)
        if warnings:
            print("Pending manual review:", *warnings, sep="\n")
    try:
        with open(args.output, "x", encoding="utf-8") as handle:
            handle.write(result)
    except FileExistsError:
        parser.error("Output exists; choose a new path to preserve your work")
    digest = _sha256_file(args.input)
    print(f"Written locally: {args.output}; input SHA256: {digest}")


if __name__ == "__main__":
    main()
