"""Score saved model predictions offline. No API calls, credentials or private exams."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GOLD = ROOT / "app/testdata/coach-evaluation-v1.json"


def parse_objects(response):
    decoder = json.JSONDecoder()
    rows = []
    index = 0
    while index < len(response):
        start = response.find("{", index)
        if start < 0:
            break
        try:
            value, end = decoder.raw_decode(response, start)
        except json.JSONDecodeError as exc:
            raise ValueError("Prediction contains malformed JSON; retain raw output and score format failure") from exc
        if not isinstance(value, dict):
            raise ValueError("Prediction objects must be dictionaries")
        rows.append(value)
        index = end
    return rows


def evaluate(cases, predictions):
    expected_ids = {case["id"] for case in cases}
    if len(predictions) != len(expected_ids) or {p["caseId"] for p in predictions} != expected_ids:
        raise ValueError("Supply exactly one response for every gold case; no omitted or duplicate IDs")
    results = {p["caseId"]: p["response"] for p in predictions}
    counts = dict(quotes=0, exact_quotes=0, suggestions=0, located=0, expected_errors=0,
                  detected_errors=0, applied=0, successful_fixes=0, reading_cases=0, correct_reading=0, format_failures=0)
    by_case = []
    for case in cases:
        response = results[case["id"]]
        if not isinstance(response, str) or len(response) > 60_000:
            raise ValueError("Response must be text within the app limit")
        try:
            rows = parse_objects(response)
        except ValueError:
            rows = []
            counts["format_failures"] += 1
        source = case.get("answer", case["material"])
        gold = case["errors"]
        counts["expected_errors"] += len(gold)
        matched = set()
        fixed = set()
        occupied = []
        for row in rows:
            if row.get("type") not in ("issue", "locate"):
                continue
            quote = row.get("quote", "")
            if not isinstance(quote, str):
                quote = ""
            counts["quotes"] += 1
            start = source.find(quote) if quote else -1
            exact = start >= 0
            counts["exact_quotes"] += exact
            if row.get("type") != "issue":
                continue
            counts["suggestions"] += 1
            candidates = [i for i, error in enumerate(gold) if i not in matched and exact and
                          error["quote"] == quote and error["start"] == start]
            if candidates:
                found = candidates[0]
                matched.add(found)
                counts["located"] += 1
            else:
                found = None
            # Simulate accepting located, non-overlapping fixes. Harmless paraphrases still need human review.
            end = start + len(quote)
            if exact and all(end <= left or start >= right for left, right in occupied):
                occupied.append((start, end))
                counts["applied"] += 1
                if found is not None and row.get("fix") in gold[found]["fixes"]:
                    fixed.add(found)
                    counts["successful_fixes"] += 1
        counts["detected_errors"] += len(matched)
        if case["kind"] == "reading":
            counts["reading_cases"] += 1
            correct = [r.get("label", "").upper() for r in rows
                       if r.get("type") == "option" and r.get("verdict") == "correct"]
            counts["correct_reading"] += correct == [case["correct"]]
        by_case.append(dict(caseId=case["id"], detected=len(matched), expected=len(gold), successfulFixes=len(fixed)))
    def ratio(numerator, denominator):
        return dict(value=numerator / denominator if denominator else None, numerator=numerator, denominator=denominator)
    return dict(counts=counts, metrics=dict(
        quote_accuracy=ratio(counts["exact_quotes"], counts["quotes"]),
        localization_precision=ratio(counts["located"], counts["suggestions"]),
        omission_rate=ratio(counts["expected_errors"] - counts["detected_errors"], counts["expected_errors"]),
        accepted_fix_success=ratio(counts["successful_fixes"], counts["applied"]),
        reading_answer_accuracy=ratio(counts["correct_reading"], counts["reading_cases"])), cases=by_case)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("predictions", type=Path, help='JSON array of {caseId, response}; retain model/settings separately')
    parser.add_argument("--gold", type=Path, default=GOLD, help="Versioned original gold corpus")
    parser.add_argument("--scoring-version", type=int, choices=(1, 2), default=2,
                        help="Use 1 to reproduce historical metrics exactly; default 2")
    args = parser.parse_args()
    if args.predictions.stat().st_size > 8 * 1024 * 1024 or args.gold.stat().st_size > 8 * 1024 * 1024:
        raise ValueError("Evaluation input exceeds 8 MiB")
    dataset = json.loads(args.gold.read_text(encoding="utf-8-sig"))
    predictions = json.loads(args.predictions.read_text(encoding="utf-8-sig"))
    if args.scoring_version == 1:
        result = evaluate(dataset["cases"], predictions)
    else:
        from coach_evaluation_v2 import evaluate as evaluate_v2
        result = evaluate_v2(dataset["cases"], predictions)
        result["dataset_version"] = dataset.get("version")
    print(json.dumps(result, ensure_ascii=False, indent=2))
