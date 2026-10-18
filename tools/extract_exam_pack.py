"""Automatic CET-6 Exam Materials Extractor and Pack Builder.

Extracts reading passages (Section C), questions 46-55 with ABCD options,
writing prompts, translation source text, and third-party reference answers from downloaded raw-papers/ PDFs,
and bundles them into standard CET6ExamPack JSON format.
"""
import json
import re
from pathlib import Path

from pypdf import PdfReader
import hashlib


def extract_writing(text):
    m = re.search(r"Part I\s+Writing\s*(?:\(\s*30\s*minutes\s*\))?\s*Directions:\s*(.*?)(?=Part II|\n\s*Listening|\Z)", text, re.S | re.I)
    if not m:
        return "", ""
    content = m.group(1).strip()
    # Normalize spaces
    content = re.sub(r"[ \t]+", " ", content)
    content = re.sub(r"\n\s*\n+", "\n\n", content)
    # Split prompt and instructions if possible
    return content, "原卷限时 30 分钟；字数与具体要求以原始题干为准。"


def parse_passages_and_questions(sec_c_text):
    passages = []

    # Split by Passage One / Passage Two
    p1_match = re.search(r"Passage\s+(?:One|1)\s*(.*?)(?=Passage\s+(?:Two|2)|\Z)", sec_c_text, re.S | re.I)
    p2_match = re.search(r"Passage\s+(?:Two|2)\s*(.*?)(?=Part IV|\n\s*Translation|\n\s*KEYS|\Z)", sec_c_text, re.S | re.I)

    for code, p_match in [("1", p1_match), ("2", p2_match)]:
        if not p_match:
            continue
        raw_passage = p_match.group(1).strip()
        # Find where questions start
        # Usually: "Questions 46 to 50 are based on..." or "46. ..."
        q_start = 46 if code == "1" else 51
        q_end = 50 if code == "1" else 55

        q_split = re.search(rf"\n\s*{q_start}\s*[.)]", raw_passage)
        if not q_split:
            continue

        article_part = raw_passage[:q_split.start()].strip()
        # Remove "Questions X to Y are based on the following passage." from article header
        article_part = re.sub(r"^Questions\s+\d+\s+to\s+\d+\s+are\s+based\s+on\s+.*?\.\s*", "", article_part, flags=re.S | re.I).strip()
        # Clean up linebreaks in article
        article_lines = [l.strip() for l in article_part.splitlines() if l.strip()]
        clean_article = "\n\n".join(article_lines)

        questions_part = raw_passage[q_split.start():].strip()

        # Parse individual questions
        questions = []
        for q_num in range(q_start, q_end + 1):
            next_q = q_num + 1
            if next_q <= q_end:
                pattern = rf"(?ms)^\s*{q_num}\s*[.)]\s*(.*?)(?=^\s*{next_q}\s*[.)]|\Z)"
            else:
                pattern = rf"(?ms)^\s*{q_num}\s*[.)]\s*(.*?)(?=\Z)"

            q_match = re.search(pattern, questions_part)
            if not q_match:
                continue
            q_block = q_match.group(1).strip()

            # PDF columns can yield A/C/B/D. Preserve labels; never infer O) as C).
            opt_matches = list(re.finditer(r"(?<![A-Za-z])([ABCDO])[.)]\s*", q_block))
            stem = re.sub(r"\s+", " ", q_block[:opt_matches[0].start()] if opt_matches else q_block).strip()
            options = []
            for i, om in enumerate(opt_matches):
                end = opt_matches[i+1].start() if i+1 < len(opt_matches) else len(q_block)
                options.append({"label": om.group(1), "text": re.sub(r"\s+", " ", q_block[om.end():end]).strip()})
            options.sort(key=lambda o: o["label"])

            questions.append({
                "number": q_num,
                "stem": stem,
                "options": options,
                "referenceAnswer": "",
                "answerSource": "",
                "evidence": ""
            })

        passages.append({
            "code": code,
            "title": f"Passage {code}",
            "content": clean_article,
            "questions": questions
        })

    return passages


def extract_keys(text):
    keys = {}
    # Search for Reading Comprehension Keys (36-45, 46-55)
    m = re.search(r"KEYS.*?(?:Reading Comprehension|\n\s*46\s+47)(.*?)(?:\n\s*\n\s*\n|\Z)", text, re.S | re.I)
    block = m.group(0) if m else text

    # Try table lookup: 46 47 48 49 50 51 52 53 54 55 followed by letters
    table_m = re.search(r"46\s+47\s+48\s+49\s+50\s+51\s+52\s+53\s+54\s+55\s+([A-D\s]+)", block)
    if table_m:
        letters = table_m.group(1).split()
        if len(letters) >= 10:
            for idx, num in enumerate(range(46, 56)):
                keys[num] = letters[idx]
    return keys


def parse_pdf(pdf_path):
    doc = PdfReader(pdf_path, strict=True)
    full_text = "\n".join(page.extract_text() or "" for page in doc.pages)

    writing_prompt, writing_instructions = extract_writing(full_text)

    sec_c_m = re.search(r"Section C\s+Directions:.*?(Passage\s+(?:One|1).*?)(?=Part IV|\n\s*Translation|\n\s*KEYS|\Z)", full_text, re.S | re.I)
    sec_c_text = sec_c_m.group(1) if sec_c_m else ""

    passages = parse_passages_and_questions(sec_c_text)
    keys = extract_keys(full_text)

    for p in passages:
        for q in p["questions"]:
            if q["number"] in keys:
                q["referenceAnswer"] = keys[q["number"]]
                q["answerSource"] = f"https://www.wehuster.com/static/cet6/{pdf_path.name}#embedded-KEYS; SHA256={hashlib.sha256(pdf_path.read_bytes()).hexdigest()} (third-party reference)"

    translation = ""
    tail = re.search(r"Part IV\s+Translation(.*?)(?=\n\s*KEYS|\Z)", full_text, re.S | re.I)
    if tail:
        chinese = re.search(r"[\u4e00-\u9fff]", tail.group(1))
        if chinese:
            translation = tail.group(1)[chinese.start():].strip()
    return {
        "chineseText": translation,
        "writingPrompt": writing_prompt,
        "writingInstructions": writing_instructions,
        "passages": passages
    }


def main():
    root = Path(__file__).resolve().parent.parent
    raw_dir = root / "raw-papers"
    out_dir = root / ".tools" / "private-exams" / "agent-extracted"
    out_dir.mkdir(parents=True, exist_ok=True)

    session_mapping = {
        "2023_03": "2023-03-12",
        "2023_06": "2023-06-17",
        "2023_12": "2023-12-16",
        "2024_06": "2024-06-15",
        "2024_12": "2024-12-14",
        "2025_06": "2025-06-14",
        "2025_12": "2025-12-13",
        "2026_06": "2026-06-13",
    }

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
        if session_str not in session_mapping:
            continue

        exam_date = session_mapping[session_str]
        stable_id = f"cet6-{exam_date}-{set_code}"

        parsed = parse_pdf(pdf_path)
        if not parsed["passages"]:
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
            "referenceSource": ""
        }
        all_papers.append(paper)
        print(f"Extracted: {name} -> {stable_id} (passages={len(parsed['passages'])})")

    pack = {
        "format": "CET6ExamPack",
        "version": 1,
        "papers": all_papers
    }

    out_file = out_dir / "cet6-recent-years.json"
    if out_file.exists():
        raise FileExistsError(f"Keep previous output; choose a new output directory: {out_file}")
    out_file.write_text(json.dumps(pack, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nSaved pack with {len(all_papers)} papers to {out_file}")


if __name__ == "__main__":
    main()
