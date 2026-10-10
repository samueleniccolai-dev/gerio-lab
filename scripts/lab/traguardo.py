#!/usr/bin/env python3
"""Updates the state of one goal in docs/lab/traguardi.json (only the fields a round may change).

    python3 scripts/lab/traguardo.py T3 --stato passato --giro 004 --pr 12
    python3 scripts/lab/traguardo.py --mostra

The consegna workflow refuses any other change to the file (scripts/guard/pr_guard.py).
"""
import argparse
import datetime
import json
import os
import sys

PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs", "lab", "traguardi.json")
STATI = ("da-fare", "in-corso", "passato")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("id", nargs="?", help="T1 … T8")
    parser.add_argument("--stato", choices=STATI)
    parser.add_argument("--giro", help="numero del giro, es. 004")
    parser.add_argument("--pr", type=int, help="numero della PR del giro")
    parser.add_argument("--mostra", action="store_true", help="stampa lo stato dei traguardi")
    args = parser.parse_args()

    with open(PATH, encoding="utf-8") as f:
        data = json.load(f)
    if args.mostra or not args.id:
        for t in data["traguardi"]:
            print(f"{t['id']}  {t['stato']:<9}  giri={','.join(t['giri']) or '-'}  pr={t['pr'] or '-'}  {t['titolo']}")
        return 0

    goal = next((t for t in data["traguardi"] if t["id"] == args.id), None)
    if goal is None:
        print(f"traguardo sconosciuto: {args.id}", file=sys.stderr)
        return 1
    if args.giro and args.giro not in goal["giri"]:
        goal["giri"].append(args.giro)
    if args.pr and args.pr not in goal["pr"]:
        goal["pr"].append(args.pr)
    if args.stato:
        goal["stato"] = args.stato
        goal["passato_il"] = (
            datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ") if args.stato == "passato" else None
        )
    with open(PATH, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"{goal['id']}: stato={goal['stato']} giri={goal['giri']} pr={goal['pr']} passato_il={goal['passato_il']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
