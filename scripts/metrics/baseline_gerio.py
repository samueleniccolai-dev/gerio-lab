#!/usr/bin/env python3
"""Extracts the baseline of the experiment from Gerio (private repository): one row per cycle.

Run once, with read access to Gerio, from a clone of it:

    python3 scripts/metrics/baseline_gerio.py --gerio ../gerio --repo samueleniccolai-dev/gerio

Sources: the cycle reports (docs/ciclo/NNN-rapporto.md), DECISIONI.md, and the GitHub API
(pull requests, their files, the ci runs of their branches and the timing of each run).
The output, metrics/baseline-gerio.csv, is committed: the lab never reads Gerio itself.
"""
import argparse
import csv
import datetime
import json
import os
import re
import subprocess
import sys

T0 = "2026-10-04T14:06:28Z"  # end of the v2 set-up, start of cycle 1 (docs/ESPERIMENTO.md)
CYCLES = {  # cycle -> (PR, goal of the 4/10 plan, or the kind of off-plan work)
    1: (9, "T1"), 2: (13, "T2"), 3: (14, "T3"), 4: (15, "T4"), 5: (16, "stack"),
    6: (17, "T5"), 7: (19, "T5"), 8: (20, "T6"), 9: (23, "T6"), 10: (24, "T7"),
    11: (25, "deriva"), 12: (27, "deriva"), 13: (30, "deriva"), 14: (31, "deriva"),
}
FIELDS = [
    "ciclo", "traguardo", "pr", "pr_creata", "pr_mergiata", "ore_dal_t0", "commit",
    "test_totali", "test_delta", "righe_main_aggiunte", "righe_main_tolte", "righe_test_aggiunte",
    "file", "ci_run", "ci_rossi", "ci_minuti", "rilavorazioni", "revisore_bloccanti", "revisore_dubbi",
    "decisioni", "decisioni_cambiate", "porte_senso_unico", "minuti_umani_stimati", "parole_rapporto",
]


def gh(path):
    out = subprocess.run(["gh", "api", "--paginate", path], capture_output=True, text=True, check=True).stdout
    # --paginate prints one JSON document per page: read them all, then merge them.
    decoder, values, i = json.JSONDecoder(), [], 0
    out = out.strip()
    while i < len(out):
        value, i = decoder.raw_decode(out, i)
        values.append(value)
        while i < len(out) and out[i].isspace():
            i += 1
    if values and all(isinstance(v, list) for v in values):
        return [item for v in values for item in v]
    merged = dict(values[0]) if values else {}
    for v in values[1:]:
        for key, item in v.items():
            if isinstance(item, list) and isinstance(merged.get(key), list):
                merged[key] = merged[key] + item
    return merged


def parse_time(value):
    return datetime.datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=datetime.timezone.utc)


def report_path(gerio, cycle):
    for name in (f"{cycle:03d}-rapporto.md",):
        path = os.path.join(gerio, "docs", "ciclo", name)
        if os.path.exists(path):
            return path
    return None


def section(text, number):
    m = re.search(rf"^## {number}\..*?$(.*?)(?=^## \d+\.|\Z)", text, re.S | re.M)
    return m.group(1) if m else ""


def metrics_row(text):
    """Cells of the single data row of the table in section 5 (Metriche del ciclo)."""
    rows = [l for l in section(text, 5).splitlines() if l.startswith("|") and not re.match(r"^\|\s*-", l)]
    if len(rows) < 2:
        return []
    return [c.strip() for c in rows[1].strip().strip("|").split("|")]


def first_int(text):
    m = re.search(r"\d+", text or "")
    return int(m.group()) if m else ""


def minutes(cell):
    found = [int(x) for x in re.findall(r"(\d+)\s*['’]", cell or "")]
    return sum(found) if found else ""


def tests_total(cell):
    """'534 (+7; erano 527)', '+3 (194 totali, …)', '265 → 313 (+48)', '+1 (HealthEndpointTest)'."""
    cell = cell or ""
    for pattern in (r"→\s*(\d+)", r"(\d+)\s+totali", r"^(\d+)\b"):
        m = re.search(pattern, cell)
        if m:
            return int(m.group(1))
    return ""


def tests_delta(cell):
    m = re.search(r"\(\+(\d+)|^\+(\d+)|\+(\d+)\s", cell or "")
    return int(next(g for g in m.groups() if g)) if m else ""


def paragraphs(sec, keyword):
    """Bullet paragraphs of a section whose first line contains `keyword`."""
    out, current = [], None
    for line in sec.splitlines():
        if line.startswith("- "):
            if current is not None:
                out.append(current)
            current = line if keyword in line.upper() else None
        elif current is not None:
            current += " " + line.strip()
    if current is not None:
        out.append(current)
    return out


def reviewer_counts(text):
    """Blockers of the first verdict (later re-checks only confirm fixes) and distinct doubts."""
    sec = section(text, 3)
    blockers = 0
    for para in paragraphs(sec, "BLOCCANT")[:1]:
        ids = set(re.findall(r"\bB\d+\b", para))
        if ids:
            blockers = len(ids)
            continue
        head = para.split(":", 1)[0] + ":" + (para.split(":", 1)[1][:20] if ":" in para else "")
        if re.search(r"nessun", head, re.I):
            continue
        n = re.search(r"BLOCCANTI\W*\(?\s*:?\**\s*(\d+)", para)
        blockers = int(n.group(1)) if n else 0
    doubts = len(set(re.findall(r"\bD\d+\b", sec)))
    return blockers, doubts


def one_way_doors(text):
    m = re.search(r"Domande che bloccano[^:]*:\**\s*(.*)", text)
    if not m:
        return ""
    line = m.group(1)
    if re.match(r"\s*nessuna", line, re.I):
        return 0
    return max(1, len(re.findall(r"\b\d\)", line)))


def decisions(gerio):
    """cycle -> (count, changed) from DECISIONI.md; decisions taken between cycles go to the
    cycle they prepared or followed (the number in the «Ciclo» column)."""
    out = {}
    path = os.path.join(gerio, "docs", "ciclo", "DECISIONI.md")
    for line in open(path, encoding="utf-8"):
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 7 or not cells[0].isdigit():
            continue
        m = re.search(r"(\d+)", cells[2])  # '1', '5 (4½)', 'pre-1', 'dopo 11', 'prima di 13 (7c)'
        if not m:
            continue
        cycle = int(m.group(1))
        changed = bool(re.search(r"cambiata|^no", cells[-1], re.I))
        count, ch = out.get(cycle, (0, 0))
        out[cycle] = (count + 1, ch + (1 if changed else 0))
    return out


def ci_runs(repo, branch):
    runs = gh(f"repos/{repo}/actions/runs?branch={branch}&event=pull_request&per_page=100")["workflow_runs"]
    total_ms, red = 0, 0
    for run in runs:
        if run.get("conclusion") in ("failure", "timed_out"):
            red += 1
        timing = gh(f"repos/{repo}/actions/runs/{run['id']}/timing")
        total_ms += timing.get("run_duration_ms") or 0
    return len(runs), red, round(total_ms / 60000, 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--gerio", required=True, help="clone of Gerio")
    parser.add_argument("--repo", default="samueleniccolai-dev/gerio")
    parser.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "..", "metrics", "baseline-gerio.csv"))
    args = parser.parse_args()

    t0 = parse_time(T0)
    decided = decisions(args.gerio)
    rows = []
    for cycle, (number, goal) in CYCLES.items():
        pr = gh(f"repos/{args.repo}/pulls/{number}")
        files = gh(f"repos/{args.repo}/pulls/{number}/files?per_page=100")
        path = report_path(args.gerio, cycle)
        text = open(path, encoding="utf-8").read() if path else ""
        cells = metrics_row(text)
        blockers, doubts = reviewer_counts(text)
        runs, red, ci_minutes = ci_runs(args.repo, pr["head"]["ref"])
        merged = parse_time(pr["merged_at"]) if pr.get("merged_at") else None
        rows.append({
            "ciclo": cycle,
            "traguardo": goal,
            "pr": number,
            "pr_creata": pr["created_at"],
            "pr_mergiata": pr.get("merged_at") or "",
            "ore_dal_t0": round((merged - t0).total_seconds() / 3600, 1) if merged else "",
            "commit": pr["commits"],
            "test_totali": tests_total(cells[1] if len(cells) > 1 else ""),
            "test_delta": tests_delta(cells[1] if len(cells) > 1 else ""),
            "righe_main_aggiunte": sum(f["additions"] for f in files if f["filename"].startswith("src/main/")),
            "righe_main_tolte": sum(f["deletions"] for f in files if f["filename"].startswith("src/main/")),
            "righe_test_aggiunte": sum(f["additions"] for f in files if f["filename"].startswith("src/test/")),
            "file": pr["changed_files"],
            "ci_run": runs,
            "ci_rossi": red,
            "ci_minuti": ci_minutes,
            "rilavorazioni": first_int(cells[2]) if len(cells) > 2 else "",
            "revisore_bloccanti": blockers,
            "revisore_dubbi": doubts,
            "decisioni": decided.get(cycle, (0, 0))[0],
            "decisioni_cambiate": decided.get(cycle, (0, 0))[1],
            "porte_senso_unico": one_way_doors(text),
            "minuti_umani_stimati": minutes(cells[5]) if len(cells) > 5 else "",
            "parole_rapporto": len(text.split()),
        })
        print(f"ciclo {cycle:>2}: PR #{number} {goal:<7} test={rows[-1]['test_totali']} ci={runs}/{red} {ci_minutes}'", file=sys.stderr)

    # Cycle 1 reports only the delta ("+1"): its total is the next total minus the next delta.
    for i in range(len(rows) - 2, -1, -1):
        nxt = rows[i + 1]
        if rows[i]["test_totali"] == "" and nxt["test_totali"] != "" and nxt["test_delta"] != "":
            rows[i]["test_totali"] = nxt["test_totali"] - nxt["test_delta"]

    with open(args.out, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    print(f"scritto {args.out}: {len(rows)} cicli", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
