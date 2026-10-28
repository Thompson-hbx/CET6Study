import copy
import unittest

from prepare_evidence_review import candidates


class EvidenceReviewTest(unittest.TestCase):
    def pack(self):
        return {"papers": [{"stableId": "test", "revision": 2, "reviewed": True,
            "sourceName": "Original fixture", "sourceUrl": "local:fixture", "passages": [{"code": "1",
            "content": "First sentence.\n\nLibraries offer quiet rooms! Final sentence.",
            "questions": [{"number": 46, "stem": "What do libraries offer?", "referenceAnswer": "A",
                "options": [{"label": "A", "text": "Quiet rooms"}]}]}]}]}

    def test_every_quote_is_exact_pending_and_source_pack_is_unchanged(self):
        pack = self.pack()
        before = copy.deepcopy(pack)
        review = candidates(pack)
        self.assertEqual(before, pack)
        row = review["questions"][0]
        self.assertEqual("pending-human-review", row["status"])
        self.assertEqual("Libraries offer quiet rooms!", row["candidates"][0]["quote"])
        content = pack["papers"][0]["passages"][0]["content"]
        for candidate in row["candidates"]:
            self.assertEqual(candidate["quote"], content[candidate["start"]:candidate["end"]])
            self.assertEqual("pending-human-review", candidate["status"])
            self.assertIn("local:fixture", candidate["source"])
        self.assertNotIn("evidence", row)

    def test_unreviewed_papers_are_not_promoted_and_confirmed_evidence_is_preserved(self):
        pack = self.pack()
        pack["papers"][0]["reviewed"] = False
        self.assertEqual([], candidates(pack)["questions"])
        pack["papers"][0]["reviewed"] = True
        q = pack["papers"][0]["passages"][0]["questions"][0]
        q.update(evidence="First sentence.", evidenceSource="local:fixture")
        self.assertEqual([], candidates(pack)["questions"])
        q["evidence"] = "Invented."
        with self.assertRaises(ValueError):
            candidates(pack)


if __name__ == "__main__":
    unittest.main()
