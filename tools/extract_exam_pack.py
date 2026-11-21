"""Automatic CET-6 Exam Materials Extractor and Pack Builder.

Extracts reading passages (Section C), questions 46-55 with ABCD options,
writing prompts, translation source text, and third-party reference answers from downloaded raw-papers/ PDFs,
and bundles them into standard CET6ExamPack JSON format.
"""
import hashlib
import json
from pathlib import Path
import re

PdfReader = None

SESSION_MAPPING = {
    "2023_03": "2023-03-12",
    "2023_06": "2023-06-17",
    "2023_12": "2023-12-16",
    "2024_06": "2024-06-15",
    "2024_12": "2024-12-14",
    "2025_06": "2025-06-14",
    "2025_12": "2025-12-13",
    "2026_06": "2026-06-13",
}


def _sha256_file(path):
    with open(path, "rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def extract_writing(text):
    m = re.search(
        r"(?mis)^\s*Part\s+I\s+Writing\b(?:\s*\(\s*30\s*minutes\s*\))?\s*Directions:\s*(.*?)(?=^\s*Part\s+II\b|^\s*Listening\b|\Z)",
        text,
    )
    if not m:
        return "", ""
    content = m.group(1).strip()
    content = re.sub(r"[ \t]+", " ", content)
    content = re.sub(r"\n\s*\n+", "\n\n", content)
    return content, "原卷限时 30 分钟；字数与具体要求以原始题干为准。"


def _split_question_options(q_block):
    opt_matches = list(re.finditer(r"(?:^|(?<=\s))([ABCDO])[.)]\s+", q_block))
    if not opt_matches:
        return re.sub(r"\s+", " ", q_block).strip(), []

    # If the stem contains an inline initial like "A. Einstein" or "O. Henry" before the
    # real option block starting with A, anchor on the last A that precedes further options.
    chosen = None
    for idx in range(len(opt_matches) - 2, -1, -1):
        if opt_matches[idx].group(1) == "A":
            chosen = opt_matches[idx:]
            break
    if chosen is None:
        chosen = opt_matches if len(opt_matches) >= 2 else []

    if not chosen:
        return re.sub(r"\s+", " ", q_block).strip(), []

    stem = re.sub(r"\s+", " ", q_block[:chosen[0].start()]).strip()
    options = []
    for i, om in enumerate(chosen):
        end = chosen[i + 1].start() if i + 1 < len(chosen) else len(q_block)
        options.append({
            "label": om.group(1),
            "text": re.sub(r"\s+", " ", q_block[om.end():end]).strip(),
        })
    options.sort(key=lambda o: o["label"])
    return stem, options


def parse_passages_and_questions(sec_c_text):
    passages = []

    p1_match = re.search(
        r"(?mis)^\s*Passage\s+(?:One|1)\b\s*(.*?)(?=^\s*Passage\s+(?:Two|2)\b|\Z)",
        sec_c_text,
    )
    p2_match = re.search(
        r"(?mis)^\s*Passage\s+(?:Two|2)\b\s*(.*?)(?=^\s*Part\s+IV\b|^\s*Translation\b|^\s*KEYS\b|\Z)",
        sec_c_text,
    )

    for code, p_match in [("1", p1_match), ("2", p2_match)]:
        if not p_match:
            continue
        raw_passage = p_match.group(1).strip()
        q_start = 46 if code == "1" else 51
        q_end = 50 if code == "1" else 55

        q_split = re.search(rf"(?m)^\s*{q_start}\s*[.)]\s+", raw_passage)
        if not q_split:
            continue

        article_part = raw_passage[:q_split.start()].strip()
        article_part = re.sub(
            r"(?i)^Questions\s+\d+\s+to\s+\d+\s+are\s+based\s+on\s+[^\n]*?passage\.?\s*",
            "",
            article_part,
        ).strip()
        article_lines = [line.strip() for line in article_part.splitlines() if line.strip()]
        clean_article = "\n\n".join(article_lines)

        questions_part = raw_passage[q_split.start():].strip()

        questions = []
        for q_num in range(q_start, q_end + 1):
            next_q = q_num + 1
            if next_q <= q_end:
                pattern = rf"(?ms)^\s*{q_num}\s*[.)]\s+(.*?)(?=^\s*{next_q}\s*[.)]\s+|\Z)"
            else:
                pattern = rf"(?ms)^\s*{q_num}\s*[.)]\s+(.*?)(?=\Z)"

            q_match = re.search(pattern, questions_part)
            if not q_match:
                continue
            q_block = q_match.group(1).strip()
            stem, options = _split_question_options(q_block)

            questions.append({
                "number": q_num,
                "stem": stem,
                "options": options,
                "referenceAnswer": "",
                "answerSource": "",
                "evidence": "",
            })

        passages.append({
            "code": code,
            "title": f"Passage {code}",
            "content": clean_article,
            "questions": questions,
        })

    return passages


def extract_keys(text):
    keys = {}
    m = re.search(
        r"(?mis)^\s*KEYS\b.*?(?:Reading\s+Comprehension|^\s*46\s+47\b)(.*?)(?:\n\s*\n\s*\n|\Z)",
        text,
    )
    if not m:
        return keys

    table_m = re.search(
        r"\b46\s+47\s+48\s+49\s+50\s+51\s+52\s+53\s+54\s+55\b\s+([A-D](?:\s+[A-D]){9})(?!\S)",
        m.group(0),
    )
    if table_m:
        letters = table_m.group(1).split()
        if len(letters) == 10 and all(len(ch) == 1 and ch in {"A", "B", "C", "D"} for ch in letters):
            for idx, num in enumerate(range(46, 56)):
                keys[num] = letters[idx]
    return keys


def _get_pdf_reader():
    if PdfReader is not None:
        return PdfReader
    try:
        from pypdf import PdfReader as pypdf_reader
        return pypdf_reader
    except ImportError as exc:
        raise RuntimeError("pypdf is required to parse PDF files; install pypdf first") from exc


def parse_pdf(pdf_path):
    reader_cls = _get_pdf_reader()
    doc = reader_cls(pdf_path, strict=True)
    full_text = "\n".join(page.extract_text() or "" for page in doc.pages)

    writing_prompt, writing_instructions = extract_writing(full_text)

    sec_c_m = re.search(
        r"(?mis)^\s*Section\s+C\s+Directions:.*?(^\s*Passage\s+(?:One|1)\b.*?)(?=^\s*Part\s+IV\b|^\s*Translation\b|^\s*KEYS\b|\Z)",
        full_text,
    )
    sec_c_text = sec_c_m.group(1) if sec_c_m else ""

    passages = parse_passages_and_questions(sec_c_text)
    keys = extract_keys(full_text)

    pdf_sha256 = _sha256_file(pdf_path)
    for p in passages:
        for q in p["questions"]:
            if q["number"] in keys:
                q["referenceAnswer"] = keys[q["number"]]
                q["answerSource"] = f"https://www.wehuster.com/static/cet6/{pdf_path.name}#embedded-KEYS; SHA256={pdf_sha256} (third-party reference)"

    translation = ""
    tail = re.search(r"(?mis)^\s*Part\s+IV\s+Translation\b(.*?)(?=^\s*KEYS\b|\Z)", full_text)
    if tail:
        chinese = re.search(r"[\u4e00-\u9fff]", tail.group(1))
        if chinese:
            translation = tail.group(1)[chinese.start():].strip()
    return {
        "chineseText": translation,
        "writingPrompt": writing_prompt,
        "writingInstructions": writing_instructions,
        "passages": passages,
    }


def extract_directory(raw_dir, out_file):
    if not raw_dir.is_dir():
        raise FileNotFoundError(f"Input directory not found: {raw_dir}")
    if out_file.exists():
        raise FileExistsError(f"Keep previous output; choose a new output file: {out_file}")

    all_papers = []
    pdf_files = sorted(raw_dir.glob("cet6_*.pdf"))

    for pdf_path in pdf_files:
        name = pdf_path.stem
        if name.endswith("_ans"):
            continue

        m = re.fullmatch(r"cet6_(\d{4}_\d{2})_([123])", name)
        if not m:
            continue
        session_str, set_code = m.group(1), m.group(2)
        if session_str not in SESSION_MAPPING:
            continue

        exam_date = SESSION_MAPPING[session_str]
        stable_id = f"cet6-{exam_date}-{set_code}"

        parsed = parse_pdf(pdf_path)
        if not parsed["passages"] and not parsed["writingPrompt"].strip() and not parsed["chineseText"].strip():
            continue

        paper = {
            "stableId": stable_id,
            "examDate": exam_date,
            "setCode": set_code,
            "revision": 1,
            "sourceName": f"WeHUSTER 第三方整理卷 ({session_str} 第{set_code}套)",
            "sourceUrl": f"https://www.wehuster.com/cet6/{name}",
            "licenseStatus": "personal-use",
            "reviewed": False,
            "sample": False,
            "passages": parsed["passages"],
            "writingPrompt": parsed["writingPrompt"],
            "writingInstructions": parsed["writingInstructions"],
            "chineseText": parsed["chineseText"],
            "referenceTranslation": "",
            "referenceSource": "",
            "referenceWriting": "",
            "referenceWritingSource": "",
        }
        all_papers.append(paper)
        print(f"Extracted: {name} -> {stable_id} (passages={len(parsed['passages'])})")

    pack = {
        "format": "CET6ExamPack",
        "version": 1,
        "papers": all_papers,
    }

    out_file.parent.mkdir(parents=True, exist_ok=True)
    with open(out_file, "x", encoding="utf-8") as handle:
        handle.write(json.dumps(pack, ensure_ascii=False, indent=2))
    print(f"\nSaved pack with {len(all_papers)} papers to {out_file}")
    return pack


def main():
    root = Path(__file__).resolve().parent.parent
    raw_dir = root / "raw-papers"
    out_file = root / ".tools" / "private-exams" / "agent-extracted" / "cet6-recent-years.json"
    extract_directory(raw_dir, out_file)


if __name__ == "__main__":
    main()
