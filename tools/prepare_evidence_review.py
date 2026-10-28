"""Produce local review candidates; never modify a pack or claim human confirmation."""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import re

STOP = set("a an the and or of to in on for is are was were be been by with as at from that this it its what which how why according does do did can could would should passage author imply suggest about most".split())


def words(text):
    return set(re.findall(r"[a-z]{3,}", text.lower())) - STOP


def spans(content):
    # Offsets always address the unchanged passage; sentence splitting is only a heuristic.
    for match in re.finditer(r"[^.!?]+(?:[.!?]+[’\"”']*|$)", content):
        raw = match.group()
        quote = raw.strip()
        if quote:
            start = match.start() + len(raw) - len(raw.lstrip())
            yield start, start + len(quote), quote


def candidates(pack):
    rows = []
    for paper in pack["papers"]:
        if not paper.get("reviewed") or paper.get("sample"):
            continue
        for passage in paper.get("passages", []):
            content = passage["content"]
            sentences = list(spans(content))
            frequency = Counter(w for _, _, s in sentences for w in words(s))
            for q in passage["questions"]:
                if q.get("evidence") and q.get("evidenceSource"):
                    if q["evidence"] not in content:
                        raise ValueError("Existing evidence is absent from the source")
                    continue
                answer = next((o["text"] for o in q["options"] if o["label"] == q.get("referenceAnswer")), "")
                terms = words(q["stem"] + " " + answer)
                def score(item):
                    return sum(math.log(1 + len(sentences) / frequency[w]) for w in terms & words(item[2]))
                ranked = sorted(sentences, key=lambda item: (-score(item), item[0]))[:3]
                rows.append(dict(stableId=paper["stableId"], revision=paper["revision"], passage=passage["code"],
                    number=q["number"], stem=q["stem"], options=q["options"], referenceAnswer=q.get("referenceAnswer", ""),
                    answerSource=q.get("answerSource", ""), status="pending-human-review",
                    sourceUrl=paper["sourceUrl"], sourceName=paper["sourceName"],
                    passageContent=content, passageSha256=hashlib.sha256(content.encode("utf-8")).hexdigest(),
                    candidates=[dict(quote=quote, start=start, end=end,
                        source=f'{paper["sourceUrl"]} | {paper["stableId"]} r{paper["revision"]} | passage {passage["code"]} | chars {start}:{end}',
                        status="pending-human-review") for start, end, quote in ranked]))
    return dict(format="CET6EvidenceReview", version=1, status="pending-human-review", questions=rows)


def markdown(review):
    lines = ["# 真题证据候选 · 待人工确认", "", "每句只保证来自原文。排序由词汇重合计算，不证明它支持参考答案；全部待人工确认，不能直接作为已校对证据导入。", ""]
    seen = set()
    if "packPath" in review:
        lines.extend([f'来源内容包：{review["packPath"]}', f'SHA-256：{review["packSha256"]}', ""])
    for row in review["questions"]:
        key = row["stableId"], row["passage"]
        if key not in seen:
            seen.add(key)
            lines.extend([f'# {key[0]} · 篇章 {key[1]} · 核对原文', "", row["passageContent"], ""])
        lines.extend([f'## {row["stableId"]} · 篇章 {row["passage"]} · 第 {row["number"]} 题', "", row["stem"], ""])
        lines.extend(f'{o["label"]}. {o["text"]}' for o in row["options"])
        lines.extend(["", f'参考：{row["referenceAnswer"]}；答案来源：{row["answerSource"]}', ""])
        for c in row["candidates"]:
            lines.extend([f'- 待确认：{c["quote"]}', f'  来源：{c["source"]}', ""])
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pack", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    original = args.pack.read_bytes()
    review = candidates(json.loads(original.decode("utf-8")))
    review["packPath"] = str(args.pack)
    review["packSha256"] = hashlib.sha256(original).hexdigest()
    # Exclusive writes protect previous review work.
    with args.output.open("x", encoding="utf-8") as out:
        json.dump(review, out, ensure_ascii=False, indent=2)
    print(f'{len(review["questions"])} questions pending human review; source pack unchanged')


if __name__ == "__main__":
    main()
