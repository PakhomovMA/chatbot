#!/usr/bin/env python3
import argparse
import json
import random
from pathlib import Path

SEED = 20261123
RESAMPLES = 10000
EQUIVALENCE_MARGIN = 0.02
PRIMARY_METRICS = ["Recall@5", "MRR", "nDCG@10"]


def paired_outcomes(a, b, mode="HYBRID"):
    if a["chunkSize"] != b["chunkSize"] or a["overlap"] != b["overlap"]:
        raise ValueError("Reports must use the same chunking")
    a_rows = [row for row in a["outcomes"] if row["mode"] == mode]
    b_rows = [row for row in b["outcomes"] if row["mode"] == mode]
    for rows in (a_rows, b_rows):
        ids = [row["id"] for row in rows]
        if len(ids) != len(set(ids)):
            raise ValueError("Duplicate question IDs in a report")
    a_by_id = {row["id"]: row for row in a_rows}
    b_by_id = {row["id"]: row for row in b_rows}
    if a_by_id.keys() != b_by_id.keys():
        raise ValueError("Reports must contain the same question IDs")
    return [(a_by_id[id], b_by_id[id]) for id in sorted(a_by_id)]


def is_negative(row):
    return "negative" in row["categories"] or "near-topic" in row["categories"]


def metric_value(row, field, negative):
    if is_negative(row) != negative:
        return None
    value = row[field]
    return float(value) if value is not None else None


def bootstrap_metric(pairs, field, negative, resamples, seed):
    values = []
    for a, b in pairs:
        a_value = metric_value(a, field, negative)
        b_value = metric_value(b, field, negative)
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
    deltas.sort()
    ci = [deltas[int(0.025 * (resamples - 1))], deltas[int(0.975 * (resamples - 1))]]
    if ci[0] > 0 or ci[1] < 0:
        status = "different"
    elif ci[0] >= -EQUIVALENCE_MARGIN and ci[1] <= EQUIVALENCE_MARGIN:
        status = "equivalent"
    else:
        status = "inconclusive"
    return {"samples": len(values), "delta": delta, "ci95": ci, "status": status}


def compare_reports(a, b, resamples=RESAMPLES, seed=SEED):
    pairs = paired_outcomes(a, b)
    definitions = [
        ("Recall@5", "recallAt5", False),
        ("MRR", "reciprocalRank", False),
        ("nDCG@10", "ndcgAt10", False),
        ("Negative sufficiency", "sufficient", True),
    ]
    metrics = {}
    for index, (name, field, negative) in enumerate(definitions):
        metrics[name] = bootstrap_metric(pairs, field, negative, resamples, seed + index)
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
        "primaryMetrics": PRIMARY_METRICS,
        "metrics": metrics,
        "verdict": verdict,
    }


def render_markdown(result):
    lines = [
        f"A: `{result['aFingerprint']}`  ",
        f"B (minus A): `{result['bFingerprint']}`",
        "",
        f"Chunking: {result['chunkSize']}/{result['overlap']}; paired bootstrap: "
        f"{result['resamples']} resamples, seed {result['seed']}, equivalence ±{result['equivalenceMargin']}.",
        "",
        "| Metric | N | B−A | 95% CI | Status |",
        "|---|---:|---:|---:|---|",
    ]
    for name, metric in result["metrics"].items():
        if metric["delta"] is None:
            lines.append(f"| {name} | 0 | n/a | n/a | not-applicable |")
        else:
            low, high = metric["ci95"]
            lines.append(f"| {name} | {metric['samples']} | {metric['delta']:.4f} | "
                         f"[{low:.4f}, {high:.4f}] | {metric['status']} |")
    lines.extend(["", f"**Verdict: {result['verdict']}.**", ""])
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
