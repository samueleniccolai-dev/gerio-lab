#!/usr/bin/env python3
"""Decides whether a round's PR may be merged without a human (docs/METODO.md §4).

The consegna workflow runs the copy on main against the PR's commits, read as data only:
nothing from the PR is executed. The orchestrator runs it locally before opening the PR.

    python3 scripts/guard/pr_guard.py --base origin/main --head HEAD
    python3 scripts/guard/pr_guard.py --base <sha> --head <sha> --pr-json pr.json --out verdetto.json --markdown verdetto.md

Exit code 0 in both cases: the answer is in `auto_merge` (JSON) or on the first printed line.
"""
import argparse
import json
import os
import re
import subprocess
import sys

PROTECTED_PREFIXES = (
    ".github/",
    ".claude/",
    ".mvn/",
    "scripts/ci/",
    "scripts/guard/",
    "scripts/metrics/",
    "scripts/lab/",
    "infra/",
    "src/main/docker/",
)
PROTECTED_FILES = {
    "CLAUDE.md",
    "pom.xml",
    "mvnw",
    "mvnw.cmd",
    ".dockerignore",
    ".gitignore",
    "docs/SPEC.md",
    "docs/METODO.md",
    "docs/ESPERIMENTO.md",
    "docs/lab/SETUP.md",
    "docs/lab/giri/_modello-piano.md",
    "docs/lab/giri/_modello-rapporto.md",
    "metrics/baseline-gerio.csv",
}
MIGRATIONS_DIR = "src/main/resources/db/migration/"
ACCEPTANCE_DIR = "src/test/java/it/gerio/platform/acceptance/"
APP_PROPERTIES = re.compile(
    r"^src/main/resources/(application(-[a-z0-9]+)?\.(properties|ya?ml)|META-INF/microprofile-config\.properties)$"
)
# Files that change how git shows the diff (or what it contains) to the checks below.
GIT_META_FILES = {".gitattributes", ".gitmodules"}
# The empty tree: with it as the attribute source, a .gitattributes in the PR cannot mark files as
# binary (-diff) and hide their lines from the checks.
EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
DANGEROUS_CONFIG = re.compile(
    r"(flyway[.\w-]*\.(clean|locations|baseline|out-of-order|validate-on-migrate|ignore|repair|placeholder))"
    r"|((schema-management\.strategy|database\.generation|hbm2ddl|sql-load-script)\s*[=:])",
    re.I,
)
GOALS_FILE = "docs/lab/traguardi.json"
GOAL_STATE_FIELDS = {"stato", "giri", "pr", "passato_il"}
GOAL_STATES = {"da-fare", "in-corso", "passato"}
STOP_FILE = "docs/lab/STOP"
BRANCH_PREFIX = "claude/giro-"
MAX_MAIN_LINES = 1500
AFTER_REVIEW_ALLOWED = ("docs/lab/", "metrics/")
MIGRATION_NAME = re.compile(r"^V(\d+)__[a-z0-9_]+\.sql$")
ALLOWED_SQL = [
    re.compile(p, re.I | re.S)
    for p in (
        r"^create\s+table\s",
        r"^create\s+(unique\s+)?index\s",
        r"^create\s+sequence\s",
        r"^alter\s+table\s+(if\s+exists\s+)?[\w.\"]+\s+add\s+(column\s+|constraint\s+)?(?!.*\b(drop|rename|type)\b)",
        r"^insert\s+into\s",
        r"^comment\s+on\s",
    )
]
SECRETS = [
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(r"\b(sk|rk)_(live|test)_[0-9A-Za-z]{20,}"),
    re.compile(r"\bwhsec_[0-9A-Za-z]{24,}"),
    re.compile(r"\bghp_[0-9A-Za-z]{30,}"),
    re.compile(r"\bgithub_pat_[0-9A-Za-z_]{30,}"),
    re.compile(r"\bsk-ant-[0-9A-Za-z_-]{20,}"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bxox[abprs]-[0-9A-Za-z-]{10,}"),
]
VERDICT = re.compile(r"<!--\s*verdetto-revisore:\s*(\{.*?\})\s*-->", re.S)


def git(repo, *args, check=True):
    res = subprocess.run(
        ["git", "-C", repo, "-c", "core.quotePath=false", f"--attr-source={EMPTY_TREE}", *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if check and res.returncode != 0:
        raise SystemExit(f"git {' '.join(args)}: {res.stderr.strip()}")
    return res.stdout


def show(repo, ref, path):
    res = subprocess.run(["git", "-C", repo, "show", f"{ref}:{path}"], capture_output=True, text=True, encoding="utf-8")
    return res.stdout if res.returncode == 0 else None


def protected(path):
    return path in PROTECTED_FILES or path.startswith(PROTECTED_PREFIXES) or os.path.basename(path) in GIT_META_FILES


def is_acceptance(path):
    return path.startswith(ACCEPTANCE_DIR) or path.endswith("AcceptanceTest.java")


def changes(repo, base, head):
    """[(status, path, old_path)] between the merge base and head; NUL-separated, so any file name works."""
    out = []
    fields = git(repo, "diff", "-z", "--name-status", "-M", f"{base}...{head}").split("\0")
    i = 0
    while i < len(fields) and fields[i]:
        status = fields[i][0]
        if status in ("R", "C"):
            out.append((status, fields[i + 2], fields[i + 1]))
            i += 3
        else:
            out.append((status, fields[i + 1], None))
            i += 2
    return out


def numstat(repo, base, head):
    stats = []
    for record in git(repo, "diff", "-z", "--numstat", "--no-renames", f"{base}...{head}").split("\0"):
        if not record:
            continue
        added, removed, path = record.split("\t", 2)
        if added == "-":  # binary
            added = removed = "0"
        stats.append((int(added), int(removed), path))
    return stats


def sql_statements(body):
    """Statements of a migration, without comments, splitting on ';' outside quotes."""
    out, current, i, quote = [], [], 0, None
    while i < len(body):
        ch = body[i]
        if quote:
            current.append(ch)
            if ch == quote:
                quote = None
        elif ch in ("'", '"'):
            quote = ch
            current.append(ch)
        elif body.startswith("--", i):
            while i < len(body) and body[i] != "\n":
                i += 1
            current.append(" ")
            continue
        elif body.startswith("/*", i):
            end = body.find("*/", i + 2)
            i = len(body) if end < 0 else end + 2
            current.append(" ")
            continue
        elif ch == ";":
            out.append("".join(current))
            current = []
        else:
            current.append(ch)
        i += 1
    out.append("".join(current))
    return [re.sub(r"\s+", " ", s).strip() for s in out if s.strip()]


def check_migrations(repo, base, head, changed, reasons):
    existing = [
        int(m.group(1))
        for name in git(repo, "ls-tree", "--name-only", f"{base}:{MIGRATIONS_DIR}", check=False).splitlines()
        if (m := MIGRATION_NAME.match(os.path.basename(name)))
    ]
    expected = (max(existing) if existing else 0) + 1
    added = []
    for status, path, old in changed:
        for p in (path, old):
            if p and "/db/migration/" in p and not p.startswith(MIGRATIONS_DIR):
                reasons.append(f"migrazione fuori da {MIGRATIONS_DIR}: {p}")
        touched = [p for p in (path, old) if p and p.startswith(MIGRATIONS_DIR)]
        if not touched:
            continue
        if status != "A":
            reasons.append(f"migrazione {touched[0]}: solo aggiunte, mai modifiche, cancellazioni o rinomine")
            continue
        m = MIGRATION_NAME.match(os.path.basename(path))
        if not m:
            reasons.append(f"migrazione {os.path.basename(path)}: il nome deve essere V<n>__descrizione.sql")
            continue
        added.append((int(m.group(1)), path))
    for version, path in sorted(added):
        name = os.path.basename(path)
        if version != expected:
            reasons.append(f"migrazione {name}: attesa la versione V{expected}, in sequenza con main")
        expected = version + 1
        for statement in sql_statements(show(repo, head, path) or ""):
            if not any(p.match(statement) for p in ALLOWED_SQL):
                reasons.append(f"migrazione {name}: istruzione non tra quelle sempre sicure → «{statement[:60]}»")
    return len(added)


def check_config(repo, base, head, changed, reasons):
    for status, path, old in changed:
        if not APP_PROPERTIES.match(path or ""):
            continue
        diff = git(repo, "diff", "--unified=0", f"{base}...{head}", "--", path)
        for line in diff.splitlines():
            if line.startswith(("+", "-")) and not line.startswith(("+++", "---")) and DANGEROUS_CONFIG.search(line):
                reasons.append(f"{path}: chiave che può cancellare o rigenerare lo schema → «{line[1:61].strip()}»")


def check_acceptance(repo, base, head, changed, reasons):
    merge_base = git(repo, "merge-base", base, head).strip()
    for status, path, old in changed:
        paths = [p for p in (path, old) if p and is_acceptance(p)]
        if paths and status != "A":
            reasons.append(f"test di accettazione {paths[0]}: quelli già su main non si toccano")
    # Every acceptance test added during the PR must still be there, unchanged since the commit
    # that created it, and that commit must be a test(acceptance) one.
    first = {}
    for commit in git(repo, "log", "--reverse", "--format=%H", f"{merge_base}..{head}").split():
        subject = git(repo, "log", "-1", "--format=%s", commit).strip()
        fields = git(repo, "diff-tree", "--no-commit-id", "-z", "-r", "--name-status", "--diff-filter=A", commit).split("\0")
        for status, path in zip(fields[0::2], fields[1::2]):
            if status == "A" and is_acceptance(path) and path not in first:
                first[path] = (commit, subject)
    for path, (commit, subject) in first.items():
        if not subject.lower().startswith("test(acceptance)"):
            reasons.append(f"test di accettazione {path}: nato in un commit che non è test(acceptance)")
            continue
        original = show(repo, commit, path)
        now = show(repo, head, path)
        if now is None:
            reasons.append(f"test di accettazione {path}: tolto o spostato dopo il commit che lo ha creato")
        elif now != original:
            reasons.append(f"test di accettazione {path}: cambiato dopo il commit test(acceptance) che lo ha creato")


def check_goals(repo, base, head, changed, reasons):
    if not any(GOALS_FILE in (path, old) for _, path, old in changed):
        return
    try:
        before = json.loads(show(repo, git(repo, "merge-base", base, head).strip(), GOALS_FILE) or "{}")
        after = json.loads(show(repo, head, GOALS_FILE) or "null")
    except ValueError:
        reasons.append(f"{GOALS_FILE}: JSON non valido")
        return
    if not isinstance(after, dict):
        reasons.append(f"{GOALS_FILE}: cancellato o vuoto")
        return
    if {k: v for k, v in before.items() if k != "traguardi"} != {k: v for k, v in after.items() if k != "traguardi"}:
        reasons.append(f"{GOALS_FILE}: cambiati campi che non sono di stato")
    old_goals, new_goals = before.get("traguardi", []), after.get("traguardi", [])
    if [g.get("id") for g in old_goals] != [g.get("id") for g in new_goals]:
        reasons.append(f"{GOALS_FILE}: traguardi aggiunti, tolti o riordinati")
        return
    changed_goals = 0
    for old, new in zip(old_goals, new_goals):
        if {k: v for k, v in old.items() if k not in GOAL_STATE_FIELDS} != {k: v for k, v in new.items() if k not in GOAL_STATE_FIELDS}:
            reasons.append(f"{GOALS_FILE}: {new.get('id')} cambiato oltre lo stato")
        if new.get("stato") not in GOAL_STATES:
            reasons.append(f"{GOALS_FILE}: {new.get('id')} ha uno stato sconosciuto")
        for key in ("giri", "pr"):
            if new.get(key, [])[: len(old.get(key, []))] != old.get(key, []):
                reasons.append(f"{GOALS_FILE}: {new.get('id')}.{key} riscritto (si può solo aggiungere)")
        if old != new:
            changed_goals += 1
    if changed_goals > 1:
        reasons.append(f"{GOALS_FILE}: un giro cambia lo stato di un traguardo solo, qui {changed_goals}")


def check_secrets(repo, base, head, reasons):
    diff = git(repo, "diff", "--unified=0", "--no-color", f"{base}...{head}")
    current, previous = None, ""
    for line in diff.splitlines():
        if previous.startswith("--- ") and line.startswith("+++ "):
            current = line[6:] if line.startswith("+++ b/") else line[4:]
        elif line.startswith("+") and any(p.search(line) for p in SECRETS):
            reasons.append(f"possibile segreto in {current}: niente segreti nel repository")
        previous = line


def check_pr(repo, head, meta, reasons):
    branch = meta.get("headRefName", "")
    labels = {l.get("name") if isinstance(l, dict) else l for l in meta.get("labels", [])}
    if not branch.startswith(BRANCH_PREFIX):
        reasons.append(f"branch {branch!r}: le PR automatiche vengono solo da {BRANCH_PREFIX}…")
    if "giro" not in labels:
        reasons.append("manca l'etichetta 'giro'")
    for label in ("domanda", "in-corso", "bloccato", "revisione-umana"):
        if label in labels:
            reasons.append(f"etichetta '{label}': non è il momento del merge")
    if meta.get("isDraft"):
        reasons.append("la PR è ancora in bozza")
    found = VERDICT.findall(meta.get("body") or "")
    if not found:
        reasons.append("nel corpo della PR manca il verdetto del revisore (<!-- verdetto-revisore: {...} -->)")
        return None
    try:
        verdict = json.loads(found[-1])  # the last one: the most recent review
    except ValueError:
        reasons.append("il verdetto del revisore non è JSON valido")
        return None
    blockers = verdict.get("bloccanti")
    if type(blockers) is not int or blockers != 0:
        reasons.append(f"il revisore riporta bloccanti: {blockers!r}")
    sha = str(verdict.get("sha") or "")
    if not re.fullmatch(r"[0-9a-f]{7,40}", sha):
        reasons.append("il verdetto non dice quale commit ha rivisto (campo sha)")
    else:
        res = subprocess.run(["git", "-C", repo, "merge-base", "--is-ancestor", sha, head], capture_output=True)
        if res.returncode != 0:
            reasons.append(f"il commit rivisto {sha[:9]} non è nella storia della PR")
        else:
            after = [p for _, p, _ in changes(repo, sha, head) if not p.startswith(AFTER_REVIEW_ALLOWED)]
            if after:
                reasons.append(f"codice cambiato dopo la revisione di {sha[:9]}: {', '.join(after[:5])}")
    return verdict


def check_links(repo, base, head, reasons):
    """Symbolic links: a link can make a protected or checked file say something else."""
    fields = git(repo, "diff", "-z", "--raw", "--no-renames", f"{base}...{head}").split("\0")
    for meta, path in zip(fields[0::2], fields[1::2]):
        parts = meta.split()
        if len(parts) >= 2 and parts[1] == "120000":
            reasons.append(f"collegamento simbolico: {path}")


def guarded(name, reasons, check, *args):
    """Runs one check; if it fails, the PR goes to a human instead of the check being skipped."""
    try:
        return check(*args)
    except (Exception, SystemExit) as exc:  # noqa: BLE001 - any failure means "a human looks"
        reasons.append(f"controllo «{name}» non riuscito ({type(exc).__name__}: {str(exc)[:120]})")
        return None


def evaluate(repo, base, head, meta=None):
    reasons = []
    changed = changes(repo, base, head)
    for status, path, old in changed:
        for p in (path, old):
            if p and protected(p):
                reasons.append(f"percorso protetto: {p}")
    if show(repo, head, STOP_FILE) is not None or show(repo, base, STOP_FILE) is not None:
        reasons.append("interruttore: esiste docs/lab/STOP")
    new_migrations = guarded("migrazioni", reasons, check_migrations, repo, base, head, changed, reasons)
    guarded("configurazione", reasons, check_config, repo, base, head, changed, reasons)
    guarded("test di accettazione", reasons, check_acceptance, repo, base, head, changed, reasons)
    guarded("traguardi", reasons, check_goals, repo, base, head, changed, reasons)
    guarded("segreti", reasons, check_secrets, repo, base, head, reasons)
    guarded("collegamenti", reasons, check_links, repo, base, head, reasons)
    stats = numstat(repo, base, head)
    main_added = sum(a for a, _, p in stats if p.startswith("src/main/"))
    if main_added > MAX_MAIN_LINES:
        reasons.append(f"fetta troppo grande: {main_added} righe aggiunte in src/main (massimo {MAX_MAIN_LINES})")
    verdict = guarded("PR", reasons, check_pr, repo, head, meta, reasons) if meta is not None else None
    reasons = list(dict.fromkeys(reasons))
    return {
        "auto_merge": not reasons,
        "motivi": reasons,
        "metriche": {
            "file": len(changed),
            "righe_main_aggiunte": main_added,
            "righe_main_tolte": sum(r for _, r, p in stats if p.startswith("src/main/")),
            "righe_test_aggiunte": sum(a for a, _, p in stats if p.startswith("src/test/")),
            "righe_docs_aggiunte": sum(a for a, _, p in stats if p.startswith("docs/")),
            "migrazioni_nuove": new_migrations or 0,
            "verdetto": verdict,
        },
    }


def markdown(result):
    if result["auto_merge"]:
        head = "✅ **Controlli di consegna superati**: merge automatico."
    else:
        head = "⏸️ **Revisione umana**: il merge automatico non si fa perché:\n\n" + "\n".join(
            f"- {r}" for r in result["motivi"]
        )
    m = result["metriche"]
    return (
        f"{head}\n\n"
        f"File: {m['file']} · righe in src/main: +{m['righe_main_aggiunte']} −{m['righe_main_tolte']} · "
        f"righe di test: +{m['righe_test_aggiunte']} · migrazioni nuove: {m['migrazioni_nuove']}\n\n"
        "_Regole: `docs/METODO.md` §4, controllo `scripts/guard/pr_guard.py` (versione su main)._\n"
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", default=".")
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--pr-json", help="JSON della PR: headRefName, labels, isDraft, body")
    parser.add_argument("--out", help="scrive il risultato JSON in questo file")
    parser.add_argument("--markdown", help="scrive il commento per la PR in questo file")
    args = parser.parse_args()
    meta = None
    if args.pr_json:
        with open(args.pr_json, encoding="utf-8") as f:
            meta = json.load(f)
    result = evaluate(args.repo, args.base, args.head, meta)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
    if args.markdown:
        with open(args.markdown, "w", encoding="utf-8") as f:
            f.write(markdown(result))
    print("auto-merge" if result["auto_merge"] else "revisione-umana")
    for reason in result["motivi"]:
        print(f"- {reason}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
