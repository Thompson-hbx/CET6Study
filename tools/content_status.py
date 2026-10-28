"""Summarize source metadata without copying private exam text or claiming new proofreading."""
import argparse
import hashlib
import json
from datetime import date
from pathlib import Path


def report(path, checked_on):
    raw = path.read_bytes()
    pack = json.loads(raw.decode("utf-8"))
    assert pack["format"] == "CET6ExamPack" and pack["version"] == 1
    papers = sorted((p for p in pack["papers"] if not p.get("sample")), key=lambda p: p["stableId"])
    lines = ["# 个人题库内容状态", "", f"核对日期：{checked_on}。此表只核对内容包结构与已有来源记录，没有重新逐页校对原卷。",
        "", f"来源：`{path.as_posix()}`；SHA-256 `{hashlib.sha256(raw).hexdigest()}`。",
        "", "`reviewed` 是整套的旧字段，不能证明范文、译文或证据句全部核验。参考资料“有来源”只表示字段存在。",
        "", "| 考次 / 套 | 阅读篇 / 题 / 来源键 | 写作任务 | 翻译原文 | 范文有来源 | 译文有来源 | 证据句 |",
        "| --- | --- | --- | --- | --- | --- | --- |"]
    complete = 0
    for p in papers:
        passages = p.get("passages", [])
        questions = [q for passage in passages for q in passage.get("questions", [])]
        keys = sum(bool(q.get("referenceAnswer")) and bool(q.get("answerSource")) for q in questions)
        complete += len(passages) == 2 and len(questions) == 10 and keys == 10
        yes = lambda value: "有" if value else "缺"
        lines.append(f"| {p['examDate']} / {p['setCode']}（r{p['revision']}） | {len(passages)} / {len(questions)} / {keys} | "
            f"{yes(p.get('writingPrompt') and p.get('writingInstructions'))} | {yes(p.get('chineseText'))} | "
            f"{yes(p.get('referenceWriting') and p.get('referenceWritingSource'))} | "
            f"{yes(p.get('referenceTranslation') and p.get('referenceSource'))} | {sum(bool(q.get('evidence')) for q in questions)} |")
    lines += ["", f"共 {len(papers)} 套，{complete} 套阅读结构与来源键完整；这不代表全部内容已校对。",
        "", "2023-03 第 2/3 套阅读在原卷中合并或省略；独立题序与来源映射仍未解决，不复制第 1 套冒充。",
        "2024-06 第 1 套第 54/55 题选项标签已有配套解析第 19 页的校对记录（`reading-review-2024-06-1-labels-20261020.json`）。",
        "2023-03 第 1 套及 2023-06、2023-12 的新增阅读有同目录 `reading-review-*.json` 来源记录；此表没有把旧记录提升为本次重验。",
        "所有阅读证据字段仍为空；原文可查看不等于证据逐句核验。范文、译文仍需逐套逐字复核。", ""]
    return "\n".join(lines)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pack", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--checked-on", type=date.fromisoformat, required=True)
    args = parser.parse_args()
    result = report(args.pack, args.checked_on)
    if args.output:
        args.output.write_text(result, encoding="utf-8")
    else:
        print(result)
