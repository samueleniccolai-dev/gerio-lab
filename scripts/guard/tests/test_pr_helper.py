"""Tests of .claude/skills/giro/pr.py, the round's REST helper, against a fake `gh` on the PATH."""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
LAB = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
HELPER = os.path.join(LAB, ".claude", "skills", "giro", "pr.py")

FAKE_GH = r'''#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
data = sys.stdin.read() if "--input" in args else ""
method = args[args.index("-X") + 1] if "-X" in args else "GET"
path = next(a for a in args if a.startswith("repos/"))
with open(os.environ["FAKE_GH_LOG"], "a") as f:
    f.write(json.dumps({"method": method, "path": path, "body": json.loads(data) if data else None}) + "\n")
answers = json.load(open(os.environ["FAKE_GH_ANSWERS"]))
for key, value in answers.items():
    m, p = key.split(" ", 1)
    if m == method and path.startswith(p):
        print(json.dumps(value))
        break
'''


class PrHelperTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="pr-helper-")
        bin_dir = os.path.join(self.dir, "bin")
        os.makedirs(bin_dir)
        gh = os.path.join(bin_dir, "gh")
        with open(gh, "w") as f:
            f.write(FAKE_GH)
        os.chmod(gh, 0o755)
        self.log = os.path.join(self.dir, "log.jsonl")
        self.answers = os.path.join(self.dir, "answers.json")
        self.answer({})
        self.env = dict(os.environ, PATH=bin_dir + os.pathsep + os.environ["PATH"], FAKE_GH_LOG=self.log,
                        FAKE_GH_ANSWERS=self.answers, LAB_AUTORE="SamueleNiccolai")

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def answer(self, mapping):
        with open(self.answers, "w") as f:
            json.dump(mapping, f)

    def run_helper(self, *args):
        res = subprocess.run([sys.executable, "-I", HELPER, *args], capture_output=True, text=True, env=self.env, timeout=30)
        calls = []
        if os.path.exists(self.log):
            with open(self.log) as f:
                calls = [json.loads(line) for line in f]
        return res.returncode, res.stdout, res.stderr, calls

    def test_labels_follow_the_rules(self):
        for args in (["--metti", "risposto"], ["--metti", "revisione-umana"], ["--togli", "bloccato"], ["--togli", "fuori-catena"]):
            with self.subTest(args=args):
                code, _, err, calls = self.run_helper("etichette", "5", *args)
                self.assertEqual(code, 1, err)
                self.assertEqual(calls, [])
        code, _, err, calls = self.run_helper("etichette", "5", "--metti", "domanda", "--togli", "in-corso", "risposto")
        self.assertEqual(code, 0, err)
        self.assertEqual(calls[0], {"method": "POST", "path": "repos/{owner}/{repo}/issues/5/labels", "body": {"labels": ["domanda"]}})
        self.assertEqual({c["path"] for c in calls[1:]}, {
            "repos/{owner}/{repo}/issues/5/labels/in-corso", "repos/{owner}/{repo}/issues/5/labels/risposto"})

    def test_opening_the_lock(self):
        self.answer({"POST repos/{owner}/{repo}/pulls": {"number": 7}})
        code, out, err, calls = self.run_helper("apri", "claude/giro-001-t1", "Giro 001 — in corso")
        self.assertEqual(code, 0, err)
        self.assertEqual(out.strip(), "7")
        self.assertEqual(calls[0]["body"]["draft"], True)
        self.assertEqual(calls[0]["body"]["head"], "claude/giro-001-t1")
        self.assertEqual(calls[1]["body"], {"labels": ["giro", "in-corso"]})

    def test_round_comments_are_marked(self):
        code, _, err, calls = self.run_helper("commenta", "5", "--testo", "Riprendo dalla fase costruzione.")
        self.assertEqual(code, 0, err)
        self.assertTrue(calls[0]["body"]["body"].startswith("🤖 "))

    def test_state_of_the_open_rounds(self):
        self.answer({
            "GET repos/{owner}/{repo}/pulls": [
                {"number": 9, "title": "Giro 004 — in corso", "draft": True, "updated_at": "2026-10-10T10:00:00Z",
                 "head": {"ref": "claude/giro-004-t3", "sha": "abc"}, "user": {"login": "SamueleNiccolai"},
                 "labels": [{"name": "giro"}, {"name": "in-corso"}]},
                {"number": 8, "title": "altro", "draft": False, "updated_at": "2026-10-10T10:00:00Z",
                 "head": {"ref": "feature/x", "sha": "def"}, "user": {"login": "someone"}, "labels": []},
            ],
            "GET repos/{owner}/{repo}/commits/abc/check-runs": {"check_runs": [{"conclusion": "failure", "started_at": "2026-10-10T10:00:00Z"}]},
            "GET repos/{owner}/{repo}/commits/abc": {"commit": {"committer": {"date": "2026-10-10T09:00:00Z"}}},
        })
        code, out, err, _ = self.run_helper("stato")
        self.assertEqual(code, 0, err)
        state = json.loads(out)
        self.assertEqual([p["numero"] for p in state], [9])
        self.assertEqual(state[0]["ci"], "failure")
        self.assertEqual(state[0]["ultimo_commit"], "2026-10-10T09:00:00Z")


if __name__ == "__main__":
    unittest.main()
