"""Independently recomputes the top-10 lists and compares them with the files produced by the Java app.

Uses only the Python standard library and exact arithmetic (Fraction / Decimal), so it shares no code or
floating-point behaviour with the Java implementation.

Usage: python scripts/verify_results.py data/ad_data.csv results/
"""
import csv
import math
import sys
from collections import defaultdict
from decimal import Decimal
from fractions import Fraction
from pathlib import Path

HEADER = ["campaign_id", "total_impressions", "total_clicks", "total_spend", "total_conversions", "CTR", "CPA"]


def aggregate(input_path):
    totals = defaultdict(lambda: [0, 0, Decimal(0), 0])
    with open(input_path, newline="", encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            t = totals[row["campaign_id"].strip()]
            t[0] += int(row["impressions"])
            t[1] += int(row["clicks"])
            t[2] += Decimal(row["spend"])
            t[3] += int(row["conversions"])
    return totals


def round_half_up(value, decimals):
    """Rounds a non-negative Fraction exactly, half up, and formats it with a fixed number of decimals."""
    scaled = math.floor(value * 10 ** decimals + Fraction(1, 2))
    return str(Decimal(scaled).scaleb(-decimals))


def to_row(campaign_id, t):
    impressions, clicks, spend, conversions = t
    ctr = round_half_up(Fraction(clicks, impressions), 4) if impressions else ""
    cpa = round_half_up(Fraction(spend) / conversions, 2) if conversions else ""
    return [campaign_id, str(impressions), str(clicks), str(spend.quantize(Decimal("0.01"))), str(conversions), ctr, cpa]


def expected_lists(totals):
    with_ctr = [(c, t) for c, t in totals.items() if t[0] > 0]
    with_cpa = [(c, t) for c, t in totals.items() if t[3] > 0]
    top_ctr = sorted(with_ctr, key=lambda ct: (-Fraction(ct[1][1], ct[1][0]), ct[0]))[:10]
    top_cpa = sorted(with_cpa, key=lambda ct: (Fraction(ct[1][2]) / ct[1][3], ct[0]))[:10]
    return [to_row(c, t) for c, t in top_ctr], [to_row(c, t) for c, t in top_cpa]


def read_csv(path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.reader(f))


def main(input_path, results_dir):
    expected_ctr, expected_cpa = expected_lists(aggregate(input_path))
    ok = True
    for name, expected in (("top10_ctr.csv", expected_ctr), ("top10_cpa.csv", expected_cpa)):
        actual = read_csv(Path(results_dir) / name)
        if actual == [HEADER] + expected:
            print(f"{name}: OK ({len(expected)} rows match)")
        else:
            ok = False
            print(f"{name}: MISMATCH")
            for row in [HEADER] + expected:
                print("  expected", ",".join(row))
            for row in actual:
                print("  actual  ", ",".join(row))
    return 0 if ok else 1


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2]))
