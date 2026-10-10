#!/usr/bin/env python3
"""PreToolUse guard of gerio-lab (docs/METODO.md §4, CLAUDE.md rules 3-7).

A seatbelt, not a sandbox. It stops the accidental and the eager — a merge "because the CI is
green", a quick fix to the rules, a look at a token — not a session that sets out to get around
it. The hard limits live on GitHub: rulesets on main and on the round branches, secrets in
environments restricted to main, scripts/guard/pr_guard.py always run from main (README,
«Cosa è garantito e cosa no»).

    guard.py bash  [--costruttore | --revisore | --sola-lettura]     PreToolUse on Bash
    guard.py files [--costruttore]                                  PreToolUse on Edit/Write
    guard.py after [--costruttore | --revisore | --sola-lettura]    PostToolUse on Bash

It reads the hook JSON on stdin; exit 0 allows the call, exit 2 blocks it with the reason on
stderr, which Claude reads. It fails closed: what it cannot understand, it blocks, and says why.
Inside a subagent the hook input names it (agent_type): its mode applies in any case. The hooks
are declared with "onFailure": "block", so a guard that cannot run blocks too.
"""
import functools
import glob
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import urllib.parse

# ------------------------------------------------------------------------------- the rules

# Never written from a round (CLAUDE.md rule 3). Other protected paths (infra/, pom.xml, …) may
# be written: their PRs go to human review (scripts/guard/pr_guard.py).
RULE_PREFIXES = (".github/", ".claude/", "scripts/guard/")
RULE_FILES = frozenset({
    "CLAUDE.md",
    "docs/METODO.md",
    "docs/ESPERIMENTO.md",
    "docs/lab/SETUP.md",
    "docs/lab/giri/_modello-piano.md",
    "docs/lab/giri/_modello-rapporto.md",
    "metrics/baseline-gerio.csv",
})
ACCEPTANCE_DIR = "src/test/java/it/gerio/platform/acceptance/"
MIGRATIONS_DIR = "src/main/resources/db/migration/"
MAIN_BRANCHES = {"main", "master"}
MODES = {"generale", "costruttore", "revisore", "sola-lettura"}
WHO = {"revisore": "revisore", "sola-lettura": "critico"}
# The hook input names the subagent (agent_type): its mode applies even if its own hooks do not run.
AGENT_MODES = {"costruttore-tdd": "costruttore", "revisore": "revisore", "critico": "sola-lettura", "ricercatore": "sola-lettura"}
STRICTER = ["generale", "costruttore", "revisore", "sola-lettura"]
# Outside the repository, files that change what later commands do: never written from a round.
HOME = os.path.expanduser("~")
OUTSIDE_PROTECTED = tuple(
    os.path.join(HOME, p)
    for p in (
        ".claude", ".gitconfig", ".config/git", ".config/gh", ".ssh", ".bashrc", ".bash_profile", ".bash_login",
        ".profile", ".zshrc", ".zprofile", ".zshenv", ".netrc", ".git-credentials", ".local/bin", "bin",
        ".docker", ".m2/settings.xml",
    )
) + ("/etc", "/usr", "/bin", "/sbin", "/lib", "/opt/pw-browsers")

# Labels (docs/METODO.md §2): what a round may add or remove.
LABELS_NOT_ADDED = {"risposto", "riparazione-1", "riparazione-2", "revisione-umana"}
LABELS_NOT_REMOVED = {"giro", "bloccato", "revisione-umana", "riparazione-1", "riparazione-2", "fuori-catena"}
LABELS_AT_CREATION = {"giro", "in-corso"}


class Blocked(Exception):
    pass


class Unparseable(Blocked):
    pass


# ------------------------------------------------------------------------------- paths


def git_out(root, *args, timeout=20):
    try:
        res = subprocess.run(["git", "-C", root, *args], capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError):
        return None
    return res.stdout if res.returncode == 0 else None


def project_root(cwd):
    base = os.environ.get("CLAUDE_PROJECT_DIR") or cwd or os.getcwd()
    out = git_out(base, "rev-parse", "--show-toplevel", timeout=10)
    return os.path.realpath(out.strip() if out and out.strip() else base)


def relative(path, base, root):
    """Path relative to the repository root, '.' for the root, None when outside the repository."""
    if not path:
        return None
    path = os.path.expanduser(path)
    if not os.path.isabs(path):
        path = os.path.join(base or root, path)
    real = os.path.realpath(path)
    if real != root and not real.startswith(root + os.sep):
        return None
    return os.path.relpath(real, root).replace(os.sep, "/")


def contains_root(path, root):
    """True when `path` is the repository root or one of its parents."""
    real = os.path.realpath(path)
    return real == root or root.startswith(real.rstrip("/") + "/")


def is_rule(rel):
    return rel in RULE_FILES or rel.startswith(RULE_PREFIXES) or rel + "/" in RULE_PREFIXES


def is_acceptance(rel):
    return rel.startswith(ACCEPTANCE_DIR) or rel.endswith("AcceptanceTest.java")


@functools.lru_cache(maxsize=None)
def migrations_on_main(root):
    for ref in ("origin/main", "main"):
        out = git_out(root, "ls-tree", "-r", "-z", "--name-only", ref, "--", MIGRATIONS_DIR)
        if out is not None:
            return frozenset(p for p in out.split("\0") if p)
    return frozenset()


def check_path(rel, root, mode):
    """Raises Blocked when writing `rel` (relative to the root) is not allowed in this mode."""
    if rel is None or rel in (".", ""):
        return
    if rel == ".git" or rel.startswith(".git/"):
        raise Blocked("La cartella .git non si tocca a mano (hook, configurazione, storia): usa i comandi git.")
    if is_rule(rel):
        raise Blocked(
            f"{rel} è una regola del laboratorio (CLAUDE.md regola 3): non si cambia da un giro. "
            "Scrivi la proposta in docs/lab/proposte-metodo.md."
        )
    if rel.startswith(MIGRATIONS_DIR) and rel in migrations_on_main(root):
        raise Blocked(f"{rel} è una migrazione già su main: non si modifica mai. Crea un nuovo file V<n+1>__….sql.")
    if mode == "costruttore" and is_acceptance(rel):
        raise Blocked(
            f"{rel} è un test di accettazione: il costruttore non lo tocca. Se è sbagliato, fermati e "
            "dillo nella risposta: lo corregge il pianificatore."
        )


def check_tree(rel, root, mode):
    """Like check_path, for an operation on `rel` and everything below it (rm -r, mv, …)."""
    if rel is None:
        return
    if rel in (".", ""):
        raise Blocked("Operazione su tutto il repository: indica le cartelle precise.")
    check_path(rel, root, mode)
    base = rel.rstrip("/") + "/"
    for protected in RULE_PREFIXES + tuple(sorted(RULE_FILES)):
        if protected.startswith(base):
            raise Blocked(f"{rel} contiene {protected}, una regola del laboratorio (CLAUDE.md regola 3).")
    if mode == "costruttore" and ACCEPTANCE_DIR.startswith(base):
        raise Blocked(f"{rel} contiene i test di accettazione: il costruttore non li tocca.")
    for path in (git_out(root, "ls-files", "-z", "--", rel) or "").split("\0"):
        if path:
            check_path(path, root, mode)


# ------------------------------------------------------------------------------- files


def check_outside(path, tree=False):
    """Files outside the repository that change what later commands do: never written."""
    real = os.path.realpath(path)
    for protected in OUTSIDE_PROTECTED:
        if real == protected or real.startswith(protected.rstrip("/") + "/") or (tree and protected.startswith(real.rstrip("/") + "/")):
            raise Blocked(f"{real}: configurazione della sessione o del sistema, non si scrive da un giro.")


def guard_files(data, mode):
    tool_input = data.get("tool_input") or {}
    path = tool_input.get("file_path") or tool_input.get("notebook_path") or tool_input.get("path")
    if not path:
        raise Blocked("guard: nessun percorso nel tool di scrittura, chiamata bloccata per prudenza.")
    check_outside(os.path.join(data.get("cwd") or os.getcwd(), os.path.expanduser(path)))
    root = project_root(data.get("cwd"))
    rel = relative(path, data.get("cwd"), root)
    if mode in WHO and rel is not None:
        raise Blocked(f"Il {WHO[mode]} non scrive nel repository ({rel}).")
    check_path(rel, root, mode)


# ------------------------------------------------------------------------------- shell text

HEREDOC_OP = re.compile(r"<<(-?)[ \t]*(?:'([^'\n]+)'|\"([^\"\n]+)\"|(\\?)([A-Za-z0-9_.-]+))")
HEREDOC_MARK = re.compile(r"^__HEREDOC_(\d+)__$")
SUB_MARK = re.compile(r"\$__SUB(\d+)_(\d+)__")
WORD_BREAK = " \t\n;&|()<>"


def preprocess(command, heredocs=None):
    """Reads a command line the way bash does, for the parts that matter here.

    Returns (text, heredocs): comments and line continuations removed, each heredoc body replaced
    by a marker `<< __HEREDOC_n__`, heredocs[n] = (body, quoted delimiter). Quote-aware: `<<`
    and `#` inside quotes are text, not a heredoc or a comment — except inside a $(…), which
    starts afresh even within double quotes, as in `git commit -m "$(cat <<'EOF' … EOF)"`."""
    out, heredocs = [], ([] if heredocs is None else heredocs)
    i, n, state, word_start, jump = 0, len(command), None, True, None
    outer, parens = [], []  # for each open $(…): the quoting outside it, and its open parentheses
    while i < n:
        c = command[i]
        if state == "'":
            out.append(c)
            state = None if c == "'" else state
            i += 1
            continue
        if state is not None or c != "$" or not command.startswith("$(", i):
            pass
        elif command.startswith("$((", i):
            if parens:
                parens[-1] += 2
            out.append("$((")
            i += 3
            continue
        else:
            outer.append(state)
            parens.append(0)
            out.append("$(")
            i += 2
            word_start = True
            continue
        if state == '"':
            if c == "\\" and i + 1 < n:
                out.append(command[i : i + 2])
                i += 2
                continue
            if command.startswith("$(", i) and not command.startswith("$((", i):
                outer.append(state)
                parens.append(0)
                out.append("$(")
                state, word_start = None, True
                i += 2
                continue
            out.append(c)
            state = None if c == '"' else state
            i += 1
            continue
        if parens and c == "(":
            parens[-1] += 1
        elif parens and c == ")":
            if parens[-1] == 0:
                parens.pop()
                state = outer.pop()
                out.append(c)
                word_start = False
                i += 1
                continue
            parens[-1] -= 1
        if c == "\\" and i + 1 < n:
            if command[i + 1] != "\n":  # backslash-newline is a line continuation
                out.append(command[i : i + 2])
            i += 2
            word_start = False
            continue
        if c in "'\"":
            state = c
            out.append(c)
            word_start = False
            i += 1
            continue
        if c == "#" and word_start:
            end = command.find("\n", i)
            i = n if end < 0 else end
            continue
        if c == "\n" and jump is not None:
            # The heredoc bodies of this line follow it: skip them.
            out.append("\n")
            i, jump, word_start = jump, None, True
            continue
        if command.startswith("<<", i) and not command.startswith("<<<", i):
            m = HEREDOC_OP.match(command, i)
            line_end = command.find("\n", m.end()) if m else -1
            if m and line_end >= 0:
                delimiter = m.group(2) or m.group(3) or m.group(5)
                quoted = bool(m.group(2) or m.group(3) or m.group(4))
                start = jump if jump is not None else line_end + 1
                tabs = r"\t*" if m.group(1) else ""
                end = re.compile("^" + tabs + re.escape(delimiter) + "$", re.M).search(command, start)
                if end:
                    heredocs.append((command[start : end.start()], quoted))
                    out.append(f" << __HEREDOC_{len(heredocs) - 1}__ ")
                    jump = end.end()
                    i = m.end()
                    word_start = True
                    continue
        out.append(c)
        word_start = c in WORD_BREAK
        i += 1
    return "".join(out), heredocs


def matching_paren(text, i):
    """Index of the ')' that closes the '(' at text[i], or -1. Quote-aware."""
    depth, state, j, n = 0, None, i, len(text)
    while j < n:
        c = text[j]
        if state == "'":
            state = None if c == "'" else state
        elif c == "\\":
            j += 2
            continue
        elif state == '"':
            if c == '"':
                state = None
            elif text.startswith("$(", j):
                k = matching_paren(text, j + 1)
                if k < 0:
                    return -1
                j = k + 1
                continue
        elif c in "'\"":
            state = c
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return j
        j += 1
    return -1


def extract_substitutions(text, quotes=True, tag=0):
    """Replaces each $(…) and `…` with `$__SUB<tag>_n__`; returns (text, [inner commands]).

    With quotes=False (the body of a heredoc that expands), quotes are plain characters."""
    out, inner = [], []
    i, n, state = 0, len(text), None
    while i < n:
        c = text[i]
        if quotes and state == "'":
            out.append(c)
            state = None if c == "'" else state
            i += 1
            continue
        if c == "\\" and i + 1 < n:
            out.append(text[i : i + 2])
            i += 2
            continue
        if quotes and c == "'" and state is None:
            state = "'"
        elif quotes and c == '"':
            state = None if state == '"' else '"'
        elif text.startswith("$((", i):
            out.append("$((")
            i += 3
            continue
        elif text.startswith("$(", i):
            j = matching_paren(text, i + 1)
            if j < 0:
                raise Unparseable("guard: $( senza la parentesi di chiusura; riscrivilo più semplice.")
            inner.append(text[i + 2 : j])
            out.append(f"$__SUB{tag}_{len(inner) - 1}__")
            i = j + 1
            continue
        elif c == "`":
            j = i + 1
            while j < n and text[j] != "`":
                j += 2 if text[j] == "\\" else 1
            if j >= n:
                raise Unparseable("guard: apice inverso senza chiusura; riscrivilo più semplice.")
            inner.append(text[i + 1 : j].replace("\\`", "`"))
            out.append(f"$__SUB{tag}_{len(inner) - 1}__")
            i = j + 1
            continue
        out.append(c)
        i += 1
    return "".join(out), inner


def without_single_quotes(text):
    """The text bash expands: single-quoted parts and escaped dollars removed."""
    out, state, i = [], None, 0
    while i < len(text):
        c = text[i]
        if state == "'":
            state = None if c == "'" else state
            i += 1
            continue
        if c == "\\" and i + 1 < len(text):
            if text[i + 1] != "$":
                out.append(text[i : i + 2])
            i += 2
            continue
        if c == "'" and state is None:
            state = "'"
            i += 1
            continue
        if c == '"':
            state = None if state == '"' else '"'
        out.append(c)
        i += 1
    return "".join(out)


class Op(str):
    """An unquoted shell operator (;, &&, |, >, …), unlike a quoted word that looks the same."""


OPERATORS = [
    ";;&", "<<<", "&>>", "&>|",
    "&&", "||", ";;", ";&", "|&", ">>", ">|", "&>", ">&", "<&", "<>", "<<", "<(", ">(", "((", "))", "()",
    "(", ")", ";", "&", "|", "<", ">",
]


def without_quotes(text):
    """The text outside any quotes: what bash reads as commands and operators."""
    out, state, i = [], None, 0
    while i < len(text):
        c = text[i]
        if c == "\\" and state != "'" and i + 1 < len(text):
            if state is None:
                out.append(text[i : i + 2])
            i += 2
            continue
        if state is None and c in "'\"":
            state = c
            out.append(" ")
        elif state is not None and c == state:
            state = None
        elif state is None:
            out.append(c)
        i += 1
    return "".join(out)


def tokenize(text):
    """Words and operators, split the way bash splits them. Quotes are removed from words, and a
    quoted ';' or '|' stays a word: only unquoted operators become Op."""
    tokens, word, in_word = [], [], False
    i, n = 0, len(text)

    def flush():
        nonlocal word, in_word
        if in_word:
            tokens.append("".join(word))
        word, in_word = [], False

    while i < n:
        c = text[i]
        if c in " \t":
            flush()
            i += 1
        elif c == "\n":
            flush()
            tokens.append(Op(";"))
            i += 1
        elif c == "\\":
            if i + 1 < n:
                word.append(text[i + 1])
                in_word = True
            i += 2
        elif c == "'":
            j = text.find("'", i + 1)
            if j < 0:
                raise Unparseable("guard: apice singolo senza chiusura; riscrivilo più semplice.")
            word.append(text[i + 1 : j])
            in_word = True
            i = j + 1
        elif c == '"':
            j, buf = i + 1, []
            while j < n and text[j] != '"':
                if text[j] == "\\" and j + 1 < n and text[j + 1] in '"\\$`\n':
                    buf.append(text[j + 1])
                    j += 2
                    continue
                buf.append(text[j])
                j += 1
            if j >= n:
                raise Unparseable("guard: virgolette senza chiusura; riscrivilo più semplice.")
            word.append("".join(buf))
            in_word = True
            i = j + 1
        elif c in "|&;()<>":
            op = next(o for o in OPERATORS if text.startswith(o, i))
            if in_word and op[0] in "<>" and "".join(word).isdigit():
                word, in_word = [], False  # 2>file: the number is a file descriptor
            flush()
            tokens.append(Op(op))
            i += len(op)
        else:
            word.append(c)
            in_word = True
            i += 1
    flush()
    return tokens


OUT_REDIRECTS = {">", ">>", ">|", "&>", "&>>", "<>", ">&", "&>|"}
IN_REDIRECTS = {"<", "<&"}
FD = re.compile(r"^(\d+-?|-)$")


def split_redirects(raw):
    """(words, written files, read files, heredoc indexes, here-strings) of one simple command."""
    words, outs, ins, docs, strings = [], [], [], [], []
    i = 0
    while i < len(raw):
        tok = raw[i]
        if isinstance(tok, Op):
            target = raw[i + 1] if i + 1 < len(raw) else ""
            if tok in OUT_REDIRECTS:
                if not (tok == ">&" and FD.match(target)):
                    outs.append(target)
            elif tok in IN_REDIRECTS:
                if not FD.match(target):
                    ins.append(target)
            elif tok == "<<<":
                strings.append(target)
            elif tok == "<<":
                m = HEREDOC_MARK.match(target)
                if m:
                    docs.append(int(m.group(1)))
            else:
                raise Unparseable(f"guard: operatore {tok!r} in un punto inatteso; riscrivilo più semplice.")
            i += 2
        else:
            words.append(tok)
            i += 1
    return words, outs, ins, docs, strings


def parse_args(args, value_opts=frozenset()):
    """(positional, [(option, value)]) — `value_opts` are the options that take a value."""
    pos, opts, i = [], [], 0
    while i < len(args):
        a = args[i]
        if a == "--":
            pos.extend(args[i + 1 :])
            break
        if a.startswith("--"):
            name, eq, val = a.partition("=")
            if not eq and name in value_opts and i + 1 < len(args):
                opts.append((name, args[i + 1]))
                i += 2
                continue
            opts.append((name, val))
        elif a.startswith("-") and len(a) > 1:
            if a[:2] in value_opts:
                if len(a) > 2:
                    opts.append((a[:2], a[2:]))
                elif i + 1 < len(args):
                    opts.append((a, args[i + 1]))
                    i += 2
                    continue
                else:
                    opts.append((a, ""))
            else:
                opts.append((a, ""))
        else:
            pos.append(a)
        i += 1
    return pos, opts


def has_flag(opts, letters, longs=()):
    """True when a short option bundle contains one of `letters`, or a long option is in `longs`."""
    for name, _ in opts:
        if name.startswith("--"):
            if name in longs:
                return True
        elif any(ch in name[1:] for ch in letters):
            return True
    return False


def has_expansion(text):
    return "$" in text or "`" in text


BRACES = re.compile(r"\{([^{}]*,[^{}]*)\}")


def expand_braces(word, limit=64):
    m = BRACES.search(word)
    if not m:
        return [word]
    out = []
    for part in m.group(1).split(","):
        out.extend(expand_braces(word[: m.start()] + part + word[m.end() :], limit))
        if len(out) > limit:
            raise Blocked("guard: troppe alternative tra graffe; scrivi i percorsi per esteso.")
    return out


def expand_word(word, base):
    """Absolute paths a word can stand for: braces and globs expanded, plus the literal folder of a
    glob (so that `rm .github/*.yml` is seen even when nothing matches)."""
    out = []
    for alt in expand_braces(word):
        full = alt if os.path.isabs(alt) else os.path.join(base, alt)
        cuts = [full.find(ch) for ch in "*?[" if ch in full]
        if cuts:
            out.append(full[: min(cuts)].rsplit("/", 1)[0] or "/")
            out.extend(sorted(glob.glob(full)))
        else:
            out.append(full)
    return out


def patch_paths(text, strip):
    """Files a patch writes. Checks the path as written and with 1 or `strip` components removed."""
    paths = set()
    for line in text.splitlines():
        m = re.match(r"^diff --git a/(\S+) b/(\S+)", line)
        if m:
            paths.update(m.groups())
            continue
        m = re.match(r"^(?:---|\+\+\+) (\S+)", line)
        if m and m.group(1) != "/dev/null":
            parts = m.group(1).split("/")
            for s in {0, 1, strip}:
                if s < len(parts):
                    paths.add("/".join(parts[s:]))
            continue
        m = re.match(r"^(?:rename|copy) (?:from|to) (.+)$", line)
        if m:
            paths.add(m.group(1).strip())
    return sorted(paths)


EMBEDDED_LIST = re.compile(r"""\[\s*(["'])(?:gh|git)\1[^\]\n]*\]?""")
EMBEDDED_STRING = re.compile(r"""(["'])((?:gh|git)\s[^"'\n]*)\1""")


def embedded_commands(code):
    """gh and git commands written inside other code, as strings or argument lists."""
    for m in EMBEDDED_LIST.finditer(code):
        items = re.findall(r"""["']([^"'\n]*)["']""", m.group(0))
        yield " ".join(shlex.quote(x) for x in items)
    for m in EMBEDDED_STRING.finditer(code):
        yield m.group(2)


# ------------------------------------------------------------------------------- programs

MERGE = "Il merge lo fa solo il workflow consegna (METODO §4)."
MAIN_PUSH = "Push verso main bloccato: su main arriva solo il merge del workflow consegna."
CHAIN = "Un giro non avvia altri giri: la catena la fa il workflow consegna."
ENV_MSG = "Niente stampa delle variabili d'ambiente (CLAUDE.md regola 4)."
SECRET_MSG = "Niente token, segreti o password nei comandi (CLAUDE.md regola 4)."
GH_WRITE = "Scritture su GitHub solo con i comandi gh previsti dalla skill (PR, etichette, commenti)."

RAW_DANGER = [
    (re.compile(r"\bgh\s+pr\s+merge\b"), MERGE),
    (re.compile(r"pulls/\d+/merge\b|\brepos/[^/\s'\"]+/[^/\s'\"]+/merges\b"), MERGE),
    (
        re.compile(
            r"\b(mergePullRequest|enablePullRequestAutoMerge|addLabelsToLabelable|removeLabelsFromLabelable|"
            r"clearLabelsFromLabelable|updateRef|deleteRef|createRef|createCommitOnBranch|updatePullRequestBranch)\b"
        ),
        "Mutazione GraphQL di GitHub non permessa: usa i comandi gh previsti dalla skill.",
    ),
    (re.compile(r"\bpush\b[^\n;&|]*\s\+?(refs/heads/)?(main|master)(\s|$|[;&|)\"'])"), MAIN_PUSH),
    (re.compile(r":\+?(refs/heads/)?(main|master)\b"), MAIN_PUSH),
    (re.compile(r"/routines/|claude_code/routines"), CHAIN),
    # The same commands written as argument lists, e.g. subprocess.run(["gh", "pr", "merge", …]).
    (re.compile(r"""["']gh["']\s*,\s*["']pr["']\s*,\s*["']merge["']"""), MERGE),
    (re.compile(r"""["']push["'][^\]\n]*["']\+?(refs/heads/)?(main|master)["']"""), MAIN_PUSH),
]
# Extra checks for code run by an interpreter (python, node, perl, …).
CODE_DANGER = [
    (
        re.compile(
            r"\bos\.environ\b(?!\s*(\[|\.get\b|\.setdefault\b))|\bos\.getenv\(\s*\)|\bprocess\.env\b(?!\s*[.\[])|"
            r"\bSystem\.getenv\(\s*\)|%ENV\b|\bENV\.(to_h|to_a|each|inspect|keys)\b|\bENVIRON\b|"
            r"(system|popen|run|call|check_output|exec\w*|spawn\w*)\s*\(\s*\[?\s*[\"'](/\S*/)?(env|printenv)\b"
        ),
        ENV_MSG,
    ),
    (re.compile(r"\b[A-Z0-9_]*(TOKEN|SECRET|PASSWORD|PASSWD|API_KEY|PRIVATE_KEY|CREDENTIAL)[A-Z0-9_]*\b"), SECRET_MSG),
    (re.compile(r"/proc/\S*environ"), ENV_MSG),
]
GH_HOST = re.compile(r"(api|uploads)\.github\.com", re.I)
GH_VERB = re.compile(r"\b(post|put|patch|delete)\b", re.I)
SECRET_NAME = r"[A-Za-z0-9_]*(?:TOKEN|SECRET|PASSWORD|PASSWD|API_KEY|PRIVATE_KEY|CREDENTIAL)[A-Za-z0-9_]*"
SECRET_VAR = re.compile(r"\$\{?(" + SECRET_NAME + ")", re.I)
SECRET_WORD = re.compile(r"^" + SECRET_NAME + r"$", re.I)
INDIRECT = re.compile(r"\$\{!")
SECRET_PATH = re.compile(
    r"(^|/)\.env($|\.(?!example$)[^/]*$)|(^|/)(\.git-credentials|\.netrc|\.pgpass|id_(rsa|ed25519|ecdsa|dsa))$|"
    r"(^|/)\.ssh(/|$)|(^|/)\.config/gh(/|$)|(^|/)\.docker/config\.json$|(^|/)\.aws(/|$)|"
    r"\.(pem|p12|pfx|jks|keystore)$|^/proc/.*environ$"
)
VAR_REF = re.compile(r"\$(?:\{([A-Za-z_][A-Za-z0-9_]*)\}|([A-Za-z_][A-Za-z0-9_]*))")
ASSIGNMENT = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)\+?=(.*)$", re.S)
DANGEROUS_VARS = re.compile(
    r"^(PATH|BASH_ENV|ENV|LD_PRELOAD|LD_LIBRARY_PATH|LD_AUDIT|PROMPT_COMMAND|IFS|GIT_CONFIG\w*|GIT_EXEC_PATH|"
    r"GIT_SSH\w*|GIT_ASKPASS|SSH_ASKPASS|GIT_DIR|GIT_WORK_TREE|GIT_PROXY_COMMAND|GH_CONFIG_DIR|GH_HOST)$"
)
PROGRAM_VARS = re.compile(r"^(PAGER|GIT_PAGER|GH_PAGER|EDITOR|VISUAL|GIT_EDITOR|GIT_SEQUENCE_EDITOR|GH_EDITOR|BROWSER|GH_BROWSER)$")
HARMLESS_PROGRAMS = {"", "cat", "less", "more", "true", ":"}
DURATION = re.compile(r"^\d+(\.\d+)?[smhd]?$")
NUMERIC = re.compile(r"^\d[\d.,:x-]*[a-zA-Z]?$")

SEPARATORS = {"&&", "||", ";", "|", "&", ";;", "|&", ";&", ";;&", "()"}
OPENERS = {"(", "<(", ">(", "(("}
CLOSERS = {")", "))"}
KEYWORDS = {"if", "then", "else", "elif", "fi", "do", "done", "while", "until", "!", "{", "}", "time", "coproc", "esac"}
WRAPPERS = {"command", "builtin", "nohup", "exec", "noglob", "setsid", "unbuffer", "busybox", "nice", "ionice", "chrt", "stdbuf", "taskset", "caffeinate"}
# Wrappers with options that take a value, then the command.
VALUED_WRAPPERS = {
    "xvfb-run": {"-n", "--server-num", "-s", "--server-args", "-f", "--auth-file", "-e", "--error-file", "-p", "--xauth-protocol", "-w", "--wait"},
    "flock": {"-w", "--timeout", "-E", "--conflict-exit-code"},
}
SYSTEM_DIRS = ("/usr/", "/bin/", "/sbin/", "/lib/", "/lib64/", "/opt/", "/snap/", "/nix/")
# Programs that run something later, elsewhere or as someone else: not from a round.
NEVER_PROGRAMS = {
    "sudo", "doas", "su", "pkexec", "runuser", "setpriv", "chroot", "nsenter", "unshare", "crontab", "at", "batch",
    "systemd-run", "tmux", "screen", "parallel", "strace", "ltrace", "gdb", "fakeroot", "proot", "firejail",
    "valgrind", "expect",
}
# Option values that are text for people (messages, titles, search patterns), not commands.
MESSAGE_OPTIONS = {"-m", "--message", "--title", "--body", "--titolo", "--testo", "--motivo", "--comment", "--subject", "-e", "--regexp"}
TEXT_PROGRAMS = {"echo", "printf", "grep", "egrep", "fgrep", "rg"}
SHELLS = {"bash", "sh", "zsh", "dash", "ksh", "mksh", "ash", "fish", "rbash"}
INTERPRETER = re.compile(r"^(python|pypy|node|nodejs|perl|ruby|php|groovy|jshell|java|bun|deno|lua|Rscript|pwsh|awk|gawk|mawk|nawk)(\d[\d.]*)?$")
CODE_LETTERS = {"python": "c", "pypy": "c", "node": "ep", "nodejs": "ep", "bun": "e", "perl": "eE", "ruby": "e", "php": "r", "groovy": "e", "lua": "e", "Rscript": "e", "pwsh": "c"}
INTERP_VALUE_OPTS = {"-W", "-X", "-r", "--require", "--import", "--loader", "-I", "-M", "-C", "--conditions", "--input-type"}
AWK = {"awk", "gawk", "mawk", "nawk"}
REMOTE_PROGRAMS = {"ssh", "scp", "sftp", "rsync", "ssh-keyscan", "ssh-copy-id", "mosh", "telnet", "nc", "ncat", "netcat", "socat"}

ALL_ARGS = {
    "tee", "rm", "unlink", "rmdir", "shred", "truncate", "touch", "mkdir", "mkfifo", "mknod", "ed", "ex", "vi", "vim",
    "nvim", "nano", "emacs", "gzip", "gunzip", "bzip2", "bunzip2", "xz", "unxz", "zstd", "unzstd", "lzma",
}
COMPRESSORS = {"gzip", "gunzip", "bzip2", "bunzip2", "xz", "unxz", "zstd", "unzstd", "lzma"}
OWNER_FIRST = {"chmod", "chown", "chgrp"}
MOVERS = {"cp", "install", "ln", "mv"}
WRITERS = ALL_ARGS | OWNER_FIRST | MOVERS | {"dd", "sort", "sed", "tar", "unzip", "curl", "wget", "patch", "docker"}
VALUE_OPTS = {
    "truncate": {"-s", "--size", "-r", "--reference"},
    "touch": {"-d", "--date", "-t", "-r", "--reference"},
    "mkdir": {"-m", "--mode"},
    "shred": {"-n", "--iterations", "-s", "--size"},
    "cp": {"-S", "--suffix", "-t", "--target-directory"},
    "mv": {"-S", "--suffix", "-t", "--target-directory"},
    "ln": {"-S", "--suffix", "-t", "--target-directory"},
    "install": {"-m", "--mode", "-o", "--owner", "-g", "--group", "-S", "--suffix", "-t", "--target-directory"},
    "sort": {"-o", "--output", "-k", "--key", "-t", "--field-separator", "-T", "--temporary-directory", "-S", "--buffer-size"},
    "sed": {"-e", "--expression", "-f", "--file", "-l", "--line-length"},
    "unzip": {"-d", "-x", "-P"},
    "patch": {
        "-p", "-i", "--input", "-d", "--directory", "-o", "--output", "-r", "--reject-file", "-B", "--prefix",
        "-z", "--suffix", "-D", "--ifdef", "-F", "--fuzz", "-V", "--version-control", "-Y", "--basename-prefix",
    },
}

GIT_READ_ONLY = {
    "diff", "log", "show", "status", "rev-parse", "ls-files", "grep", "blame", "shortlog", "describe", "cat-file",
    "ls-tree", "merge-base", "rev-list", "name-rev", "fetch", "worktree", "for-each-ref", "show-ref", "ls-remote",
    "whatchanged", "check-ignore", "diff-tree", "diff-index", "diff-files", "range-diff", "count-objects", "help",
    "version",
}
GIT_REVIEWER_EXTRA = {"checkout", "restore", "stash", "reset", "apply", "clean", "switch"}
GIT_NEVER = {"filter-branch", "filter-repo", "replace", "update-ref", "fast-import"}
GIT_BUILDER_NEVER = {"rebase", "revert"}
BRANCH_LISTING = {
    "--show-current", "-a", "--all", "-r", "--remotes", "-l", "--list", "-v", "-vv", "--verbose", "--contains",
    "--no-contains", "--merged", "--no-merged", "--points-at", "--format", "--sort", "--column", "--no-column",
    "--color", "--no-color", "-i", "--ignore-case",
}
BRANCH_VALUE = {"--contains", "--no-contains", "--merged", "--no-merged", "--points-at", "--format", "--sort"}
DANGEROUS_GIT_CONFIG = re.compile(
    r"(?i)^(alias\.|core\.(hookspath|sshcommand|pager|editor|fsmonitor|askpass|gitproxy)|credential|"
    r"http\..*extraheader|url\.|include\.|includeif\.|filter\.|diff\..*\.(textconv|command)|merge\..*\.driver|"
    r"sequence\.editor|gpg\..*program|remote\..*\.(url|pushurl|receivepack|uploadpack|proxy)|protocol\.|"
    r"uploadpack\.|receive\.)"
)
GH_NEVER = {
    "secret", "variable", "ruleset", "auth", "release", "gpg-key", "ssh-key", "codespace", "extension", "extensions",
    "alias", "config", "cache", "attestation", "org", "project", "gist",
}
# The writes a round makes through the REST API (GraphQL is not reachable from cloud sessions).
REST_WRITES = [
    ("POST", re.compile(r"^repos/[^/]+/[^/]+/pulls$"), "pr-create"),
    ("PATCH", re.compile(r"^repos/[^/]+/[^/]+/pulls/\d+$"), "pr-edit"),
    ("POST", re.compile(r"^repos/[^/]+/[^/]+/issues/\d+/labels$"), "labels-add"),
    ("DELETE", re.compile(r"^repos/[^/]+/[^/]+/issues/\d+/labels/([^/]+)$"), "label-remove"),
    ("POST", re.compile(r"^repos/[^/]+/[^/]+/issues/\d+/comments$"), "comment"),
    ("POST", re.compile(r"^repos/[^/]+/[^/]+/pulls/\d+/ccr/(ready_for_review|convert_to_draft)$"), "draft"),
]
PR_FIELDS = {"pr-create": {"title", "head", "base", "body", "draft"}, "pr-edit": {"title", "body", "state"}}
GH_READ_ONLY = {
    ("pr", "view"), ("pr", "diff"), ("pr", "list"), ("pr", "checks"), ("pr", "status"), ("run", "view"),
    ("run", "list"), ("issue", "view"), ("issue", "list"), ("repo", "view"), ("label", "list"),
    ("workflow", "list"), ("workflow", "view"),
}


def interpreter_parts(base, args):
    """(inline code, script file, positional arguments, module mode) of an interpreter call."""
    codes, positional, letters, i = [], [], CODE_LETTERS.get(base, ""), 0
    while i < len(args):
        a = args[i]
        if a == "--":
            positional.extend(args[i + 1 :])
            break
        if a.startswith("--"):
            name, eq, val = a.partition("=")
            if name in ("--eval", "--print", "--command"):
                codes.append(val if eq else (args[i + 1] if i + 1 < len(args) else ""))
                i += 1 if eq else 2
                continue
            i += 2 if (name in INTERP_VALUE_OPTS and not eq) else 1
            continue
        if a.startswith("-") and len(a) > 1:
            if base in ("python", "pypy") and a[-1] == "m":
                return codes, None, [], True
            if letters and a[-1] in letters:
                codes.append(args[i + 1] if i + 1 < len(args) else "")
                i += 2
                continue
            i += 2 if a in INTERP_VALUE_OPTS else 1
            continue
        positional.append(a)
        i += 1
    if base == "java":
        return codes, next((p for p in positional if p.endswith(".java")), None), positional, False
    return codes, (None if codes or not positional else positional[0]), positional, False


def labels_in(args, *flags):
    out = []
    for i, tok in enumerate(args):
        for flag in flags:
            if tok == flag and i + 1 < len(args):
                out.extend(x.strip().lower() for x in args[i + 1].split(","))
            elif tok.startswith(flag + "="):
                out.extend(x.strip().lower() for x in tok.split("=", 1)[1].split(","))
    return [x for x in out if x]


def current_branch(where):
    out = git_out(where or ".", "rev-parse", "--abbrev-ref", "HEAD", timeout=10)
    return (out or "").strip()


class Context:
    """What one command line carries besides its tokens: heredoc bodies and $(…) commands."""

    def __init__(self, heredocs, subs, tag):
        self.heredocs = heredocs
        self.subs = subs
        self.tag = tag

    def own(self, word):
        """Indexes of this line's $(…) markers in `word` (markers of an outer line are skipped)."""
        return [int(k) for t, k in SUB_MARK.findall(word) if int(t) == self.tag]


class Analyzer:
    """Follows one Bash call segment by segment, with the working directory and variables known."""

    def __init__(self, mode, cwd, root):
        self.mode = mode
        self.root = root
        self.cwd = os.path.realpath(cwd) if cwd else root
        self.start = self.cwd
        self.vars = {}
        self.tags = 0
        self.branch = None  # the branch after a git switch/checkout in this call; None: ask git

    # ------------------------------------------------------------------ command lines

    def command(self, text, depth=0, isolated=False, heredocs=None):
        """Checks a command line. `heredocs`: the bodies already taken out of the line that
        contains this one (for a $(…) whose heredoc was removed with the rest of the line)."""
        if depth > 6:
            raise Blocked("guard: troppi comandi annidati; riscrivilo più semplice.")
        saved = (self.cwd, dict(self.vars)) if isolated else None
        try:
            visible, heredocs = preprocess(text, heredocs)
            self.tags += 1
            tag = self.tags
            visible, subs = extract_substitutions(visible, tag=tag)
            for k, inner in enumerate(subs):
                self.vars[f"__SUB{tag}_{k}__"] = self.known_output(inner, heredocs)
            bare = without_quotes(visible)
            for pattern, reason in RAW_DANGER:
                if pattern.search(bare):
                    raise Blocked(reason)
            self.secrets(without_single_quotes(visible), bare)
            self.walk(Context(heredocs, subs, tag), tokenize(visible), depth)
        finally:
            if saved is not None:
                self.cwd, self.vars = saved

    def secrets(self, expanded, bare):
        """Variables with secret-looking names, unless this same line assigned them."""
        assigned = {
            name
            for name, value in re.findall(r"(?:^|[\s;&|(])([A-Za-z_][A-Za-z0-9_]*)\+?=(\S*)", expanded)
            if not SECRET_VAR.search(value)
        }
        if INDIRECT.search(expanded):
            raise Blocked(SECRET_MSG)
        for m in SECRET_VAR.finditer(expanded):
            if m.group(1) not in assigned:
                raise Blocked(SECRET_MSG)

    def walk(self, ctx, tokens, depth):
        stack, current, piped = [], [], False
        for tok in tokens:
            if not isinstance(tok, Op):
                current.append(tok)
            elif tok in OPENERS:
                stack.append((current, piped, tok, self.cwd))
                current, piped = [], False
            elif tok in CLOSERS:
                arithmetic = bool(stack) and stack[-1][2] == "(("
                if current:
                    if arithmetic:  # $(( … )) or (( … )): an expression, only its $(…) run
                        for word in current:
                            for k in ctx.own(word):
                                self.command(ctx.subs[k], depth + 1, isolated=True, heredocs=ctx.heredocs)
                    else:
                        self.segment(ctx, current, piped, depth)
                if stack:
                    current, piped, opener, self.cwd = stack.pop()
                    if opener in ("<(", ">("):
                        current.append("/dev/fd/63")
                    elif opener == "((" and current and current[-1].endswith("$"):
                        current[-1] += "((…))"
                else:
                    current, piped = [], False
            elif tok in SEPARATORS and stack and stack[-1][2] == "((":
                current.append(tok)  # for (( i = 0; i < 3; i++ ))
            elif tok in SEPARATORS:
                if current:
                    self.segment(ctx, current, piped, depth)
                current, piped = [], tok in ("|", "|&")
            else:
                current.append(tok)
        if current:
            self.segment(ctx, current, piped, depth)

    def segment(self, ctx, raw, piped, depth):
        for tok in raw:
            for k in ctx.own(tok):
                self.command(ctx.subs[k], depth + 1, isolated=True, heredocs=ctx.heredocs)
        for word in raw:
            if SECRET_PATH.search(word):
                raise Blocked(f"{word}: file di segreti o credenziali, non si legge né si scrive (CLAUDE.md regola 4).")
        words, outs, ins, docs, strings = split_redirects(raw)
        for k in docs:
            body, quoted = ctx.heredocs[k]
            if not quoted:
                self.expanding(body, depth)
        for target in outs:
            self.write(target)
        words = self.strip(words)
        if not words:
            return
        self.raw_words(words)
        first, args = words[0], words[1:]
        if has_expansion(first):
            raise Blocked("Il programma è in una variabile o viene da un altro comando: scrivilo per esteso.")
        prog = os.path.basename(first)
        match = INTERPRETER.match(prog)
        stdin = [ctx.heredocs[k][0] for k in docs] + strings
        for f in ins:
            text = self.read_text(f)
            if text is not None:
                stdin.append(text)
        if self.builtin(prog, args, depth):
            return
        if prog in REMOTE_PROGRAMS:
            raise Blocked(f"{prog} non è permesso: nessun comando sui server, il deploy è del workflow consegna (CLAUDE.md regola 7).")
        if prog in SHELLS:
            self.shell(ctx, args, stdin, piped, depth)
        elif prog in ("source", "."):
            if args:
                self.script(args[0], depth, "shell")
        elif prog == "eval":
            self.command(" ".join(args), depth + 1)
        elif prog in ("xargs", "parallel"):
            self.xargs(args, depth)
        elif prog == "find":
            self.find(args, depth)
        elif prog == "watch":
            raise Blocked("watch non serve in un giro.")
        elif match:
            self.interpreter(ctx, match.group(1), args, stdin, piped, depth)
        elif prog in WRITERS or prog in ("git", "gh"):
            for target, tree in self.targets(prog, args, stdin, piped):
                self.write(target, tree)
            if prog in ("curl", "wget"):
                self.http(prog, args)
            elif prog == "git":
                self.git(args, stdin, piped, depth)
            elif prog == "gh":
                self.gh(args, stdin, piped)
            elif prog == "docker":
                self.docker(args)
        elif "/" in first:
            self.script(first, depth, "auto")
        else:
            found = shutil.which(prog)
            if found and not os.path.realpath(found).startswith(SYSTEM_DIRS):
                self.script(found, depth, "auto")  # a program someone put on the PATH: read it

    def raw_words(self, words):
        """RAW_DANGER on a command's words, quoted text included: a merge or a push to main inside
        a string handed to some other program (git rebase -x, docker exec, …) is still seen."""
        prog = os.path.basename(words[0])
        if prog in TEXT_PROGRAMS:
            return
        kept, skip = [], False
        for word in words:
            if skip:
                skip = False
                continue
            name = word.split("=", 1)[0]
            if name in MESSAGE_OPTIONS:
                skip = "=" not in word
                continue
            kept.append(word)
        text = " ".join(kept)
        for pattern, reason in RAW_DANGER:
            if pattern.search(text):
                raise Blocked(reason)

    def builtin(self, prog, args, depth):
        """Shell builtins and small programs; True when the segment is fully handled here."""
        if prog in ("cd", "pushd"):
            self.change_dir(args)
        elif prog == "popd":
            self.cwd = None
        elif prog in ("printenv", "compgen") or (prog == "set" and not args):
            raise Blocked(ENV_MSG)
        elif prog in ("export", "declare", "typeset", "readonly", "local"):
            names = [a for a in args if not a.startswith(("-", "+"))]
            if not names and prog != "local":
                raise Blocked(ENV_MSG)
            if any(SECRET_WORD.match(a.split("=", 1)[0]) and "=" not in a for a in names):
                raise Blocked(SECRET_MSG)
            for a in names:
                m = ASSIGNMENT.match(a)
                if m:
                    self.assign(m.group(1), m.group(2))
        elif prog == "alias" or prog == "enable" or (prog == "shopt" and "expand_aliases" in args) or (prog == "hash" and "-p" in args):
            raise Blocked(f"{prog}: alias e scorciatoie non servono in un giro; scrivi i comandi per esteso.")
        elif prog == "trap":
            if args and not args[0].startswith("-"):
                self.command(args[0], depth + 1, isolated=True)
        elif prog == "ps":
            if args and not args[0].startswith("-") and "e" in args[0]:
                raise Blocked("ps con le variabili d'ambiente dei processi non è permesso (CLAUDE.md regola 4).")
        elif prog == "jq":
            if any(re.search(r"(^|[^.\w])env\b|\$ENV\b", a) for a in args):
                raise Blocked(ENV_MSG)
            return False
        elif prog == "script":
            valued = {"-E", "--echo", "-o", "--output-limit", "-T", "--log-timing", "-I", "--log-in", "-O", "--log-out", "-B", "--log-io", "-m", "--logging-format"}
            i, files = 0, []
            while i < len(args):
                a = args[i]
                if a == "--command" or re.fullmatch(r"-[A-Za-z]*c", a):
                    if i + 1 < len(args):
                        self.command(args[i + 1], depth + 1, isolated=True)
                    i += 2
                elif a.startswith("--command="):
                    self.command(a.split("=", 1)[1], depth + 1, isolated=True)
                    i += 1
                elif a in valued:
                    i += 2
                elif a.startswith("-"):
                    i += 1
                else:
                    files.append(a)
                    i += 1
            for p in files[:1]:
                self.write(p)
        elif prog in NEVER_PROGRAMS:
            raise Blocked(f"{prog} non serve in un giro: niente comandi che girano dopo, altrove o come un altro utente.")
        elif prog in ("mvnw", "mvn") and any(re.search(r"help:(system|effective-settings)", a) for a in args):
            raise Blocked(ENV_MSG)
        else:
            return False
        return True

    def strip(self, words):
        """Drops what comes before the program: assignments, keywords, env, timeout, nohup, …"""
        i = 0
        while i < len(words):
            tok = words[i]
            name = os.path.basename(tok)
            m = ASSIGNMENT.match(tok)
            if m:
                self.assign(m.group(1), m.group(2))
                i += 1
            elif tok in KEYWORDS:
                i += 1
            elif tok == "function":
                i += 2
            elif tok in ("for", "select", "case", "in"):
                return []
            elif name in NEVER_PROGRAMS:
                raise Blocked(f"{name} non è permesso nel laboratorio.")
            elif name in VALUED_WRAPPERS:
                i += 1
                while i < len(words) and words[i].startswith("-"):
                    if name == "flock" and words[i] in ("-c", "--command") and i + 1 < len(words):
                        self.command(words[i + 1], 1, isolated=True)
                        return []
                    i += 2 if words[i] in VALUED_WRAPPERS[name] else 1
                if name == "flock":
                    i += 1  # the lock file
            elif name == "env":
                i += 1
                while i < len(words) and (words[i].startswith("-") or ASSIGNMENT.match(words[i])):
                    w = words[i]
                    if w.startswith(("-S", "-C", "--split-string", "--chdir")):
                        raise Blocked("env -S/-C non serve in un giro: scrivi il comando per esteso.")
                    m = ASSIGNMENT.match(w)
                    if m:
                        self.assign(m.group(1), m.group(2))
                    i += 2 if w in ("-u", "--unset") else 1
                if i >= len(words):
                    raise Blocked("env senza comando stampa le variabili d'ambiente (CLAUDE.md regola 4).")
            elif name == "timeout":
                i += 1
                while i < len(words) and (words[i].startswith("-") or DURATION.match(words[i])):
                    i += 2 if words[i] in ("-s", "--signal", "-k", "--kill-after") else 1
            elif name in WRAPPERS:
                if name == "command" and i + 1 < len(words) and words[i + 1] in ("-v", "-V"):
                    return []
                i += 1
                while i < len(words) and (words[i].startswith("-") or NUMERIC.match(words[i])):
                    i += 1
            else:
                break
        return words[i:]

    # ------------------------------------------------------------------ values and paths

    def assign(self, name, value):
        if DANGEROUS_VARS.match(name):
            raise Blocked(f"La variabile {name} cambia quali programmi eseguono la shell o git: non si imposta da un giro.")
        if PROGRAM_VARS.match(name) and value not in HARMLESS_PROGRAMS:
            raise Blocked(f"{name}={value} fa eseguire un programma: usa cat, o lascia il valore predefinito.")
        self.vars[name] = self.resolve(value)

    def known_output(self, inner, heredocs):
        """What a few harmless commands print, when it matters for a path, a branch or a commit
        message: the current branch, the folder, a temporary file, `cat <<'EOF' … EOF`. None for
        anything else."""
        m = re.fullmatch(r"\s*cat\s*<<\s*__HEREDOC_(\d+)__\s*", inner)
        if m and int(m.group(1)) < len(heredocs):
            return heredocs[int(m.group(1))][0]
        words = inner.split()
        if words in (
            ["git", "branch", "--show-current"],
            ["git", "rev-parse", "--abbrev-ref", "HEAD"],
            ["git", "symbolic-ref", "--short", "HEAD"],
        ):
            return current_branch(self.cwd or self.root) or None
        if words == ["pwd"]:
            return self.cwd
        if words == ["git", "rev-parse", "--show-toplevel"]:
            return self.root
        if words and words[0] == "mktemp":
            rest = words[1:]
            if any(w.startswith(("-p", "--tmpdir")) for w in rest):
                return None
            template = next((w for w in rest if not w.startswith("-")), "")
            if not template or "-t" in rest:
                return "/tmp/" + (template or "tmp.XXXXXX").replace("X", "g")
            return template.replace("X", "g") if template.startswith("/tmp/") else None
        return None

    def resolve(self, text):
        """`text` with ~ and the known variables expanded; None when something stays unknown."""
        if text == "~" or text.startswith("~/"):
            text = os.path.expanduser("~") + text[1:]
        known = {
            "HOME": os.path.expanduser("~"),
            "CLAUDE_PROJECT_DIR": self.root,
            "PWD": self.cwd,
            "TMPDIR": os.environ.get("TMPDIR") or "/tmp",
        }
        known.update(self.vars)
        unknown = []

        def value(m):
            v = known.get(m.group(1) or m.group(2))
            if v is None:
                unknown.append(m.group(0))
                return ""
            return v

        out = VAR_REF.sub(value, text)
        return None if unknown or has_expansion(out) else out

    def bases(self):
        """Folders a relative path may start from (both candidates after an unknown `cd`)."""
        return [self.cwd] if self.cwd else list(dict.fromkeys([self.start, self.root]))

    def change_dir(self, args):
        args = [a for a in args if a not in ("-L", "-P", "-e", "-@", "--")]
        if not args:
            self.cwd = os.path.expanduser("~")
            return
        value = None if args[0] == "-" else self.resolve(args[0])
        if value is None or (not os.path.isabs(value) and self.cwd is None):
            self.cwd = None
            return
        self.cwd = os.path.realpath(value if os.path.isabs(value) else os.path.join(self.cwd, value))

    def write(self, token, tree=False):
        """Raises Blocked when the word `token`, as a file written (or, with tree, a folder written
        recursively), touches what this mode may not touch."""
        if not token or token == "-" or token.startswith("/dev/") or re.fullmatch(r"&?\d+-?", token):
            return
        value = self.resolve(token)
        if value is None:
            head = self.resolve(re.split(r"[$`]", token, maxsplit=1)[0]) or ""
            if os.path.realpath(head).startswith("/tmp/") and head.startswith("/tmp/"):
                return
            raise Blocked(
                f"Scrittura su un percorso con una variabile o un comando ({token}): scrivi il percorso per "
                "esteso; per i file temporanei usa /tmp/…."
            )
        for base in self.bases():
            for path in expand_word(value, base):
                self.check_written(path, tree)

    def check_written(self, path, tree):
        if tree and contains_root(path, self.root):
            raise Blocked("Operazione che comprende tutto il repository: indica le cartelle precise.")
        check_outside(path, tree)
        rel = relative(path, None, self.root)
        if rel is None:
            return
        if self.mode in WHO:
            raise Blocked(f"Il {WHO[self.mode]} non scrive nel repository ({rel}): lavora in /tmp/….")
        (check_tree if tree else check_path)(rel, self.root, self.mode)

    def candidates(self, token):
        value = self.resolve(token)
        if value is None:
            return []
        return [value if os.path.isabs(value) else os.path.join(base, value) for base in self.bases()]

    def is_dir(self, token):
        return any(os.path.isdir(p) for p in self.candidates(token))

    def read_text(self, token, limit=2_000_000):
        for path in self.candidates(token):
            if os.path.isfile(path):
                with open(path, "rb") as f:
                    if b"\0" in f.read(8192):
                        return None  # a compiled program: not readable here (README, «Cosa è garantito»)
                if os.path.getsize(path) > limit:
                    raise Blocked(f"{token}: file di testo troppo grande da controllare.")
                with open(path, "rb") as f:
                    return f.read().decode("utf-8", "replace")
        return None

    # ------------------------------------------------------------------ code that runs

    def expanding(self, body, depth):
        """A heredoc whose delimiter is not quoted: bash expands variables and runs $(…) in it."""
        if SECRET_VAR.search(body) or INDIRECT.search(body):
            raise Blocked(SECRET_MSG)
        _, inner = extract_substitutions(body, quotes=False)
        for cmd in inner:
            self.command(cmd, depth + 1, isolated=True)

    def code(self, text, depth, origin):
        """Code for an interpreter: what can be seen without running it."""
        for pattern, reason in RAW_DANGER + CODE_DANGER:
            if pattern.search(text):
                raise Blocked(f"{reason} [{origin}]")
        if GH_HOST.search(text) and GH_VERB.search(text):
            raise Blocked(f"{GH_WRITE} [{origin}]")
        for cmd in embedded_commands(text):
            try:
                self.command(cmd, depth + 1, isolated=True)
            except Unparseable:
                pass

    def script(self, token, depth, kind):
        if re.match(r"^/(dev|proc)/", token):
            raise Blocked("Codice da una sostituzione di processo o da /dev: scrivilo in un file, così si può controllare.")
        if has_expansion(token) and self.resolve(token) is None:
            raise Blocked(f"Script in una variabile ({token}): scrivi il percorso per esteso.")
        for path in self.candidates(token):
            text = self.read_script(path)
            if text is None:
                continue
            shell = kind == "shell" or (
                kind == "auto" and (not text.startswith("#!") or re.match(r"#!\S*(/|\s)(ba|z|da|k)?sh\b", text.split("\n", 1)[0]))
            )
            if shell:
                try:
                    self.command(text, depth + 1, isolated=True)
                except Unparseable:
                    self.code(text, depth, token)
                    if SECRET_VAR.search(without_single_quotes(text)):
                        raise Blocked(f"{SECRET_MSG} [{token}]")
            else:
                self.code(text, depth, token)

    def read_script(self, path):
        real = os.path.realpath(path)
        if not os.path.isfile(real):
            return None
        rel = relative(real, None, self.root)
        if rel is not None and is_rule(rel):
            return None  # the lab's own scripts: a round cannot change them
        if os.path.basename(real) == "mvnw":
            try:
                with open(real, "rb") as a, open(os.path.join(self.root, "mvnw"), "rb") as b:
                    if a.read() == b.read():
                        return None  # the Maven wrapper, as in the repository
            except OSError:
                pass
        return self.read_text(real)

    def stdin_code(self, ctx, stdin, piped, depth, shell, origin):
        """Code read from standard input: heredocs, here-strings, `< file`, or a pipe."""
        bodies = list(stdin)
        if not bodies and piped:
            if not ctx.heredocs:
                raise Blocked(f"Codice per {origin} da una pipe: scrivilo in un file e lancialo, così si può controllare.")
            bodies = [body for body, _ in ctx.heredocs]  # e.g. cat <<'EOF' | python3
        for body in bodies:
            if shell:
                self.command(body, depth + 1, isolated=True)
            else:
                self.code(body, depth, origin)

    def shell(self, ctx, args, stdin, piped, depth):
        command_mode, i = False, 0
        while i < len(args):
            a = args[i]
            if a in ("--rcfile", "--init-file") and i + 1 < len(args):
                self.script(args[i + 1], depth, "shell")
                i += 2
            elif a in ("-o", "+o", "-O", "+O") and i + 1 < len(args):
                i += 2
            elif a == "--":
                i += 1
                break
            elif a.startswith("--"):
                i += 1
            elif a[:1] in "-+" and len(a) > 1:
                command_mode = command_mode or "c" in a[1:]
                i += 1
            else:
                break
        rest = args[i:]
        if command_mode:
            if rest:
                self.command(rest[0], depth + 1, isolated=True)
        elif rest:
            self.script(rest[0], depth, "shell")
        else:
            self.stdin_code(ctx, stdin, piped, depth, shell=True, origin="la shell")

    def interpreter(self, ctx, base, args, stdin, piped, depth):
        if base in AWK:
            pos, opts = parse_args(args, {"-f", "--file", "-v", "--assign", "-F", "--field-separator"})
            files = [v for n, v in opts if n in ("-f", "--file")]
            for f in files:
                self.script(f, depth, "code")
            if not files and pos:
                self.code(pos[0], depth, base)
            return
        codes, script, positional, module = interpreter_parts(base, args)
        if module:
            return
        if base in ("perl", "ruby") and any(re.fullmatch(r"-[lnpaFsw0-9]*i.*", a) for a in args):
            for f in positional if codes else positional[1:]:
                self.write(f)
        for code in codes:
            self.code(code, depth, base)
        if script and script != "-":
            self.script(script, depth, "code")
        elif not codes:
            self.stdin_code(ctx, stdin, piped, depth, shell=False, origin=base)

    def xargs(self, args, depth):
        i = 0
        while i < len(args) and args[i].startswith("-"):
            name = args[i].split("=", 1)[0]
            takes = name in ("-I", "-n", "-P", "-d", "-L", "-s", "-E", "-a", "--max-args", "--max-procs",
                             "--delimiter", "--arg-file", "--max-lines", "--max-chars", "--eof") and "=" not in args[i]
            i += 2 if takes else 1
        inner = args[i:]
        if not inner:
            return
        prog = os.path.basename(inner[0])
        if (
            has_expansion(inner[0]) or "/" in inner[0] or prog in WRITERS or prog in SHELLS
            or prog in ("git", "gh", "eval", "env", "xargs", "find", "perl", "ruby") or INTERPRETER.match(prog)
        ):
            raise Blocked(
                f"xargs con {prog}: gli argomenti arrivano da una pipe e non si possono controllare. "
                "Scrivi i comandi per esteso."
            )
        self.command(" ".join(shlex.quote(a) for a in inner), depth + 1, isolated=True)

    def find(self, args, depth):
        i, starts = 0, []
        while i < len(args) and (args[i] in ("-H", "-L", "-P") or args[i].startswith(("-D", "-O"))):
            i += 2 if args[i] == "-D" else 1
        while i < len(args) and not args[i].startswith(("-", "(", "!", ")")):
            starts.append(args[i])
            i += 1
        expr = args[i:]
        writes = "-delete" in expr
        for j, a in enumerate(expr):
            if a in ("-fprint", "-fprint0", "-fprintf", "-fls") and j + 1 < len(expr):
                self.write(expr[j + 1])
            if a in ("-exec", "-execdir", "-ok", "-okdir"):
                inner = []
                for b in expr[j + 1 :]:
                    if b in (";", "+"):
                        break
                    inner.append(b)
                inner = [b for b in inner if b != "{}"]
                if inner:
                    prog = os.path.basename(inner[0])
                    if prog in WRITERS or prog in SHELLS or prog == "git" or INTERPRETER.match(prog):
                        writes = True
                    self.command(" ".join(shlex.quote(b) for b in inner), depth + 1, isolated=True)
        if writes:
            for start in starts or ["."]:
                self.write(start, tree=True)

    # ------------------------------------------------------------------ what a program writes

    def targets(self, prog, args, stdin, piped):
        """[(word, recursive)] for the files and folders `prog` writes, from its arguments."""
        if prog in ALL_ARGS:
            pos, opts = parse_args(args, VALUE_OPTS.get(prog, frozenset()))
            if prog in COMPRESSORS and has_flag(opts, "clt", ("--stdout", "--to-stdout", "--list", "--test")):
                return []
            tree = prog == "rm" and has_flag(opts, "rR", ("--recursive",))
            return [(p, tree) for p in pos]
        if prog in OWNER_FIRST:
            options = {
                "-R", "-v", "-c", "-f", "-h", "-H", "-L", "-P", "--recursive", "--verbose", "--changes",
                "--silent", "--quiet", "--preserve-root", "--no-preserve-root", "--dereference", "--no-dereference",
            }
            rest = [a for a in args if a not in options and not a.startswith(("--reference=", "--from="))]
            files = rest if any(a.startswith("--reference") for a in args) else rest[1:]
            tree = any(re.fullmatch(r"-[A-Za-z]*R[A-Za-z]*", a) or a == "--recursive" for a in args)
            return [(p, tree) for p in files]
        if prog in MOVERS:
            return self.mover_targets(prog, args)
        if prog == "dd":
            return [(a[3:], False) for a in args if a.startswith("of=")]
        if prog == "sort":
            _, opts = parse_args(args, VALUE_OPTS["sort"])
            return [(v, False) for n, v in opts if n in ("-o", "--output")]
        if prog == "sed":
            pos, opts = parse_args(args, VALUE_OPTS["sed"])
            in_place = any(n == "--in-place" or (not n.startswith("--") and "i" in n[1:]) for n, _ in opts)
            if not in_place:
                return []
            scripted = any(n in ("-e", "--expression", "-f", "--file") for n, _ in opts)
            return [(p, False) for p in (pos if scripted else pos[1:])]
        if prog == "tar":
            return self.tar_targets(args)
        if prog == "unzip":
            pos, opts = parse_args(args, VALUE_OPTS["unzip"])
            if has_flag(opts, "lptvzZ"):
                return []
            return [(next((v for n, v in opts if n == "-d"), "."), True)]
        if prog in ("curl", "wget"):
            return self.download_targets(prog, args)
        if prog == "patch":
            return self.patch_targets(args, stdin, piped)
        return []

    def mover_targets(self, prog, args):
        pos, opts = parse_args(args, VALUE_OPTS[prog])
        target_dir = next((v for n, v in opts if n in ("-t", "--target-directory")), None)
        if target_dir is not None:
            dest, sources = target_dir, pos
        elif len(pos) >= 2:
            dest, sources = pos[-1], pos[:-1]
        elif prog == "ln" and len(pos) == 1:
            dest, sources = ".", pos
        else:
            return []
        recursive = prog == "mv" or (prog == "cp" and has_flag(opts, "rRa", ("--recursive", "--archive")))
        # mv removes its sources; a link to a protected folder would let anything write through it.
        out = [(s, True) for s in sources] if prog in ("mv", "ln") else []
        no_dir = has_flag(opts, "T", ("--no-target-directory",))
        if target_dir is not None or (not no_dir and (dest.endswith("/") or len(sources) > 1 or self.is_dir(dest))):
            out += [(dest.rstrip("/") + "/" + os.path.basename(s.rstrip("/")), recursive) for s in sources]
        else:
            out.append((dest, recursive))
        return out

    def tar_targets(self, args):
        if not args:
            return []
        bundle = args[0] if not args[0].startswith("-") else ""
        letters = set(bundle)
        for a in args:
            if a.startswith("-") and not a.startswith("--"):
                letters |= set(a[1:])
        longs = {a.split("=", 1)[0] for a in args if a.startswith("--")}
        out = []
        if "x" in letters or longs & {"--extract", "--get"}:
            folder = "."
            for i, a in enumerate(args):
                if a in ("-C", "--directory") and i + 1 < len(args):
                    folder = args[i + 1]
                elif a.startswith("--directory="):
                    folder = a.split("=", 1)[1]
            out.append((folder, True))
        if letters & set("cru") or longs & {"--create", "--append", "--update"}:
            for i, a in enumerate(args):
                if a.startswith("--file="):
                    out.append((a.split("=", 1)[1], False))
                elif a == "--file" and i + 1 < len(args):
                    out.append((args[i + 1], False))
                elif ((a.startswith("-") and not a.startswith("--")) or (i == 0 and bundle)) and "f" in a:
                    glued = a.split("f", 1)[1] if a.startswith("-") else ""
                    if glued or i + 1 < len(args):
                        out.append((glued or args[i + 1], False))
        return out

    def download_targets(self, prog, args):
        out, urls, remote_name = [], [], False
        valued = (
            {"-o", "--output", "-D", "--dump-header", "-c", "--cookie-jar", "--trace", "--trace-ascii", "--stderr",
             "--etag-save", "--libcurl", "--hsts", "--alt-svc", "--output-dir"}
            if prog == "curl"
            else {"-O", "--output-document", "-o", "--output-file", "-a", "--append-output", "-P", "--directory-prefix"}
        )
        i = 0
        while i < len(args):
            a = args[i]
            name, eq, val = a.partition("=")
            if a.startswith("--") and eq and name in valued:
                out.append((val, name in ("--output-dir", "--directory-prefix")))
            elif a in valued and i + 1 < len(args):
                out.append((args[i + 1], a in ("--output-dir", "-P", "--directory-prefix")))
                i += 2
                continue
            elif prog == "curl" and re.fullmatch(r"-[A-Za-z]*o", a) and i + 1 < len(args):
                out.append((args[i + 1], False))
                i += 2
                continue
            elif prog == "curl" and (a in ("--remote-name", "--remote-name-all") or re.fullmatch(r"-[A-Za-z]*O[A-Za-z]*", a)):
                remote_name = True
            elif re.match(r"https?://", a, re.I):
                urls.append(a)
            i += 1
        if remote_name or (prog == "wget" and not out):
            for url in urls:
                name = url.split("?", 1)[0].rstrip("/").rsplit("/", 1)[-1]
                if name and "://" not in name:
                    out.append((name, False))
        return out

    def patch_targets(self, args, stdin, piped):
        pos, opts = parse_args(args, VALUE_OPTS["patch"])
        if any(n in ("--dry-run",) for n, _ in opts):
            return []
        strip = next((int(v) for n, v in opts if n == "-p" and v.isdigit()), 0)
        folder = next((v for n, v in opts if n in ("-d", "--directory")), ".")
        texts = []
        for n, v in opts:
            if n in ("-i", "--input"):
                text = self.read_text(v)
                if text is None:
                    raise Blocked(f"patch: il file {v} non si legge; non si può controllare cosa scrive.")
                texts.append(text)
        if not texts:
            texts = list(stdin)
            if not texts and piped:
                raise Blocked("patch da una pipe: salva il patch in un file, così si può controllare cosa scrive.")
        out = [(os.path.join(folder, p), False) for t in texts for p in patch_paths(t, strip)]
        out += [(v, False) for n, v in opts if n in ("-o", "--output")]
        if pos:
            out.append((pos[0], False))
        return out

    # ------------------------------------------------------------------ network and containers

    def http(self, prog, args):
        if any(re.search(r"/routines/|claude_code/routines", a) for a in args):
            raise Blocked(CHAIN)
        hosts = [re.sub(r"^https?://([^/:?#]+).*$", r"\1", a, flags=re.I).lower() for a in args if re.match(r"https?://", a, re.I)]
        if not any(h == "github.com" or h.endswith(".github.com") for h in hosts):
            return
        method, sends = "GET", False
        for i, a in enumerate(args):
            name = a.split("=", 1)[0]
            if prog == "curl":
                if a in ("-X", "--request") and i + 1 < len(args):
                    method = args[i + 1].upper()
                elif name == "--request" and "=" in a:
                    method = a.split("=", 1)[1].upper()
                elif a.startswith("-X") and len(a) > 2:
                    method = a[2:].upper()
                elif name in ("-d", "--data", "--data-raw", "--data-binary", "--data-urlencode", "--data-ascii", "-F",
                              "--form", "--form-string", "--json", "-T", "--upload-file"):
                    sends = True
            elif name in ("--post-data", "--post-file", "--body-data", "--body-file"):
                sends = True
            elif name == "--method":
                method = (a.split("=", 1)[1] if "=" in a else (args[i + 1] if i + 1 < len(args) else "")).upper()
        if sends or method not in ("GET", "HEAD"):
            raise Blocked(GH_WRITE)

    def docker(self, args):
        i = 0
        while i < len(args) and args[i].startswith("-"):
            i += 2 if args[i] in ("--context", "-c", "-H", "--host", "--config", "-l", "--log-level") else 1
        if i >= len(args):
            return
        sub, rest = args[i], args[i + 1 :]
        if sub == "container" and rest:
            sub, rest = rest[0], rest[1:]
        if sub == "exec":
            j = 0
            while j < len(rest) and rest[j].startswith("-"):
                j += 2 if rest[j] in ("-e", "--env", "-u", "--user", "-w", "--workdir", "--env-file", "--detach-keys") else 1
            inner = rest[j + 1 :]  # after the container: the command, verbatim
            if inner:
                self.command(" ".join(shlex.quote(a) for a in inner), 1, isolated=True)
        if sub in ("run", "create"):
            for j, a in enumerate(rest):
                if a.startswith("--privileged"):
                    raise Blocked("docker --privileged non è permesso nel laboratorio.")
                if a in ("-v", "--volume") and j + 1 < len(rest):
                    self.volume(rest[j + 1])
                elif a.startswith("--volume="):
                    self.volume(a.split("=", 1)[1])
                elif a.startswith("-v") and len(a) > 2:
                    self.volume(a[2:])
                elif a == "--mount" and j + 1 < len(rest):
                    self.mount(rest[j + 1])
                elif a.startswith("--mount="):
                    self.mount(a.split("=", 1)[1])
        elif sub == "cp" and len(rest) >= 2 and not re.match(r"^[A-Za-z0-9_.-]+:", rest[-1]):
            self.write(rest[-1], tree=True)
        elif sub in ("build", "buildx"):
            for j, a in enumerate(rest):
                value = rest[j + 1] if a in ("-o", "--output") and j + 1 < len(rest) else (a.split("=", 1)[1] if a.startswith("--output=") else None)
                if value:
                    dest = re.search(r"dest=([^,]+)", value)
                    self.write(dest.group(1) if dest else value, tree=True)

    def volume(self, spec):
        parts = spec.split(":")
        source, options = parts[0], parts[2:] if len(parts) > 2 else []
        if "docker.sock" in spec:
            raise Blocked("Il socket di Docker non si monta nei container.")
        if not source.startswith(("/", ".", "~", "$")) or any(o in ("ro", "readonly") for opt in options for o in opt.split(",")):
            return
        self.write(source, tree=True)

    def mount(self, spec):
        fields = dict(f.split("=", 1) if "=" in f else (f, "true") for f in spec.split(","))
        source = fields.get("source") or fields.get("src") or ""
        if "docker.sock" in source:
            raise Blocked("Il socket di Docker non si monta nei container.")
        if fields.get("type", "bind") == "bind" and source and not ({"readonly", "ro"} & set(fields)):
            self.write(source, tree=True)

    # ------------------------------------------------------------------ git

    def git(self, args, stdin, piped, depth):
        where, i = self.cwd, 0
        while i < len(args):
            a = args[i]
            if a == "-C" and i + 1 < len(args):
                value = self.resolve(args[i + 1])
                if value is None or (not os.path.isabs(value) and where is None):
                    where = None
                else:
                    where = os.path.realpath(value if os.path.isabs(value) else os.path.join(where, value))
                i += 2
            elif a == "-c" and i + 1 < len(args):
                self.git_config_key(args[i + 1].split("=", 1)[0])
                i += 2
            elif a.split("=", 1)[0] in ("--git-dir", "--work-tree", "--namespace", "--exec-path", "--config-env", "--super-prefix"):
                raise Blocked(f"git {a.split('=', 1)[0]} non serve in un giro.")
            elif a.startswith("-"):
                i += 1
            else:
                break
        if i >= len(args):
            return
        sub, rest = args[i], args[i + 1 :]
        if has_expansion(sub):
            raise Blocked("Sottocomando di git in una variabile: scrivilo per esteso.")
        if sub in GIT_NEVER or sub.startswith("credential"):
            raise Blocked(f"git {sub} non serve a un giro.")
        self.git_runs(sub, rest, depth)
        if sub == "config":
            self.git_config(rest)
        if self.mode in WHO:
            self.git_read_only(sub, rest, where)
            return
        if sub in ("switch", "checkout"):
            self.follow_branch(sub, rest)
        if sub == "push":
            self.git_push(rest, where)
        elif sub == "commit":
            self.git_commit(rest, stdin)
        elif sub == "branch":
            pos, opts = parse_args(rest)
            if set(pos) & MAIN_BRANCHES and has_flag(opts, "dDmMfcC", ("--delete", "--move", "--force", "--copy")):
                raise Blocked("Non si cancella, rinomina o sposta main.")
        elif sub in ("rm", "mv", "restore", "checkout"):
            self.git_paths(sub, rest, where)
        elif sub in ("apply", "am"):
            self.git_apply(rest, stdin, piped)
        if self.mode == "costruttore" and sub in GIT_BUILDER_NEVER:
            raise Blocked(f"git {sub} non serve al costruttore: fai commit nuovi.")

    def git_runs(self, sub, rest, depth):
        """git subcommands and options that run another command: that command is checked too."""
        for i, a in enumerate(rest):
            name, eq, value = a.partition("=")
            if name in ("--upload-pack", "--receive-pack", "--exec") and sub != "rebase":
                raise Blocked(f"git {sub} {name}: non serve in un giro.")
            if sub == "rebase" and (name in ("-x", "--exec") or re.fullmatch(r"-[A-Za-z]*x", a)):
                command = value if eq else (rest[i + 1] if i + 1 < len(rest) else "")
                self.command(command, depth + 1, isolated=True)
            if sub == "difftool" and name in ("-x", "--extcmd"):
                raise Blocked("git difftool --extcmd non serve in un giro.")
        if sub == "bisect" and rest[:1] == ["run"]:
            self.command(" ".join(shlex.quote(a) for a in rest[1:]), depth + 1, isolated=True)
        if sub == "submodule" and "foreach" in rest:
            inner = rest[rest.index("foreach") + 1 :]
            inner = [a for a in inner if a not in ("--recursive", "-q", "--quiet")]
            self.command(" ".join(inner) if len(inner) == 1 else " ".join(shlex.quote(a) for a in inner), depth + 1, isolated=True)

    def follow_branch(self, sub, rest):
        """The branch a later `git push … HEAD` in this same command will push."""
        before = rest[: rest.index("--")] if "--" in rest else rest
        pos, opts = parse_args(before, {"-c", "-C", "-b", "-B", "--orphan", "--conflict"})
        created = next((v for n, v in opts if n in ("-c", "-C", "-b", "-B", "--orphan")), None)
        if created:
            self.branch = created if not has_expansion(created) else "?"
        elif any(n in ("--detach", "-d") for n, _ in opts) and sub == "switch":
            self.branch = "HEAD"
        elif "--" in rest or not pos:
            return
        elif sub == "checkout" and any(os.path.exists(p) for p in self.candidates(pos[0])):
            return  # git checkout <file>: not a branch
        elif pos[0] == "-" or has_expansion(pos[0]):
            self.branch = "?"
        else:
            self.branch = pos[0]

    def git_config_key(self, key):
        if DANGEROUS_GIT_CONFIG.match(key):
            raise Blocked(f"git -c {key}: configurazione che esegue programmi o tocca credenziali e remote, non da un giro.")

    def git_config(self, rest):
        positional = [a for a in rest if not a.startswith("-")]
        writes = {"--add", "--unset", "--unset-all", "--replace-all", "--rename-section", "--remove-section"}
        if set(rest) & {"--global", "--system"} and (len(positional) > 1 or set(rest) & writes or positional[:1] in (["set"], ["unset"])):
            raise Blocked("La configurazione globale di git non si cambia da un giro: usa git -c per un comando solo.")
        for a in rest:
            if a.split("=", 1)[0] in ("-l", "--list", "--get-regexp", "--get-urlmatch", "-e", "--edit"):
                raise Blocked("git config --list/--edit non serve in un giro (mostrerebbe anche le credenziali).")
        if positional[:1] in (["list"], ["edit"]):
            raise Blocked("git config list/edit non serve in un giro (mostrerebbe anche le credenziali).")
        for a in positional:
            if DANGEROUS_GIT_CONFIG.match(a):
                raise Blocked(f"git config {a}: configurazione che esegue programmi o tocca credenziali e remote, non da un giro.")

    def git_read_only(self, sub, rest, where):
        who = WHO[self.mode]
        inside = where is None or relative(where, None, self.root) is not None
        if sub == "branch":
            prev = ""
            for a in rest:
                if not (a.split("=", 1)[0] in BRANCH_LISTING or prev in BRANCH_VALUE):
                    raise Blocked(f"git branch {a}: il {who} può solo elencare i branch.")
                prev = a
            return
        if sub == "config":
            writes = {"--add", "--unset", "--unset-all", "--replace-all", "--rename-section", "--remove-section", "set", "unset"}
            if set(rest) & writes or len([a for a in rest if not a.startswith("-")]) > 1:
                raise Blocked(f"git config in scrittura non è permesso al {who}.")
            return
        allowed = set(GIT_READ_ONLY)
        if self.mode == "sola-lettura":
            allowed.discard("worktree")
        if self.mode == "revisore" and not inside:
            allowed |= GIT_REVIEWER_EXTRA
        if sub not in allowed:
            hint = ""
            if self.mode == "revisore" and sub in GIT_REVIEWER_EXTRA:
                hint = " nel repository principale: lavora nella copia /tmp/revisione-NNN"
            raise Blocked(f"git {sub} non è permesso al {who}{hint}.")
        if sub == "worktree" and rest[:1] == ["add"]:
            pos, opts = parse_args(rest[1:], {"-b", "-B", "--reason"})
            if any(n in ("-b", "-B") for n, _ in opts):
                raise Blocked(f"Il {who} non crea branch: usa git worktree add --detach.")
            if pos and any(relative(p, None, self.root) is not None for p in self.candidates(pos[0])):
                raise Blocked("La copia di revisione va fuori dal repository: /tmp/revisione-NNN.")

    def git_push(self, rest, where):
        positional, i = [], 0
        while i < len(rest):
            a = rest[i]
            name = a.split("=", 1)[0]
            if name in ("--all", "--mirror", "--delete", "-d", "--prune", "--repo"):
                raise Blocked("git push --all/--mirror/--delete non è permesso: si pusha solo il branch del giro.")
            if name in ("-f", "--force", "--force-with-lease", "--force-if-includes") or re.fullmatch(r"-[A-Za-z]*f[A-Za-z]*", a):
                raise Blocked(
                    "Push forzato non permesso: la storia pubblicata non si riscrive. Fai un commit nuovo, o un "
                    "merge di main nel branch."
                )
            if name in ("--receive-pack", "--exec"):
                raise Blocked("git push --receive-pack/--exec non è permesso.")
            if a in ("-o", "--push-option") and i + 1 < len(rest):
                i += 2
                continue
            if not a.startswith("-"):
                positional.append(a)
            i += 1
        if positional and positional[0] != "origin":
            raise Blocked("Si pusha solo su origin.")
        branch = self.branch or current_branch(where or self.root)
        refspecs = positional[1:]
        for spec in refspecs:
            value = self.resolve(spec) if has_expansion(spec) else spec
            if value is None:
                raise Blocked("Push con il branch in una variabile: scrivi il nome per esteso, o usa HEAD.")
            if value.startswith("+"):
                raise Blocked("Push forzato non permesso: la storia pubblicata non si riscrive.")
            src, colon, dst = value.partition(":")
            if colon and not src:
                raise Blocked("Push che cancella un branch remoto: non si fa da un giro.")
            dst = dst if colon else src
            if dst.startswith("refs/heads/"):
                dst = dst[len("refs/heads/") :]
            if dst in MAIN_BRANCHES or (dst in ("HEAD", "@") and branch in MAIN_BRANCHES):
                raise Blocked(MAIN_PUSH)
            if dst in ("HEAD", "@") and branch == "?":
                raise Blocked("Push di HEAD dopo un cambio di branch non leggibile: scrivi il nome del branch.")
        if not refspecs and branch in MAIN_BRANCHES | {"?"}:
            raise Blocked("Sei su main: push bloccato. Lavora sul branch claude/giro-NNN-….")

    def git_commit(self, rest, stdin):
        if self.mode != "costruttore":
            return
        refuse = "Il costruttore fa solo commit nuovi con -m: niente --amend, -C/-c, --fixup, --squash, template."
        messages, i = [], 0
        while i < len(rest):
            a = rest[i]
            name, eq, val = a.partition("=")
            nxt = rest[i + 1] if i + 1 < len(rest) else ""
            if a.startswith("--"):
                if name in ("--amend", "--reuse-message", "--reedit-message", "--fixup", "--squash", "--template"):
                    raise Blocked(refuse)
                if name in ("--message", "--file"):
                    value = val if eq else nxt
                    messages.append(value if name == "--message" else self.commit_file(value, stdin))
                    i += 1 if eq else 2
                    continue
                i += 1
                continue
            if a.startswith("-") and len(a) > 1:
                letters, consumed = a[1:], False
                for k, ch in enumerate(letters):
                    if ch in "cCt":
                        raise Blocked(refuse)
                    if ch in "mF":
                        value = letters[k + 1 :] or nxt
                        consumed = not letters[k + 1 :]
                        messages.append(value if ch == "m" else self.commit_file(value, stdin))
                        break
                i += 2 if consumed else 1
                continue
            i += 1
        for message in messages:
            value = self.resolve(message) if has_expansion(message) else message
            if value is None:
                raise Blocked(
                    "Messaggio di commit con una variabile o un comando: scrivilo per esteso (-m \"…\", "
                    "oppure -m \"$(cat <<'EOF' … EOF)\")."
                )
            if re.search(r"test\s*\(\s*acceptance", value, re.I):
                raise Blocked("I commit test(acceptance) li fa solo il pianificatore.")

    def commit_file(self, token, stdin):
        if token == "-":
            if not stdin:
                raise Blocked("git commit -F - senza un testo leggibile: usa -m.")
            return "\n".join(stdin)
        text = self.read_text(token)
        if text is None:
            raise Blocked(f"git commit -F {token}: file non leggibile, usa -m.")
        return text

    def git_paths(self, sub, rest, where):
        if any(a.startswith("--pathspec-from-file") for a in rest):
            raise Blocked(f"git {sub} --pathspec-from-file non si può controllare: elenca i percorsi.")
        if sub == "checkout":
            before, paths = (rest[: rest.index("--")], rest[rest.index("--") + 1 :]) if "--" in rest else (rest, None)
            pos, _ = parse_args(before, {"-b", "-B", "--orphan", "--conflict"})
            if paths is None:
                paths = pos[1:]
            elif not pos:
                paths = []  # from the index: it only undoes local changes
        elif sub == "restore":
            pos, opts = parse_args(rest, {"-s", "--source", "--conflict"})
            paths = pos if any(n in ("-s", "--source") for n, _ in opts) else []
        else:
            paths, _ = parse_args(rest)
        saved = self.cwd
        self.cwd = where
        try:
            for p in paths:
                if p.startswith(":/"):
                    p = os.path.join(self.root, p[2:])
                elif p.startswith(":"):
                    raise Blocked(f"git {sub} {p}: scrivi il percorso senza la «magia» delle pathspec.")
                self.write(p, tree=True)
        finally:
            self.cwd = saved

    def git_apply(self, rest, stdin, piped):
        pos, opts = parse_args(rest, {"-p", "--directory", "--exclude", "--include", "-C", "--whitespace", "--build-fake-ancestor"})
        names = {n for n, _ in opts}
        if names & {"--check", "--stat", "--numstat", "--summary"} and "--apply" not in names:
            return
        strip = next((int(v) for n, v in opts if n == "-p" and v.isdigit()), 1)
        texts = []
        for p in pos:
            text = self.read_text(p)
            if text is None:
                raise Blocked(f"git apply: il patch {p} non si legge; non si può controllare cosa scrive.")
            texts.append(text)
        if not pos:
            texts = list(stdin)
            if not texts and piped:
                raise Blocked("git apply da una pipe: salva il patch in un file, così si può controllare cosa scrive.")
        saved = self.cwd
        self.cwd = self.root
        try:
            for text in texts:
                for path in patch_paths(text, strip):
                    self.write(path)
        finally:
            self.cwd = saved

    # ------------------------------------------------------------------ gh

    def gh(self, args, stdin=(), piped=False):
        while args and (args[0] in ("-R", "--repo") or args[0].startswith("--repo=")):
            args = args[1:] if args[0].startswith("--repo=") else args[2:]
        if not args:
            return
        if any(has_expansion(a) for a in args[:2]):
            raise Blocked("Comando gh in una variabile: scrivilo per esteso.")
        group, action = args[0], (args[1] if len(args) > 1 else "")
        if self.mode in WHO:
            if group == "api":
                self.gh_api(args[1:], stdin, piped)
            elif (group, action) not in GH_READ_ONLY:
                raise Blocked(f"gh {group} {action} non è permesso al {WHO[self.mode]}: solo lettura.")
            return
        if group in GH_NEVER:
            raise Blocked(f"gh {group} non è permesso: segreti, regole e account li gestisce l'umano.")
        if group == "repo" and action not in ("view", "clone", ""):
            raise Blocked(f"gh repo {action} non è permesso.")
        if group == "workflow" and action in ("run", "enable", "disable"):
            raise Blocked("I workflow non si avviano né si spengono da un giro.")
        if group == "run" and action in ("cancel", "delete"):
            raise Blocked("I run della CI non si cancellano da un giro.")
        if group == "label" and action in ("delete", "edit", "clone"):
            raise Blocked("Le etichette della catena non si cambiano da un giro.")
        if group in ("issue", "pr") and action in ("delete", "transfer", "lock", "unlock", "pin", "unpin"):
            raise Blocked(f"gh {group} {action} non è permesso.")
        if group == "pr" and action == "merge":
            raise Blocked(MERGE)
        if group == "pr" and action == "review":
            raise Blocked("Niente auto-approvazioni: la revisione è del revisore e della CI.")
        if group in ("issue", "pr") and action == "edit":
            self.gh_labels(args)
        if group in ("issue", "pr") and action == "create":
            labels = set(labels_in(args, "--label", "-l"))
            if any(has_expansion(x) for x in labels):
                raise Blocked("Etichette in una variabile: scrivile per esteso.")
            if group == "pr" and labels - LABELS_AT_CREATION:
                raise Blocked("Una PR del giro nasce solo con le etichette 'giro' e 'in-corso'.")
            if group == "issue" and labels & (LABELS_NOT_ADDED | LABELS_NOT_REMOVED | LABELS_AT_CREATION | {"domanda"}):
                raise Blocked("Le etichette della catena non vanno sulle issue.")
        if group == "api":
            self.gh_api(args[1:], stdin, piped)

    def gh_labels(self, args):
        added, removed = set(labels_in(args, "--add-label")), set(labels_in(args, "--remove-label"))
        if any(has_expansion(x) for x in added | removed):
            raise Blocked("Etichette in una variabile: scrivile per esteso.")
        if added & LABELS_NOT_ADDED:
            raise Blocked(f"Le etichette {sorted(added & LABELS_NOT_ADDED)} le mette l'umano o la catena, non il giro.")
        if removed & LABELS_NOT_REMOVED:
            raise Blocked(f"Le etichette {sorted(removed & LABELS_NOT_REMOVED)} le toglie l'umano o la catena, non il giro.")

    def gh_api(self, args, stdin=(), piped=False):
        explicit, fields, inputs, endpoint, i = None, [], [], "", 0
        while i < len(args):
            a = args[i]
            name, eq, val = a.partition("=")
            nxt = args[i + 1] if i + 1 < len(args) else ""
            if a in ("-X", "--method"):
                explicit = nxt.upper()
                i += 2
                continue
            if a in ("-f", "-F", "--field", "--raw-field", "--input"):
                (inputs if a == "--input" else fields).append(nxt)
                i += 2
                continue
            if a in ("-H", "--header", "-q", "--jq", "-t", "--template", "--hostname", "--cache", "-p", "--preview"):
                i += 2
                continue
            if name == "--method" and eq:
                explicit = val.upper()
            elif a.startswith("-X") and len(a) > 2:
                explicit = a[2:].upper()
            elif name in ("--field", "--raw-field") and eq:
                fields.append(val)
            elif name == "--input" and eq:
                inputs.append(val)
            elif not a.startswith("-") and not endpoint:
                endpoint = a
            i += 1
        method = explicit or ("POST" if fields or inputs else "GET")
        if has_expansion(endpoint):
            endpoint = self.resolve(endpoint) or ""
            if not endpoint:
                raise Blocked("gh api con l'indirizzo in una variabile: scrivilo per esteso.")
        path = endpoint.split("?", 1)[0].lstrip("/")
        if re.search(r"/merges?$|/auto_merge$", path):
            raise Blocked(MERGE)
        if method in ("GET", "HEAD") and path != "graphql":
            return
        body = self.api_body(fields, inputs, stdin, piped)
        if path == "graphql":
            if re.search(r"\bmutation\b", json.dumps(body), re.I):
                raise Blocked("Mutazioni GraphQL non permesse: usa le chiamate REST previste dalla skill.")
            return
        for verb, pattern, kind in REST_WRITES:
            m = pattern.match(path)
            if m and method == verb:
                self.rest_write(kind, m, body)
                return
        raise Blocked(
            f"gh api {method} {path}: non è tra le scritture di un giro (PR, etichette, commenti, bozza/pronta: "
            "usa .claude/skills/giro/pr.py)."
        )

    def api_body(self, fields, inputs, stdin, piped):
        """The JSON body of a gh api call, from -f/-F fields and --input; unknown parts block."""
        def text_of(source):
            if source == "-":
                if not stdin:
                    raise Blocked("gh api con il corpo da una pipe: scrivilo in un file, così si può controllare.")
                return "\n".join(stdin)
            content = self.read_text(source)
            if content is None:
                raise Blocked(f"gh api: il file {source} non si legge.")
            return content

        body = {}
        for source in inputs:
            try:
                data = json.loads(text_of(source))
            except ValueError:
                raise Blocked("gh api: corpo --input non JSON.")
            if isinstance(data, dict):
                body.update(data)
            else:
                body["__non_oggetto__"] = data
        for field in fields:
            key, _, value = field.partition("=")
            if value.startswith("@"):
                value = text_of(value[1:])
            elif has_expansion(value):
                resolved = self.resolve(value)
                if resolved is None and key.rstrip("[]") in ("labels", "query", "state", "base", "name"):
                    raise Blocked(f"gh api: il campo {key} è in una variabile; scrivilo per esteso.")
                value = resolved if resolved is not None else value
            if key.endswith("[]"):
                body.setdefault(key[:-2], []).append(value)
            else:
                body[key] = value
        return body

    def rest_write(self, kind, match, body):
        if kind == "labels-add":
            labels = body.get("labels")
            if set(body) != {"labels"} or not isinstance(labels, list) or not labels or not all(isinstance(x, str) for x in labels):
                raise Blocked('gh api: le etichette si aggiungono solo come {"labels": ["nome", …]}, non vuoto.')
            labels = {x.strip().lower() for x in labels}
            if labels & LABELS_NOT_ADDED:
                raise Blocked(f"Le etichette {sorted(labels & LABELS_NOT_ADDED)} le mette l'umano o la catena, non il giro.")
        elif kind == "label-remove":
            label = urllib.parse.unquote(match.group(1)).strip().lower()
            if label in LABELS_NOT_REMOVED:
                raise Blocked(f"L'etichetta {label!r} la toglie l'umano o la catena, non il giro.")
        elif kind in PR_FIELDS:
            extra = set(body) - PR_FIELDS[kind]
            if extra:
                raise Blocked(f"gh api: campi {sorted(extra)} non previsti per una PR del giro.")
            if str(body.get("state", "open")).lower() not in ("open", "closed"):
                raise Blocked("gh api: stato della PR non valido.")


# ------------------------------------------------------------------------------- after


def guard_after(data, mode):
    """PostToolUse on Bash: files the command changed that this mode must not change — written by
    code the guard cannot read before it runs (a Python script, a Java test). The command already
    ran, so this tells Claude to put them back."""
    root = project_root(data.get("cwd"))
    if mode in WHO:
        paths = ["."]  # the reviewer and the critic change nothing in the main checkout
    else:
        paths = [p.rstrip("/") for p in RULE_PREFIXES] + sorted(RULE_FILES)
        if mode == "costruttore":
            paths += [ACCEPTANCE_DIR.rstrip("/"), ":(glob)**/*AcceptanceTest.java"]
    out = git_out(root, "status", "--porcelain=v1", "-z", "--untracked-files=all", "--", *paths)
    changed = [entry[3:] for entry in (out or "").split("\0") if len(entry) > 3 and entry[2] == " "]
    if mode in WHO:  # new notes of the orchestrator (docs/lab/…, still to commit) are not theirs
        changed = [p for p in changed if not p.startswith("docs/lab/") or git_out(root, "ls-files", "--", p)]
    if changed:
        who = WHO.get(mode, "giro")
        raise Blocked(
            f"il comando ha cambiato file che il {who} non può toccare: {', '.join(changed[:8])}. "
            "Rimettili come prima adesso (git checkout -- <file>, o rm per i file nuovi) e, se ti servivano, "
            "scrivi il perché in docs/lab/proposte-metodo.md."
        )


def stricter(a, b):
    return a if STRICTER.index(a) >= STRICTER.index(b) else b


# ------------------------------------------------------------------------------- main


def main(argv):
    if len(argv) < 2 or argv[1] not in ("bash", "files", "after"):
        print("uso: guard.py bash|files|after [--costruttore|--revisore|--sola-lettura]", file=sys.stderr)
        return 2
    mode = argv[2].lstrip("-") if len(argv) > 2 else "generale"
    if mode not in MODES:
        print(f"guard: modalità sconosciuta {mode!r}, chiamata bloccata.", file=sys.stderr)
        return 2
    try:
        data = json.load(sys.stdin)
    except ValueError:
        print("guard: input del hook non leggibile, chiamata bloccata per prudenza.", file=sys.stderr)
        return 2
    agent = str(data.get("agent_type") or "")
    if agent in AGENT_MODES:
        mode = stricter(mode, AGENT_MODES[agent])
    try:
        if argv[1] == "bash":
            cwd = data.get("cwd") or os.getcwd()
            command = (data.get("tool_input") or {}).get("command") or ""
            Analyzer(mode, cwd, project_root(cwd)).command(command)
        elif argv[1] == "after":
            guard_after(data, mode)
        else:
            guard_files(data, mode)
    except Blocked as exc:
        prefix = "ATTENZIONE dal guard del laboratorio (dopo il comando)" if argv[1] == "after" else "BLOCCATO dal guard del laboratorio"
        print(f"{prefix}: {exc}", file=sys.stderr)
        return 2
    except Exception as exc:  # fail closed, but say why
        print(f"guard: errore interno ({type(exc).__name__}: {exc}); chiamata bloccata.", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
