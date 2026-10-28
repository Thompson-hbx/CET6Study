"""Controlled scoring tests; these are not model-quality results."""
import json
import unittest
from evaluate_coach import GOLD, evaluate


class EvaluationTest(unittest.TestCase):
    def setUp(self):
        self.cases = json.loads(GOLD.read_text(encoding="utf-8"))["cases"]
        self.predictions = []
        for case in self.cases:
            rows = [dict(type="issue", quote=e["quote"], fix=e["fixes"][0]) for e in case["errors"]]
            for error in case["errors"]:
                self.assertEqual(error["quote"], case["answer"][error["start"]:error["start"] + len(error["quote"])])
            if case["kind"] == "reading":
                rows += [dict(type="locate", quote=case["material"]), dict(type="option", label=case["correct"], verdict="correct")]
            self.predictions.append(dict(caseId=case["id"], response="\n".join(json.dumps(r) for r in rows)))

    def test_controlled_expected_fixes(self):
        metrics = evaluate(self.cases, self.predictions)["metrics"]
        self.assertEqual(metrics["quote_accuracy"]["value"], 1)
        self.assertEqual(metrics["localization_precision"]["value"], 1)
        self.assertEqual(metrics["omission_rate"]["value"], 0)
        self.assertEqual(metrics["accepted_fix_success"]["value"], 1)
        self.assertEqual(metrics["reading_answer_accuracy"]["value"], 1)

    def test_bad_quotes_and_fixes_reduce_scores(self):
        self.predictions[0]["response"] = '{"type":"issue","quote":"Invented text","fix":"x"}'
        result = evaluate(self.cases, self.predictions)
        self.assertLess(result["metrics"]["quote_accuracy"]["value"], 1)
        self.assertGreater(result["metrics"]["omission_rate"]["value"], 0)
        self.predictions[1]["response"] = '{"type":"issue","quote":"can studies","fix":"can studying"}'
        self.assertLess(evaluate(self.cases, self.predictions)["metrics"]["accepted_fix_success"]["value"], 1)

    def test_missing_and_duplicate_cases_rejected(self):
        with self.assertRaises(ValueError): evaluate(self.cases, self.predictions[:-1])
        with self.assertRaises(ValueError): evaluate(self.cases, self.predictions[:-1] + [self.predictions[0]])

    def test_malformed_response_counts_as_failure_and_omissions(self):
        self.predictions[0]["response"] += "\n{unfinished"
        result = evaluate(self.cases, self.predictions)
        self.assertEqual(result["counts"]["format_failures"], 1)
        self.assertGreater(result["metrics"]["omission_rate"]["value"], 0)


if __name__ == "__main__": unittest.main()
