"""Versioned, conservative offline metrics; this is not a semantic judge or an API client."""
import hashlib
import json


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _source(case):
    return case["material"] if case["kind"] == "reading" else case["answer"]


def validate_cases(cases):
    _require(isinstance(cases, list) and bool(cases), "Gold must contain a nonempty case list")
    ids = set()
    for case in cases:
        _require(isinstance(case, dict), "Gold case must be an object")
        identifier = case.get("id")
        _require(isinstance(identifier, str) and bool(identifier) and identifier not in ids, "Gold IDs must be unique text")
        ids.add(identifier)
        _require(case.get("kind") in ("writing", "translation", "reading"), "Unknown gold kind")
        _require(isinstance(case.get("material"), str) and bool(case["material"].strip()), "Gold material is missing")
        if case["kind"] != "reading":
            _require(isinstance(case.get("answer"), str) and bool(case["answer"].strip()), "Gold answer is missing")
        source = _source(case)
        _require(len(source) <= 60_000, "Gold source exceeds app limit")
        errors = case.get("errors")
        _require(isinstance(errors, list), "Gold errors must be a list")
        _require(type(case.get("negativeControl", False)) is bool, "Negative control flag must be boolean")
        _require(not case.get("negativeControl") or not errors, "Negative control cannot contain target errors")
        evidence = case.get("evidence", [])
        _require(isinstance(evidence, list), "Gold evidence must be a list")
        seen = set()
        for error in errors + evidence:
            _require(isinstance(error, dict), "Gold span must be an object")
            quote, start = error.get("quote"), error.get("start")
            _require(isinstance(quote, str) and bool(quote) and type(start) is int and start >= 0,
                     "Gold span needs literal text and integer offset")
            _require(source[start:start + len(quote)] == quote, "Gold offset does not match literal source text")
        for error in errors:
            key = (error["start"], error["quote"])
            _require(key not in seen, "Duplicate gold error span")
            seen.add(key)
            fixes = error.get("fixes")
            _require(isinstance(fixes, list) and bool(fixes) and all(isinstance(fix, str) and fix != error["quote"] for fix in fixes),
                     "Gold fixes must list real text changes")
        if case["kind"] == "reading":
            options = case.get("options")
            _require(isinstance(options, dict) and bool(options), "Reading gold options are missing")
            _require(all(isinstance(key, str) and len(key) == 1 and "A" <= key <= "Z" and isinstance(value, str)
                         for key, value in options.items()), "Reading gold options are invalid")
            _require(isinstance(case.get("correct"), str) and case["correct"] in options,
                     "Reading gold answer is not an option")
    return ids


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        _require(key not in result, "Duplicate JSON field")
        result[key] = value
    return result


def _invalid_constant(_value):
    raise ValueError("Nonfinite JSON number")


def _rows(response, case):
    _require(isinstance(response, str) and 0 < len(response) <= 60_000, "Missing, nontext or oversized response")
    decoder = json.JSONDecoder(object_pairs_hook=_unique_object, parse_constant=_invalid_constant)
    rows, index = [], 0
    while index < len(response):
        start = response.find("{", index)
        if start < 0:
            break
        try:
            value, index = decoder.raw_decode(response, start)
        except (ValueError, RecursionError) as failure:
            raise ValueError("Malformed response JSON") from failure
        _require(isinstance(value, dict) and isinstance(value.get("type"), str), "Missing record type")
        rows.append(value)
    known = ({"locate", "misread", "option", "summary", "check"} if case["kind"] == "reading" else
             {"score", "aspect", "issue", "segment", "term", "tip", "rewrite"})
    _require(any(row["type"] in known for row in rows), "No recognized evaluation records")
    rows = [row for row in rows if row["type"] in known]
    labels = set()
    for row in rows:
        kind = row["type"]
        if kind == "issue":
            _require(isinstance(row.get("quote"), str) and isinstance(row.get("fix"), str), "Issue fields must be text")
            _require(row.get("level", "must") in ("must", "better"), "Invalid issue level")
        elif kind == "locate":
            _require(isinstance(row.get("quote"), str), "Evidence quote must be text")
        elif kind == "option":
            label = row.get("label")
            _require(isinstance(label, str) and label.upper() in case.get("options", {}), "Invalid option label")
            _require(label.upper() not in labels, "Duplicate option label")
            labels.add(label.upper())
            _require(row.get("verdict") in ("correct", "wrong"), "Invalid option verdict")
    return rows


def _occurrences(source, quote):
    spans = []
    start = source.find(quote) if quote else -1
    while start >= 0:
        spans.append((start, start + len(quote)))
        start = source.find(quote, start + 1)
    return spans


def _ratio(numerator, denominator):
    return dict(value=numerator / denominator if denominator else None, numerator=numerator, denominator=denominator)


def evaluate(cases, predictions):
    expected_ids = validate_cases(cases)
    _require(isinstance(predictions, list), "Predictions must be a list")
    results = {}
    for prediction in predictions:
        _require(isinstance(prediction, dict) and isinstance(prediction.get("caseId"), str), "Prediction case ID missing")
        identifier = prediction["caseId"]
        if "requestState" in prediction:
            state = prediction["requestState"]
            _require(state in ("complete", "failed"), "Incomplete or uncertain request checkpoint cannot be scored")
            _require(bool(prediction.get("error")) == (state == "failed"), "Checkpoint state and request error disagree")
        _require(identifier in expected_ids and identifier not in results, "Unknown or duplicate prediction case")
        results[identifier] = prediction
    _require(set(results) == expected_ids, "Supply one response for every case, including failed requests")
    counts = dict(quotes=0, exact_quotes=0, suggestions=0, required_suggestions=0, optional_suggestions=0,
                  located=0, required_located=0, applicable_located=0, expected_errors=0, detected_errors=0, applicable_detected_errors=0,
                  applied=0, successful_fixes=0, no_ops=0, overlap_skipped=0, ambiguous_quotes=0,
                  reading_cases=0, correct_reading=0, full_options_correct=0, option_labels=0, expected_option_labels=0,
                  expected_evidence=0, cited_evidence=0, format_failures=0, request_failures=0,
                  negative_controls=0, controls_with_required_suggestions=0, clean_controls_passed=0, valid_cases=0)
    by_case = []
    for case in cases:
        baseline = counts.copy()
        prediction = results[case["id"]]
        failed_request = bool(prediction.get("error"))
        if failed_request:
            counts["request_failures"] += 1
            rows = []
        else:
            try:
                rows = _rows(prediction.get("response"), case)
                counts["valid_cases"] += 1
            except ValueError:
                rows = []
                counts["format_failures"] += 1
        source, gold = _source(case), case["errors"]
        counts["expected_errors"] += len(gold)
        matched, applicable, fixed, evidence, occupied = set(), set(), set(), set(), []
        quote_uses = {}
        review = []
        required = 0
        for index, row in enumerate(rows):
            if row["type"] not in ("issue", "locate"):
                continue
            quote = row["quote"]
            spans = _occurrences(source, quote)
            if quote:
                counts["quotes"] += 1
                counts["exact_quotes"] += bool(spans)
                counts["ambiguous_quotes"] += len(spans) > 1
            if row["type"] == "locate":
                for number, target in enumerate(case.get("evidence", [])):
                    if any(left <= target["start"] and right >= target["start"] + len(target["quote"]) for left, right in spans):
                        evidence.add(number)
                continue
            counts["suggestions"] += 1
            must = row.get("level", "must") == "must"
            required += must
            counts["required_suggestions"] += must
            counts["optional_suggestions"] += not must
            counts["no_ops"] += row["fix"] == quote
            # Literal citation matching and applicable UI placement answer different questions.
            occurrence = quote_uses.get(quote, 0)
            quote_uses[quote] = occurrence + 1
            cited = spans[occurrence] if occurrence < len(spans) else None
            quoted_gold = next((number for number, target in enumerate(gold) if number not in matched and cited and
                               target["start"] == cited[0] and target["quote"] == quote), None)
            if quoted_gold is not None:
                matched.add(quoted_gold)
                counts["located"] += 1
                counts["required_located"] += must
            span = next((span for span in spans if all(span[1] <= left or span[0] >= right for left, right in occupied)), None)
            if span is None:
                counts["overlap_skipped"] += bool(spans)
                review.append(dict(row=index, reason="overlapping_quote" if spans else "unanchored_quote"))
                continue
            occupied.append(span)
            found = next((number for number, target in enumerate(gold) if number not in applicable and
                          target["start"] == span[0] and target["quote"] == quote), None)
            if found is not None:
                applicable.add(found)
                counts["applicable_located"] += 1
            else:
                review.append(dict(row=index, reason="unlisted_span_requires_review"))
            if row["fix"] != quote:
                counts["applied"] += 1
                if found is not None and row["fix"] in gold[found]["fixes"]:
                    fixed.add(found)
                    counts["successful_fixes"] += 1
                else:
                    review.append(dict(row=index, reason="unenumerated_fix_requires_review"))
        counts["detected_errors"] += len(matched)
        counts["applicable_detected_errors"] += len(applicable)
        counts["expected_evidence"] += len(case.get("evidence", []))
        counts["cited_evidence"] += len(evidence)
        if case["kind"] == "reading":
            counts["reading_cases"] += 1
            options = [row for row in rows if row["type"] == "option"]
            selected = [row["label"].upper() for row in options if row["verdict"] == "correct"]
            correct = selected == [case["correct"]]
            counts["correct_reading"] += correct
            counts["option_labels"] += len(options)
            counts["expected_option_labels"] += len(case["options"])
            counts["full_options_correct"] += correct and len(options) == len(case["options"])
        if case.get("negativeControl", False):
            counts["negative_controls"] += 1
            counts["controls_with_required_suggestions"] += required > 0
            counts["clean_controls_passed"] += bool(rows) and required == 0
        by_case.append(dict(caseId=case["id"], counts={key: counts[key] - baseline[key] for key in counts},
                            review=review, detected=sorted(matched), applicable=sorted(applicable), fixed=sorted(fixed)))
    return dict(scoring_version=2,
                gold_cases_sha256=hashlib.sha256(json.dumps(cases, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest(),
                format_scope="JSON records and fields consumed by the scorer; app protocol validation is separate",
                semantic_scope="Literal span and enumerated fix matches are not human-adjudicated semantic accuracy",
                counts=counts, metrics=dict(
                    quote_accuracy=_ratio(counts["exact_quotes"], counts["quotes"]),
                    exact_gold_span_match_rate=_ratio(counts["located"], counts["suggestions"]),
                    applicable_gold_span_match_rate=_ratio(counts["applicable_located"], counts["suggestions"]),
                    required_gold_span_match_rate=_ratio(counts["required_located"], counts["required_suggestions"]),
                    exact_gold_span_miss_rate=_ratio(counts["expected_errors"] - counts["detected_errors"], counts["expected_errors"]),
                    applicable_gold_span_miss_rate=_ratio(counts["expected_errors"] - counts["applicable_detected_errors"], counts["expected_errors"]),
                    enumerated_fix_match_rate=_ratio(counts["successful_fixes"], counts["applied"]),
                    gold_fix_coverage=_ratio(counts["successful_fixes"], counts["expected_errors"]),
                    reading_answer_accuracy=_ratio(counts["correct_reading"], counts["reading_cases"]),
                    full_options_reading_accuracy=_ratio(counts["full_options_correct"], counts["reading_cases"]),
                    reading_option_coverage=_ratio(counts["option_labels"], counts["expected_option_labels"]),
                    evidence_span_coverage=_ratio(counts["cited_evidence"], counts["expected_evidence"]),
                    clean_control_success=_ratio(counts["clean_controls_passed"], counts["negative_controls"]),
                    valid_case_rate=_ratio(counts["valid_cases"], len(cases))), cases=by_case)
