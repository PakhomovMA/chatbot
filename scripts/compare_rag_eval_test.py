import unittest

from compare_rag_eval import compare_reports, paired_outcomes, render_markdown


def outcome(id, recall=1.0, mrr=1.0, ndcg=1.0, sufficient=False, negative=False):
    return {
        "id": id,
        "mode": "HYBRID",
        "categories": ["near-topic", "negative"] if negative else ["multi-chunk"],
        "recallAt5": bool(recall),
        "reciprocalRank": mrr,
        "ndcgAt10": ndcg,
        "sufficient": sufficient,
    }


def report(outcomes, fingerprint="model"):
    return {
        "fingerprint": fingerprint,
        "chunkSize": 800,
        "overlap": 100,
        "splitSeed": 20261123,
        "outcomes": outcomes,
    }


class ComparisonTest(unittest.TestCase):
    def test_paired_delta_is_b_minus_a(self):
        a = report([outcome("q1", recall=0, mrr=0.5, ndcg=0.25)])
        b = report([outcome("q1", recall=1, mrr=1.0, ndcg=0.75)])

        result = compare_reports(a, b, resamples=100)

        self.assertEqual(result["metrics"]["Recall@5"]["delta"], 1.0)
        self.assertEqual(result["metrics"]["MRR"]["delta"], 0.5)
        self.assertEqual(result["metrics"]["nDCG@10"]["delta"], 0.5)

    def test_mismatched_and_duplicate_ids_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "same question IDs"):
            paired_outcomes(report([outcome("q1")]), report([outcome("q2")]))
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            paired_outcomes(report([outcome("q1"), outcome("q1")]), report([outcome("q1")]))

    def test_metric_without_applicable_questions_is_not_computed(self):
        a = report([outcome("q1")])
        b = report([outcome("q1")])

        result = compare_reports(a, b, resamples=100)

        metric = result["metrics"]["Negative sufficiency"]
        self.assertIsNone(metric["delta"])
        self.assertIsNone(metric["ci95"])
        self.assertEqual(metric["status"], "not-applicable")

    def test_bootstrap_is_deterministic(self):
        outcomes_a = [outcome(f"q{i}", recall=i % 2, mrr=1 / (i + 1), ndcg=i / 10) for i in range(10)]
        outcomes_b = [outcome(f"q{i}", recall=(i + 1) % 2, mrr=1 / (i + 2), ndcg=(i + 1) / 11) for i in range(10)]

        first = compare_reports(report(outcomes_a), report(outcomes_b), resamples=200)
        second = compare_reports(report(outcomes_a), report(outcomes_b), resamples=200)

        self.assertEqual(first, second)

    def test_difference_equivalence_and_inconclusive_rules(self):
        different = compare_reports(report([outcome("q1", recall=0)]), report([outcome("q1")]), resamples=100)
        self.assertEqual(different["metrics"]["Recall@5"]["status"], "different")
        self.assertEqual(different["verdict"], "distinguishable")

        equivalent = compare_reports(report([outcome("q1")]), report([outcome("q1")]), resamples=100)
        self.assertEqual(equivalent["metrics"]["Recall@5"]["status"], "equivalent")
        self.assertEqual(equivalent["verdict"], "equivalent")

        a = report([outcome(f"q{i}", recall=i % 2, mrr=0.5, ndcg=0.5) for i in range(20)])
        b = report([outcome(f"q{i}", recall=(i + 1) % 2, mrr=0.5, ndcg=0.5) for i in range(20)])
        inconclusive = compare_reports(a, b, resamples=500)
        self.assertEqual(inconclusive["metrics"]["Recall@5"]["status"], "inconclusive")
        self.assertEqual(inconclusive["verdict"], "inconclusive")

    def test_negative_sufficiency_can_distinguish_models_when_ranking_is_inconclusive(self):
        positives_a = [outcome(f"q{i}", recall=i % 2, mrr=0.5, ndcg=0.5) for i in range(20)]
        positives_b = [outcome(f"q{i}", recall=(i + 1) % 2, mrr=0.5, ndcg=0.5) for i in range(20)]
        negatives_a = [outcome(f"n{i}", sufficient=False, negative=True) for i in range(24)]
        negatives_b = [outcome(f"n{i}", sufficient=True, negative=True) for i in range(24)]

        result = compare_reports(report(positives_a + negatives_a), report(positives_b + negatives_b), resamples=500)

        self.assertEqual(result["metrics"]["Recall@5"]["status"], "inconclusive")
        self.assertEqual(result["metrics"]["Negative sufficiency"]["status"], "different")
        self.assertEqual(result["verdict"], "distinguishable")

    def test_parity_requires_all_primary_metrics(self):
        a = report([outcome("q1", recall=1, mrr=0.5, ndcg=0.5)])
        b = report([outcome("q1", recall=1, mrr=0.5, ndcg=0.5)])

        result = compare_reports(a, b, resamples=100)

        self.assertEqual(result["verdict"], "equivalent")
        self.assertEqual(result["primaryMetrics"], ["Recall@5", "MRR", "nDCG@10"])

    def test_markdown_contains_fingerprints_ci_and_verdict(self):
        a = report([outcome("q1")], fingerprint="v1")
        b = report([outcome("q1")], fingerprint="v2")

        markdown = render_markdown(compare_reports(a, b, resamples=100))

        self.assertIn("v1", markdown)
        self.assertIn("v2", markdown)
        self.assertIn("95% CI", markdown)
        self.assertIn("equivalent", markdown)


if __name__ == "__main__":
    unittest.main()
