#!/usr/bin/env python3
import argparse
import bisect
import json
import random
from pathlib import Path

SEED = 20261123
RESAMPLES = 10000
EQUIVALENCE_MARGIN = 0.02
PRIMARY_METRICS = ["Recall@5", "MRR", "nDCG@10"]
# Metrics whose value depends on the sufficient-cosine each run was given. That threshold was fitted on the
# calibration split, so these are compared on the validation split only.
THRESHOLD_SPLIT = "validation"


def paired_outcomes(a, b, mode="HYBRID"):
    if a["chunkSize"] != b["chunkSize"] or a["overlap"] != b["overlap"]:
        raise ValueError("Reports must use the same chunking")
    a_rows = [row for row in a["outcomes"] if row["mode"] == mode]
    b_rows = [row for row in b["outcomes"] if row["mode"] == mode]
    for rows in (a_rows, b_rows):
        ids = [row["id"] for row in rows]
        if len(ids) != len(set(ids)):
            raise ValueError("Duplicate question IDs in a report")
        if any("negative" not in row for row in rows):
            raise ValueError("Report outcomes must carry the 'negative' flag; rerun ragEval")
    a_by_id = {row["id"]: row for row in a_rows}
    b_by_id = {row["id"]: row for row in b_rows}
    if a_by_id.keys() != b_by_id.keys():
        raise ValueError("Reports must contain the same question IDs")
    pairs = [(a_by_id[id], b_by_id[id]) for id in sorted(a_by_id)]
    for a_row, b_row in pairs:
        if a_row["negative"] != b_row["negative"] or a_row.get("split") != b_row.get("split"):
            raise ValueError(f"Question {a_row['id']} differs in polarity or split between the reports")
    return pairs


def metric_value(row, field, negative, split=None):
    if row["negative"] != negative:
        return None
    if split is not None and row.get("split") != split:
        return None
    value = row[field]
    return float(value) if value is not None else None


def status_of(ci):
    if ci[0] > 0 or ci[1] < 0:
        return "different"
    if ci[0] >= -EQUIVALENCE_MARGIN and ci[1] <= EQUIVALENCE_MARGIN:
        return "equivalent"
    return "inconclusive"


def percentile_ci(deltas, resamples):
    deltas.sort()
    return [deltas[int(0.025 * (resamples - 1))], deltas[int(0.975 * (resamples - 1))]]


def bootstrap_metric(pairs, field, negative, resamples, seed, split=None):
    values = []
    for a, b in pairs:
        a_value = metric_value(a, field, negative, split)
        b_value = metric_value(b, field, negative, split)
        if a_value is not None and b_value is not None:
            values.append((a_value, b_value))
    if not values:
        return {"samples": 0, "delta": None, "ci95": None, "status": "not-applicable"}
    delta = sum(b - a for a, b in values) / len(values)
    rng = random.Random(seed)
    deltas = []
    for _ in range(resamples):
        sample = [values[rng.randrange(len(values))] for _ in values]
        deltas.append(sum(b - a for a, b in sample) / len(sample))
    ci = percentile_ci(deltas, resamples)
    return {"samples": len(values), "delta": delta, "ci95": ci, "status": status_of(ci)}


def auc(rows):
    """Probability that a positive's topCosine exceeds a negative's (ties count half); None without both."""
    positives = sorted(row["topCosine"] for row in rows if not row["negative"])
    negatives = [row["topCosine"] for row in rows if row["negative"]]
    if not positives or not negatives:
        return None
    wins = 0.0
    for score in negatives:
        below_or_equal = bisect.bisect_right(positives, score)
        below = bisect.bisect_left(positives, score)
        wins += (len(positives) - below_or_equal) + 0.5 * (below_or_equal - below)
    return wins / (len(positives) * len(negatives))


def bootstrap_auc(pairs, resamples, seed):
    """Threshold-free separability of positives from negatives by topCosine, resampling questions in pairs."""
    a_value, b_value = auc([a for a, _ in pairs]), auc([b for _, b in pairs])
    if a_value is None or b_value is None:
        return {"samples": 0, "delta": None, "ci95": None, "status": "not-applicable"}
    rng = random.Random(seed)
    deltas = []
    while len(deltas) < resamples:
        sample = [pairs[rng.randrange(len(pairs))] for _ in pairs]
        a_sample, b_sample = auc([a for a, _ in sample]), auc([b for _, b in sample])
        if a_sample is not None and b_sample is not None:
            deltas.append(b_sample - a_sample)
    ci = percentile_ci(deltas, resamples)
    return {"samples": len(pairs), "delta": b_value - a_value, "ci95": ci, "status": status_of(ci),
            "a": a_value, "b": b_value}


def compare_reports(a, b, resamples=RESAMPLES, seed=SEED):
    pairs = paired_outcomes(a, b)
    definitions = [
        ("Recall@5", "recallAt5", False, None),
        ("MRR", "reciprocalRank", False, None),
        ("nDCG@10", "ndcgAt10", False, None),
        ("Negative sufficiency", "sufficient", True, THRESHOLD_SPLIT),
    ]
    metrics = {}
    for index, (name, field, negative, split) in enumerate(definitions):
        metrics[name] = bootstrap_metric(pairs, field, negative, resamples, seed + index, split)
    # Diagnostics put the verdict metrics in context and never decide it: positive sufficiency is the cost of a
    # stricter threshold, and AUC separates what the embedding does from where each threshold was put.
    diagnostics = {
        "Positive sufficiency": bootstrap_metric(pairs, "sufficient", False, resamples, seed + len(definitions),
                                                 THRESHOLD_SPLIT),
        "Sufficiency AUC (topCosine)": bootstrap_auc(pairs, resamples, seed + len(definitions) + 1),
    }
    primary = [metrics[name] for name in PRIMARY_METRICS]
    if any(metric["status"] == "different" for metric in metrics.values()):
        verdict = "distinguishable"
    elif all(metric["status"] == "equivalent" for metric in primary):
        verdict = "equivalent"
    else:
        verdict = "inconclusive"
    return {
        "aFingerprint": a["fingerprint"],
        "bFingerprint": b["fingerprint"],
        "chunkSize": a["chunkSize"],
        "overlap": a["overlap"],
        "splitSeed": a.get("splitSeed"),
        "seed": seed,
        "resamples": resamples,
        "equivalenceMargin": EQUIVALENCE_MARGIN,
        "thresholdSplit": THRESHOLD_SPLIT,
        "primaryMetrics": PRIMARY_METRICS,
        "metrics": metrics,
        "diagnostics": diagnostics,
        "verdict": verdict,
    }


def metric_rows(metrics):
    lines = ["| Metric | N | B−A | 95% CI | Status |", "|---|---:|---:|---:|---|"]
    for name, metric in metrics.items():
        if metric["delta"] is None:
            lines.append(f"| {name} | 0 | n/a | n/a | not-applicable |")
        else:
            low, high = metric["ci95"]
            lines.append(f"| {name} | {metric['samples']} | {metric['delta']:.4f} | "
                         f"[{low:.4f}, {high:.4f}] | {metric['status']} |")
    return lines


def render_markdown(result):
    lines = [
        f"A: `{result['aFingerprint']}`  ",
        f"B (minus A): `{result['bFingerprint']}`",
        "",
        f"Chunking: {result['chunkSize']}/{result['overlap']}; paired bootstrap: "
        f"{result['resamples']} resamples, seed {result['seed']}, equivalence ±{result['equivalenceMargin']}. "
        f"Sufficiency rows use the {result['thresholdSplit']} split only.",
        "",
    ]
    lines.extend(metric_rows(result["metrics"]))
    lines.extend(["", f"**Verdict: {result['verdict']}.**", "", "Diagnostics (not part of the verdict):", ""])
    lines.extend(metric_rows(result["diagnostics"]))
    separability = result["diagnostics"]["Sufficiency AUC (topCosine)"]
    if separability["delta"] is not None:
        lines.extend(["", f"AUC: A {separability['a']:.4f}, B {separability['b']:.4f}."])
    lines.append("")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="Paired bootstrap comparison of two ragEval JSON reports")
    parser.add_argument("a", type=Path)
    parser.add_argument("b", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    a = json.loads(args.a.read_text())
    b = json.loads(args.b.read_text())
    markdown = render_markdown(compare_reports(a, b))
    if args.output:
        args.output.write_text(markdown)
    else:
        print(markdown, end="")


if __name__ == "__main__":
    main()
