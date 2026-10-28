"""Offline scorer regression fixtures; no case here is a live model result."""
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from coach_evaluation_v2 import evaluate as score, validate_cases
from evaluate_coach import evaluate as legacy_score


class EvaluationV2Test(unittest.TestCase):
    def writing(self, answer="They goes. They goes."):
        quote = "They goes"
        return dict(id="controlled", kind="writing", material="Original exercise", answer=answer,
                    errors=[dict(quote=quote, start=at, fixes=["They go"])
                            for at in (0, answer.rindex(quote))])

    def reading(self):
        return dict(id="controlled", kind="reading", material="Only some students attended.", errors=[],
                    correct="A", options=dict(A="Some", B="All", C="None", D="Teachers"))

    def run_case(self, case, rows):
        response = rows if isinstance(rows, str) else "\n".join(json.dumps(row) for row in rows)
        return score([case], [dict(caseId=case["id"], response=response)])

    def test_uncertain_checkpoint_cannot_be_scored(self):
        for state in ("in-flight", "pending", "unexpected"):
            with self.assertRaises(ValueError):
                score([self.writing()], [dict(caseId="controlled", response="", error="unknown outcome", requestState=state)])

    def test_completed_checkpoint_states_remain_compatible(self):
        for state, error in (("complete", ""), ("failed", "controlled request failure")):
            result = score([self.writing()], [dict(caseId="controlled", response="", error=error, requestState=state)])
            self.assertEqual(result["counts"]["request_failures"], 1 if error else 0)

    def test_contradictory_checkpoint_state_is_rejected(self):
        for state, error in (("complete", "request failed"), ("failed", "")):
            with self.assertRaises(ValueError):
                score([self.writing()], [dict(caseId="controlled", response="", error=error, requestState=state)])

    def test_empty_response_is_a_format_failure(self):
        result = self.run_case(self.writing(), "")
        self.assertEqual(result["counts"]["format_failures"], 1)
        self.assertEqual(result["counts"]["detected_errors"], 0)

    def test_repeated_quotes_can_locate_distinct_gold_spans(self):
        rows = [dict(type="issue", quote="They goes", fix="They go") for _ in range(2)]
        result = self.run_case(self.writing(), rows)
        self.assertEqual(result["counts"]["detected_errors"], 2)

    def test_numeric_option_label_is_a_format_failure(self):
        result = self.run_case(self.reading(), [dict(type="option", label=1, verdict="correct")])
        self.assertEqual(result["counts"]["format_failures"], 1)
        self.assertEqual(result["counts"]["correct_reading"], 0)

    def test_contradictory_duplicate_option_is_not_a_correct_answer(self):
        rows = [dict(type="option", label="A", verdict="correct"),
                dict(type="option", label="A", verdict="wrong")]
        self.assertEqual(self.run_case(self.reading(), rows)["counts"]["correct_reading"], 0)

    def test_inconsistent_gold_offset_is_rejected(self):
        case = self.writing()
        case["errors"][0]["start"] = 1
        with self.assertRaises(ValueError): self.run_case(case, [])


    def test_unknown_extension_cannot_become_an_issue_in_reading(self):
        rows = [dict(type="option", label="A", verdict="correct"), dict(type="issue", extension="ignored")]
        result = self.run_case(self.reading(), rows)
        self.assertEqual(result["counts"]["format_failures"], 0)
        self.assertEqual(result["counts"]["suggestions"], 0)

    def test_partial_options_do_not_count_as_full_option_review(self):
        result = self.run_case(self.reading(), [dict(type="option", label="A", verdict="correct")])
        self.assertEqual(result["metrics"]["reading_answer_accuracy"]["value"], 1)
        self.assertEqual(result["metrics"]["full_options_reading_accuracy"]["value"], 0)
        self.assertEqual(result["metrics"]["reading_option_coverage"]["value"], 0.25)

    def test_duplicate_fields_and_mixed_malformed_records_discard_the_case(self):
        for response in ('{"type":"option","label":"A","label":"B","verdict":"correct"}',
                         '{"type":"option","label":"A","verdict":"correct"}\n{unfinished'):
            result = self.run_case(self.reading(), response)
            self.assertEqual(result["counts"]["format_failures"], 1)
            self.assertEqual(result["counts"]["correct_reading"], 0)

    def test_nontext_and_oversize_predictions_are_case_failures(self):
        case = self.writing()
        for response in (None, 1, "x" * 60001):
            result = score([case], [dict(caseId=case["id"], response=response)])
            self.assertEqual(result["counts"]["format_failures"], 1)
            self.assertEqual(result["metrics"]["exact_gold_span_miss_rate"]["value"], 1)

    def test_request_failure_is_separate_and_partial_text_is_not_scored(self):
        case = self.writing()
        response = json.dumps(dict(type="issue", quote="They goes", fix="They go"))
        result = score([case], [dict(caseId=case["id"], response=response, error="controlled request failure")])
        self.assertEqual(result["counts"]["request_failures"], 1)
        self.assertEqual(result["counts"]["format_failures"], 0)
        self.assertEqual(result["counts"]["detected_errors"], 0)

    def test_noop_is_not_counted_as_an_applied_repair(self):
        result = self.run_case(self.writing(), [dict(type="issue", quote="They goes", fix="They goes")])
        self.assertEqual(result["counts"]["no_ops"], 1)
        self.assertEqual(result["counts"]["applied"], 0)
        self.assertEqual(result["counts"]["successful_fixes"], 0)

    def test_overlapping_context_does_not_hide_literal_citation_or_noop(self):
        case = self.writing()
        rows = [dict(type="issue", quote=case["answer"], fix="A rewritten paragraph.", level="better"),
                dict(type="issue", quote="They goes", fix="They go"),
                dict(type="issue", quote="They goes", fix="They goes")]
        result = self.run_case(case, rows)
        self.assertEqual(result["counts"]["detected_errors"], 2)
        self.assertEqual(result["counts"]["applicable_detected_errors"], 0)
        self.assertEqual(result["counts"]["no_ops"], 1)
        self.assertEqual(result["counts"]["overlap_skipped"], 2)

    def test_optional_suggestion_is_separate_from_required_control_alert(self):
        case = dict(id="clean", kind="writing", material="Original exercise", answer="They go.", errors=[], negativeControl=True)
        for level, passed in (("better", 1), ("must", 0)):
            result = self.run_case(case, [dict(type="issue", quote="They go", fix="They leave", level=level)])
            self.assertEqual(result["counts"]["clean_controls_passed"], passed)
            self.assertEqual(result["counts"]["optional_suggestions"], passed)
            self.assertTrue(result["cases"][0]["review"])
        self.assertEqual(self.run_case(case, "")["counts"]["clean_controls_passed"], 0)

    def test_unlisted_alternative_is_flagged_for_review(self):
        result = self.run_case(self.writing(), [dict(type="issue", quote="They goes", fix="They leave")])
        self.assertEqual(result["counts"]["detected_errors"], 1)
        self.assertEqual(result["counts"]["successful_fixes"], 0)
        self.assertIn("unenumerated_fix_requires_review", [entry["reason"] for entry in result["cases"][0]["review"]])

    def test_wrong_evidence_does_not_receive_target_span_credit(self):
        case = self.reading()
        case["evidence"] = [dict(quote="some students", start=case["material"].index("some students"))]
        for quote, covered in ((case["material"], 1), ("attended", 0)):
            result = self.run_case(case, [dict(type="locate", quote=quote), dict(type="option", label="A", verdict="correct")])
            self.assertEqual(result["metrics"]["evidence_span_coverage"]["value"], covered)

    def test_gold_identity_changes_when_reference_changes(self):
        case = self.writing()
        first = self.run_case(case, [dict(type="score", band=8)])["gold_cases_sha256"]
        case["errors"][0]["fixes"] = ["They went"]
        second = self.run_case(case, [dict(type="score", band=8)])["gold_cases_sha256"]
        self.assertNotEqual(first, second)

    def controlled_predictions(self, cases):
        predictions = []
        for case in cases:
            rows = [dict(type="issue", quote=error["quote"], fix=error["fixes"][0], level="must") for error in case["errors"]]
            if case["kind"] == "reading":
                rows += [dict(type="locate", quote=item["quote"]) for item in case.get("evidence", [])]
                rows += [dict(type="option", label=label, verdict="correct" if label == case["correct"] else "wrong") for label in case["options"]]
            if not rows: rows.append(dict(type="score", band=14))
            predictions.append(dict(caseId=case["id"], response="\n".join(json.dumps(row) for row in rows)))
        return predictions

    def test_original_v2_corpus_profiles_and_controlled_scoring(self):
        root = Path(__file__).resolve().parents[1]
        cases = json.loads((root / "app/testdata/coach-evaluation-v2.json").read_text(encoding="utf-8"))["cases"]
        self.assertEqual(len(validate_cases(cases)), 12)
        for kind in ("writing", "translation", "reading"):
            self.assertEqual(sum(case["kind"] == kind for case in cases), 4)
        for case in cases:
            if case["kind"] == "writing":
                words = len(re.findall(r"[A-Za-z]+(?:'[A-Za-z]+)?", case["answer"]))
                self.assertGreaterEqual(words, 150)
                self.assertLessEqual(words, 200)
            elif case["kind"] == "reading":
                self.assertGreaterEqual(len(case["material"].split()), 250)
            else:
                self.assertGreaterEqual(case["material"].count("。"), 4)
        result = score(cases, self.controlled_predictions(cases))
        for name in ("quote_accuracy", "exact_gold_span_match_rate", "enumerated_fix_match_rate", "reading_answer_accuracy",
                     "full_options_reading_accuracy", "evidence_span_coverage", "clean_control_success"):
            self.assertEqual(result["metrics"][name]["value"], 1, name)
        self.assertEqual(result["metrics"]["exact_gold_span_miss_rate"]["value"], 0)

    def test_cli_versions_are_explicit_and_legacy_result_is_unchanged(self):
        root = Path(__file__).resolve().parents[1]
        folder = root / ".tools"
        folder.mkdir(exist_ok=True)
        case = self.writing()
        predictions = self.controlled_predictions([case])
        with tempfile.TemporaryDirectory(prefix="evaluation-test-", dir=folder) as temp:
            path = Path(temp)
            self.assertTrue(path.resolve().is_relative_to(folder.resolve()))
            gold = path / "gold.json"
            predicted = path / "predictions.json"
            gold.write_text(json.dumps(dict(version=2, cases=[case])), encoding="utf-8")
            predicted.write_text(json.dumps(predictions), encoding="utf-8")
            command = [sys.executable, str(root / "tools/evaluate_coach.py"), str(predicted), "--gold", str(gold)]
            current = json.loads(subprocess.run(command, check=True, capture_output=True, text=True).stdout)
            self.assertEqual(current["scoring_version"], 2)
            self.assertEqual(current["dataset_version"], 2)
            previous = json.loads(subprocess.run(command + ["--scoring-version", "1"], check=True, capture_output=True, text=True).stdout)
            self.assertEqual(previous, legacy_score([case], predictions))


if __name__ == "__main__": unittest.main()
