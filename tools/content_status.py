"""Summarize source metadata without copying private exam text or claiming new proofreading."""
import argparse
import hashlib
import json
from datetime import date
from pathlib import Path


def _has_text(value):
    return isinstance(value, str) and bool(value.strip())


def report(path, checked_on):
    if not path.is_file():
        raise ValueError(f"Pack file not found: {path}")
    raw = path.read_bytes()
    pack = json.loads(raw.decode("utf-8-sig"))
    if (
        not isinstance(pack, dict)
        or pack.get("format") != "CET6ExamPack"
        or isinstance(pack.get("version"), bool)
        or pack.get("version") != 1
        or not isinstance(pack.get("papers"), list)
    ):
        raise ValueError("Invalid CET6ExamPack v1 file")

    papers = sorted((p for p in pack["papers"] if not p.get("sample")), key=lambda p: p["stableId"])
    lines = [
        "# 个人题库内容状态",
        "",
        f"核对日期：{checked_on}。此表只核对内容包结构与已有来源记录，没有重新逐页校对原卷。",
        "",
        f"来源：`{path.as_posix()}`；SHA-256 `{hashlib.sha256(raw).hexdigest()}`。",
        "",
        "`reviewed` 是整套的旧字段，不能证明范文、译文或证据句全部核验。参考资料“有来源”只表示字段存在。",
        "",
        "| 考次 / 套 | 阅读篇 / 题 / 来源键 | 写作任务 | 翻译原文 | 范文有来源 | 译文有来源 | 证据句 |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    complete = 0
    yes = lambda value: "有" if value else "缺"
    for p in papers:
        passages = p.get("passages", [])
        questions = [q for passage in passages for q in passage.get("questions", [])]
        keys = sum(_has_text(q.get("referenceAnswer")) and _has_text(q.get("answerSource")) for q in questions)
        complete += len(passages) == 2 and len(questions) == 10 and keys == 10
        lines.append(
            f"| {p['examDate']} / {p['setCode']}（r{p['revision']}） | {len(passages)} / {len(questions)} / {keys} | "
            f"{yes(_has_text(p.get('writingPrompt')) and _has_text(p.get('writingInstructions')))} | "
            f"{yes(_has_text(p.get('chineseText')))} | "
            f"{yes(_has_text(p.get('referenceWriting')) and _has_text(p.get('referenceWritingSource')))} | "
            f"{yes(_has_text(p.get('referenceTranslation')) and _has_text(p.get('referenceSource')))} | "
            f"{sum(_has_text(q.get('evidence')) for q in questions)} |"
        )
    lines += [
        "",
        f"共 {len(papers)} 套，{complete} 套阅读结构与来源键完整；这不代表全部内容已校对。",
        "",
        "说明：此表只统计结构与非空来源字段，不代表逐页原文、答案、范文或译文已逐字核验。",
        "",
    ]
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pack", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--checked-on", type=date.fromisoformat, required=True)
    args = parser.parse_args(argv)
    if not args.pack.is_file():
        parser.error(f"Pack file not found: {args.pack}")
    if args.output is not None and args.output.exists():
        parser.error(f"Output exists: {args.output}")
    result = report(args.pack, args.checked_on)
    if args.output is not None:
        try:
            with open(args.output, "x", encoding="utf-8") as handle:
                handle.write(result)
        except FileExistsError:
            parser.error(f"Output exists: {args.output}")
    else:
        print(result)


if __name__ == "__main__":
    main()
