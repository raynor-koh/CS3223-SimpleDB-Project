"""Guard against reporting incomplete, unverified, or warmup-contaminated results."""

import copy
import unittest

from run import METHODS, validate_and_summarize


class SummaryValidationTest(unittest.TestCase):
    def records(self):
        records = []
        for experiment in (1, 2):
            for method in METHODS:
                for warmup, times in (("true", [99_000_000_000]),
                                      ("false", [1_000_000, 2_000_000, 3_000_000])):
                    for number, elapsed in enumerate(times, 1):
                        records.append({
                            "experiment": str(experiment), "dataset_id": "n32", "fact_rows": "32",
                            "profile": method, "plan_id": f"{experiment}-{method}",
                            "join_order": "benchmid->benchfact", "run": str(number), "warmup": warmup,
                            "elapsed_ns": str(elapsed), "rows": "60", "result_verified": "true",
                            "block_size": "400", "buffers": "8", "jdk": "test",
                            "cache_policy": "fresh_engine_os_cache_uncontrolled",
                        })
        return records

    def test_warmups_are_excluded_and_units_are_milliseconds(self):
        summary = validate_and_summarize(self.records(), [32], 1, 3)
        self.assertEqual(len(summary), 8)
        self.assertEqual(summary[0]["median_ms"], 2)
        self.assertEqual(summary[0]["p25_ms"], 1.5)
        self.assertEqual(summary[0]["p75_ms"], 2.5)
        self.assertEqual(summary[0]["stdev_ms"], 1)

    def test_missing_profiles_are_rejected(self):
        rows = [row for row in self.records() if row["profile"] != "HASH"]
        with self.assertRaisesRegex(ValueError, "Missing profiles"):
            validate_and_summarize(rows, [32], 1, 3)

    def test_duplicate_repetitions_are_rejected(self):
        rows = self.records()
        rows.append(copy.copy(rows[-1]))
        with self.assertRaisesRegex(ValueError, "Missing/duplicate repetitions"):
            validate_and_summarize(rows, [32], 1, 3)

    def test_unverified_timings_are_rejected(self):
        rows = self.records()
        rows[-1]["result_verified"] = "false"
        with self.assertRaisesRegex(ValueError, "Unverified"):
            validate_and_summarize(rows, [32], 1, 3)

    def test_inconsistent_plans_are_rejected(self):
        rows = self.records()
        rows[-1]["plan_id"] = "changed"
        with self.assertRaisesRegex(ValueError, "Inconsistent plan_id"):
            validate_and_summarize(rows, [32], 1, 3)

    def test_profiles_must_return_the_same_number_of_rows(self):
        rows = self.records()
        for row in rows:
            if row["profile"] == "HASH":
                row["rows"] = "59"
        with self.assertRaisesRegex(ValueError, "disagree on output"):
            validate_and_summarize(rows, [32], 1, 3)


if __name__ == "__main__":
    unittest.main()
