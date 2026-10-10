#!/usr/bin/env python3
"""The round's pull request, through the GitHub REST API (skill /giro).

From Claude Code cloud sessions GitHub's GraphQL API is not reachable, so `gh pr …` commands fail;
REST works, plus the CCR routes for draft/ready. This helper wraps the few calls a round makes and
keeps the label rules of docs/METODO.md §2 (the same sets as .claude/hooks/guard.py). It lives in
.claude/: a round cannot change it.

    python3 .claude/skills/giro/pr.py stato
    python3 .claude/skills/giro/pr.py apri <branch> "<titolo>" --corpo-file /tmp/giro-NNN-corpo.md
    python3 .claude/skills/giro/pr.py etichette <n> [--metti L ...] [--togli L ...]
    python3 .claude/skills/giro/pr.py modifica <n> [--titolo "<titolo>"] [--corpo-file F]
    python3 .claude/skills/giro/pr.py pronta <n>          |  bozza <n>
    python3 .claude/skills/giro/pr.py commenta <n> (--testo "<testo>" | --corpo-file F)
    python3 .claude/skills/giro/pr.py commenti <n> [--autore <login>]
    python3 .claude/skills/giro/pr.py chiudi <n> --motivo "<testo>"
    python3 .claude/skills/giro/pr.py mergiate [--quante 5]

Comments written by a round start with «🤖»: the metrics count only the human's.
"""
import argparse
import importlib.util
import json
import os
import subprocess
import sys
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
AUTHOR = os.environ.get("LAB_AUTORE", "SamueleNiccolai")
ROBOT = "🤖 "


def guard_rules():
    spec = importlib.util.spec_from_file_location("guard", os.path.join(HERE, "..", "..", "hooks", "guard.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


GUARD = guard_rules()


class Error(Exception):
    pass


def api(path, method="GET", body=None, paginate=False):
    args = ["gh", "api", "-X", method, f"repos/{{owner}}/{{repo}}/{path}"]
    if paginate:
        args.insert(2, "--paginate")
    if body is not None:
        args += ["--input", "-"]
    res = subprocess.run(args, input=json.dumps(body) if body is not None else None, capture_output=True, text=True)
    if res.returncode != 0:
        raise Error(f"gh api {method} {path}: {res.stderr.strip()[:300]}")
    out = res.stdout.strip()
    if not out:
        return None
    if paginate:
        decoder, values, i = json.JSONDecoder(), [], 0
        while i < len(out):
            value, i = decoder.raw_decode(out, i)
            values.extend(value if isinstance(value, list) else [value])
            while i < len(out) and out[i].isspace():
                i += 1
        return values
    return json.loads(out)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def round_prs(state):
    return [
        pr
        for pr in api(f"pulls?state={state}&per_page=100", paginate=True)
        if (pr.get("head") or {}).get("ref", "").startswith("claude/giro-")
        and (pr.get("user") or {}).get("login") == AUTHOR
        and "giro" in {label["name"] for label in pr.get("labels") or []}
    ]


def ci_state(sha):
    """Conclusion of the latest `build` check on a commit: success, failure, … or 'in-corso'."""
    runs = (api(f"commits/{sha}/check-runs?check_name=build&per_page=20") or {}).get("check_runs", [])
    if not runs:
        return "nessuna"
    latest = max(runs, key=lambda r: r.get("started_at") or "")
    return latest.get("conclusion") or "in-corso"


def stato(_args):
    out = []
    for pr in round_prs("open"):
        sha = pr["head"]["sha"]
        commit = api(f"commits/{sha}") or {}
        out.append({
            "numero": pr["number"],
            "titolo": pr["title"],
            "branch": pr["head"]["ref"],
            "bozza": pr.get("draft", False),
            "etichette": sorted(label["name"] for label in pr.get("labels") or []),
            "aggiornata": pr["updated_at"],
            "ultimo_commit": ((commit.get("commit") or {}).get("committer") or {}).get("date"),
            "sha": sha,
            "ci": ci_state(sha),
        })
    print(json.dumps(out, ensure_ascii=False, indent=2))


def check_labels(add, remove):
    add = {x.strip().lower() for x in add}
    remove = {x.strip().lower() for x in remove}
    if add & GUARD.LABELS_NOT_ADDED:
        raise Error(f"le etichette {sorted(add & GUARD.LABELS_NOT_ADDED)} le mette l'umano o la catena, non il giro")
    if remove & GUARD.LABELS_NOT_REMOVED:
        raise Error(f"le etichette {sorted(remove & GUARD.LABELS_NOT_REMOVED)} le toglie l'umano o la catena, non il giro")
    return add, remove


def set_labels(number, add=(), remove=()):
    add, remove = check_labels(add, remove)
    if add:
        api(f"issues/{number}/labels", "POST", {"labels": sorted(add)})
    for label in sorted(remove):
        try:
            api(f"issues/{number}/labels/{urllib.parse.quote(label, safe='')}", "DELETE")
        except Error as exc:
            if "404" not in str(exc):  # already gone: fine
                raise


def apri(args):
    body = read(args.corpo_file) if args.corpo_file else "Questa PR bozza è il lucchetto del giro (METODO §2)."
    pr = api("pulls", "POST", {"title": args.titolo, "head": args.branch, "base": "main", "body": body, "draft": True})
    set_labels(pr["number"], add=GUARD.LABELS_AT_CREATION)
    print(pr["number"])


def etichette(args):
    set_labels(args.numero, args.metti or [], args.togli or [])


def modifica(args):
    body = {}
    if args.titolo:
        body["title"] = args.titolo
    if args.corpo_file:
        body["body"] = read(args.corpo_file)
    if body:
        api(f"pulls/{args.numero}", "PATCH", body)


def pronta(args):
    api(f"pulls/{args.numero}/ccr/ready_for_review", "POST")


def bozza(args):
    api(f"pulls/{args.numero}/ccr/convert_to_draft", "POST")


def commenta(args):
    text = read(args.corpo_file) if args.corpo_file else args.testo
    if not text:
        raise Error("serve --testo o --corpo-file")
    api(f"issues/{args.numero}/comments", "POST", {"body": text if text.startswith(ROBOT) else ROBOT + text})


def commenti(args):
    out = [
        {"autore": (c.get("user") or {}).get("login"), "data": c["created_at"], "testo": c.get("body") or ""}
        for c in api(f"issues/{args.numero}/comments?per_page=100", paginate=True)
    ]
    if args.autore:
        out = [c for c in out if c["autore"] == args.autore and not c["testo"].startswith(ROBOT.strip())]
    print(json.dumps(out, ensure_ascii=False, indent=2))


def chiudi(args):
    api(f"issues/{args.numero}/comments", "POST", {"body": ROBOT + args.motivo})
    api(f"pulls/{args.numero}", "PATCH", {"state": "closed"})


def mergiate(args):
    prs = [pr for pr in round_prs("closed") if pr.get("merged_at")]
    prs.sort(key=lambda pr: pr["merged_at"], reverse=True)
    print(json.dumps([{"numero": pr["number"], "titolo": pr["title"], "mergiata": pr["merged_at"]} for pr in prs[: args.quante]], ensure_ascii=False, indent=2))


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="azione", required=True)
    sub.add_parser("stato").set_defaults(fn=stato)
    p = sub.add_parser("apri")
    p.add_argument("branch")
    p.add_argument("titolo")
    p.add_argument("--corpo-file")
    p.set_defaults(fn=apri)
    p = sub.add_parser("etichette")
    p.add_argument("numero", type=int)
    p.add_argument("--metti", nargs="+")
    p.add_argument("--togli", nargs="+")
    p.set_defaults(fn=etichette)
    p = sub.add_parser("modifica")
    p.add_argument("numero", type=int)
    p.add_argument("--titolo")
    p.add_argument("--corpo-file")
    p.set_defaults(fn=modifica)
    for name, fn in (("pronta", pronta), ("bozza", bozza)):
        p = sub.add_parser(name)
        p.add_argument("numero", type=int)
        p.set_defaults(fn=fn)
    p = sub.add_parser("commenta")
    p.add_argument("numero", type=int)
    p.add_argument("--testo")
    p.add_argument("--corpo-file")
    p.set_defaults(fn=commenta)
    p = sub.add_parser("commenti")
    p.add_argument("numero", type=int)
    p.add_argument("--autore")
    p.set_defaults(fn=commenti)
    p = sub.add_parser("chiudi")
    p.add_argument("numero", type=int)
    p.add_argument("--motivo", required=True)
    p.set_defaults(fn=chiudi)
    p = sub.add_parser("mergiate")
    p.add_argument("--quante", type=int, default=5)
    p.set_defaults(fn=mergiate)
    args = parser.parse_args(argv[1:])
    try:
        args.fn(args)
    except Error as exc:
        print(f"pr.py: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
