"""Tests of scripts/guard/pr_guard.py: when a round's PR merges by itself (docs/METODO.md §4)."""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.normpath(os.path.join(HERE, "..")))

import pr_guard  # noqa: E402

GOALS = {
    "versione": 1,
    "traguardi": [
        {"id": "T1", "titolo": "Deploy", "fatto_quando": "x", "stato": "da-fare", "giri": [], "pr": [], "passato_il": None},
        {"id": "T2", "titolo": "E2E", "fatto_quando": "y", "stato": "da-fare", "giri": [], "pr": [], "passato_il": None},
    ],
}
ACCEPTANCE = "src/test/java/it/gerio/platform/acceptance/Giro003CatalogAcceptanceTest.java"
MIGRATIONS = "src/main/resources/db/migration/"


class PrGuardTest(unittest.TestCase):
    def setUp(self):
        self.repo = tempfile.mkdtemp(prefix="pr-guard-test-")
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "test@example.com")
        self.git("config", "user.name", "test")
        self.write(MIGRATIONS + "V1__init.sql", "create table t (id bigint);\n")
        self.write(MIGRATIONS + "V2__more.sql", "create table u (id bigint);\n")
        self.write("src/test/java/it/gerio/platform/acceptance/Giro001DeployAcceptanceTest.java", "class A {}\n")
        self.write("docs/lab/traguardi.json", json.dumps(GOALS, indent=2))
        self.write("src/main/java/App.java", "class App {}\n")
        self.write("src/main/resources/application.properties", "quarkus.http.port=8080\n")
        self.git("add", ".")
        self.git("commit", "-q", "-m", "init")
        self.git("switch", "-q", "-c", "claude/giro-003-t3")

    def tearDown(self):
        shutil.rmtree(self.repo, ignore_errors=True)

    # ------------------------------------------------------------------ helpers

    def git(self, *args):
        return subprocess.run(["git", "-C", self.repo, *args], check=True, capture_output=True, text=True).stdout

    def head(self):
        return self.git("rev-parse", "HEAD").strip()

    def write(self, path, content):
        full = os.path.join(self.repo, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as f:
            f.write(content)

    def commit(self, message, files):
        for path, content in files.items():
            if content is None:
                os.remove(os.path.join(self.repo, path))
            else:
                self.write(path, content)
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)

    def meta(self, sha=None, verdict=None, **overrides):
        verdict = verdict if verdict is not None else {"bloccanti": 0, "dubbi": 2, "sha": sha or self.head()}
        data = {
            "headRefName": "claude/giro-003-t3",
            "labels": [{"name": "giro"}],
            "isDraft": False,
            "body": "Per te…\n<!-- verdetto-revisore: " + json.dumps(verdict) + " -->",
        }
        data.update(overrides)
        return data

    def evaluate(self, meta=None):
        return pr_guard.evaluate(self.repo, "main", "HEAD", meta if meta is not None else self.meta())

    def assertAuto(self, meta=None):
        result = self.evaluate(meta)
        self.assertTrue(result["auto_merge"], result["motivi"])
        return result

    def assertHuman(self, fragment, meta=None):
        result = self.evaluate(meta)
        self.assertFalse(result["auto_merge"])
        self.assertTrue(any(fragment in r for r in result["motivi"]), result["motivi"])
        return result

    def add_acceptance(self):
        self.commit("test(acceptance): giro 003 — catalogo (rosso)", {ACCEPTANCE: "class Giro003 { /* red */ }\n"})

    # ------------------------------------------------------------------ happy path

    def test_an_ordinary_round_merges_by_itself(self):
        self.add_acceptance()
        self.commit(
            "feat: catalog",
            {
                "src/main/java/Catalog.java": "class Catalog {}\n" * 20,
                MIGRATIONS + "V3__catalog.sql": (
                    "-- products of a shop; we never drop table t here\n"
                    "create table product (id bigint, tenant_id text);\n"
                    "create index product_tenant on product (tenant_id);\n"
                    "alter table product add column name text;\n"
                    "insert into product (id, tenant_id, name) values (1, 'demo', 'Rose; rosse');\n"
                    "comment on table product is 'catalogo';\n"
                ),
                "src/main/resources/application.properties": "quarkus.http.port=8080\nquarkus.log.level=INFO\n",
            },
        )
        reviewed = self.head()
        goals = json.loads(json.dumps(GOALS))
        goals["traguardi"][0].update(stato="passato", giri=["003"], pr=[12], passato_il="2026-10-11T10:00:00Z")
        self.commit(
            "docs: giro 003",
            {"docs/lab/traguardi.json": json.dumps(goals, indent=2), "docs/lab/giri/003-rapporto.md": "# rapporto\n"},
        )
        result = self.assertAuto(self.meta(sha=reviewed))
        self.assertEqual(result["metriche"]["migrazioni_nuove"], 1)
        self.assertEqual(result["metriche"]["righe_main_aggiunte"], 27)

    # ------------------------------------------------------------------ protected paths

    def test_rules_and_infrastructure_need_a_human(self):
        for path in [
            ".github/workflows/ci.yml",
            ".github/workflows/déploy.yml",
            ".claude/settings.json",
            "scripts/guard/pr_guärd.py",
            "scripts/metrics/raccogli.py",
            "infra/deploy.sh",
            "infra/déploy.sh",
            "pom.xml",
            "CLAUDE.md",
            "docs/SPEC.md",
            "docs/METODO.md",
            "docs/lab/SETUP.md",
            "docs/lab/giri/_modello-rapporto.md",
            "src/main/docker/Dockerfile.jvm",
        ]:
            with self.subTest(path=path):
                self.setUp()
                self.commit("chore: x", {path: "x\n"})
                self.assertHuman("percorso protetto")
                self.tearDown()

    def test_stop_file_stops_the_chain(self):
        self.commit("docs: stop", {"docs/lab/STOP": "fermo\n"})
        self.assertHuman("interruttore")

    def test_special_characters_still_count_lines(self):
        self.commit("feat: big", {"src/main/java/Café.java": "int x;\n" * 1501})
        self.assertHuman("fetta troppo grande")

    # ------------------------------------------------------------------ migrations

    def test_migrations_only_grow(self):
        self.commit("fix: x", {MIGRATIONS + "V1__init.sql": "create table t (id int);\n"})
        self.assertHuman("solo aggiunte")

    def test_migration_sequence_follows_current_main(self):
        self.commit("feat: x", {MIGRATIONS + "V5__jump.sql": "create table x (id bigint);\n"})
        self.assertHuman("attesa la versione V3")

    def test_main_moved_on_meanwhile(self):
        self.commit("feat: x", {MIGRATIONS + "V3__mine.sql": "create table mine (id bigint);\n"})
        self.git("switch", "-q", "main")
        self.commit("feat: other", {MIGRATIONS + "V3__theirs.sql": "create table theirs (id bigint);\n"})
        self.git("switch", "-q", "claude/giro-003-t3")
        self.assertHuman("attesa la versione V4")

    def test_bad_migration_names(self):
        for name in ["V3_bad-name.sql", "V3__città.sql"]:
            with self.subTest(name=name):
                self.setUp()
                self.commit("feat: x", {MIGRATIONS + name: "create table x (id bigint);\n"})
                self.assertHuman("il nome deve essere")
                self.tearDown()

    def test_unsafe_statements_need_a_human(self):
        for body in [
            "alter table t drop column id;",
            "alter table t rename name to label;",
            "alter table t alter column id type varchar(2);",
            "select '--'; drop table t;",
            "drop/**/table t;",
            "drop materialized view v;",
            "update t set id = null;",
            "delete from t;",
            "truncate t;",
            "create extension pgcrypto;",
        ]:
            with self.subTest(body=body):
                self.setUp()
                self.commit("feat: x", {MIGRATIONS + "V3__change.sql": body + "\n"})
                self.assertHuman("istruzione non tra quelle sempre sicure")
                self.tearDown()

    def test_migrations_elsewhere(self):
        self.commit("feat: x", {"src/main/java/db/migration/V3__Wipe.java": "class V3__Wipe {}\n"})
        self.assertHuman("migrazione fuori da")

    def test_dangerous_configuration(self):
        for line in [
            "quarkus.flyway.clean-at-start=true",
            "quarkus.flyway.locations=db/migration,db/extra",
            "quarkus.hibernate-orm.database.generation=drop-and-create",
            "quarkus.hibernate-orm.schema-management.strategy=drop-and-create",
        ]:
            with self.subTest(line=line):
                self.setUp()
                self.commit("feat: x", {"src/main/resources/application.properties": "quarkus.http.port=8080\n" + line + "\n"})
                self.assertHuman("chiave che può cancellare")
                self.tearDown()

    # ------------------------------------------------------------------ acceptance tests

    def test_existing_acceptance_tests_are_frozen(self):
        self.commit(
            "fix: x",
            {"src/test/java/it/gerio/platform/acceptance/Giro001DeployAcceptanceTest.java": "class A { /* weaker */ }\n"},
        )
        self.assertHuman("già su main")

    def test_new_acceptance_tests_cannot_change_after_creation(self):
        self.add_acceptance()
        self.commit("feat: make it pass", {ACCEPTANCE: "class Giro003 { /* green, weakened */ }\n"})
        self.assertHuman("cambiato dopo il commit test(acceptance)")

    def test_weakening_under_an_acceptance_title_is_still_caught(self):
        self.add_acceptance()
        self.commit("test(acceptance): tidy", {ACCEPTANCE: "class Giro003 { }\n"})
        self.assertHuman("cambiato dopo il commit test(acceptance)")

    def test_new_acceptance_test_removed_or_moved(self):
        self.add_acceptance()
        self.commit("chore: drop it", {ACCEPTANCE: None})
        self.assertHuman("tolto o spostato")
        self.tearDown()
        self.setUp()
        self.add_acceptance()
        self.git("mv", ACCEPTANCE, "src/test/java/it/gerio/platform/Other.java")
        self.git("commit", "-q", "-m", "refactor: move")
        self.assertHuman("tolto o spostato")

    def test_acceptance_test_born_in_a_feature_commit(self):
        self.commit("feat: x", {ACCEPTANCE: "class Giro003 {}\n"})
        self.assertHuman("nato in un commit che non è test(acceptance)")

    def test_acceptance_tests_outside_the_folder(self):
        self.commit("test(acceptance): x", {"src/test/java/it/gerio/platform/CartAcceptanceTest.java": "class C {}\n"})
        self.commit("feat: x", {"src/test/java/it/gerio/platform/CartAcceptanceTest.java": "class C { }\n"})
        self.assertHuman("cambiato dopo il commit test(acceptance)")

    # ------------------------------------------------------------------ goals file

    def goals_commit(self, mutate):
        goals = json.loads(json.dumps(GOALS))
        mutate(goals)
        self.commit("docs: x", {"docs/lab/traguardi.json": json.dumps(goals)})

    def test_goals_only_change_in_state_fields(self):
        self.goals_commit(lambda g: g["traguardi"][1].update(fatto_quando="anything goes"))
        self.assertHuman("T2 cambiato oltre lo stato")

    def test_goals_cannot_be_removed(self):
        self.goals_commit(lambda g: g["traguardi"].pop())
        self.assertHuman("aggiunti, tolti o riordinati")

    def test_unknown_goal_state(self):
        self.goals_commit(lambda g: g["traguardi"][0].update(stato="quasi"))
        self.assertHuman("stato sconosciuto")

    def test_one_goal_per_round(self):
        self.goals_commit(lambda g: [t.update(stato="passato") for t in g["traguardi"]])
        self.assertHuman("un traguardo solo")

    def test_history_is_append_only(self):
        base = json.loads(json.dumps(GOALS))
        base["traguardi"][0]["giri"] = ["001"]
        self.git("switch", "-q", "main")
        self.commit("docs: base", {"docs/lab/traguardi.json": json.dumps(base)})
        self.git("switch", "-q", "-C", "claude/giro-003-t3")
        base["traguardi"][0]["giri"] = ["999"]
        self.commit("docs: rewrite", {"docs/lab/traguardi.json": json.dumps(base)})
        self.assertHuman("riscritto")

    # ------------------------------------------------------------------ size and secrets

    def test_slice_too_big(self):
        self.commit("feat: big", {"src/main/java/Big.java": "int x;\n" * 1501})
        self.assertHuman("fetta troppo grande")

    def test_secrets_in_the_diff(self):
        key = "sk_live_" + "a1B2c3D4e5F6g7H8i9J0k1L2"
        for content in [f'String k = "{key}";\n', f"++ {key}\n"]:
            with self.subTest(content=content[:8]):
                self.setUp()
                self.commit("feat: x", {"src/main/java/Pay.java": content})
                self.assertHuman("possibile segreto")
                self.tearDown()

    def test_short_fake_webhook_secret_in_tests_is_fine(self):
        self.commit("test: x", {"src/test/java/WebhookTest.java": 'String s = "whsec_test";\n'})
        self.assertAuto()

    # ------------------------------------------------------------------ PR metadata and verdict

    def test_pr_metadata(self):
        self.commit("feat: x", {"src/main/java/X.java": "class X {}\n"})
        cases = [
            (self.meta(headRefName="feature/x"), "le PR automatiche vengono solo da"),
            (self.meta(labels=[]), "manca l'etichetta 'giro'"),
            (self.meta(labels=[{"name": "giro"}, {"name": "domanda"}]), "etichetta 'domanda'"),
            (self.meta(isDraft=True), "in bozza"),
            (self.meta(body="niente verdetto"), "manca il verdetto"),
            (self.meta(verdict={"bloccanti": 1, "sha": self.head()}), "bloccanti: 1"),
            (self.meta(verdict={"bloccanti": False, "sha": self.head()}), "bloccanti: False"),
            (self.meta(verdict={"bloccanti": 0.0, "sha": self.head()}), "bloccanti: 0.0"),
            (self.meta(verdict={"bloccanti": 0}), "quale commit ha rivisto"),
            (self.meta(body="<!-- verdetto-revisore: {bloccanti: 0} -->"), "non è JSON valido"),
            (self.meta(sha="0123456789abcdef0123456789abcdef01234567"), "non è nella storia della PR"),
        ]
        for meta, fragment in cases:
            with self.subTest(fragment=fragment):
                self.assertHuman(fragment, meta)
        self.assertAuto()

    def test_code_after_the_review_needs_a_new_review(self):
        self.commit("feat: x", {"src/main/java/X.java": "class X {}\n"})
        reviewed = self.head()
        self.commit("fix: after review", {"src/main/java/X.java": "class X { int y; }\n"})
        self.assertHuman("codice cambiato dopo la revisione", self.meta(sha=reviewed))
        self.commit("docs: report", {"docs/lab/giri/003-rapporto.md": "ok\n"})
        self.assertHuman("codice cambiato dopo la revisione", self.meta(sha=reviewed))

    def test_ci_extension_points_need_a_human(self):
        self.commit("ci: skip tests", {"scripts/ci/prepare.sh": "echo MAVEN_ARGS=-DskipTests >> $GITHUB_ENV\n"})
        self.assertHuman("percorso protetto: scripts/ci/prepare.sh")

    def test_git_attributes_cannot_hide_lines(self):
        self.commit("chore: attributes", {"src/.gitattributes": "*.java -diff\n"})
        self.assertHuman("percorso protetto: src/.gitattributes")
        # Even if such a file were already on main, the lines are still counted and read.
        self.git("switch", "-q", "main")
        self.commit("chore: attributes", {".gitattributes": "*.java -diff\n*.properties -diff\n"})
        self.git("switch", "-q", "claude/giro-003-t3")
        self.git("merge", "-q", "--no-edit", "main")
        big = "class Big {\n" + "  int x;\n" * 1600 + "}\n"
        self.commit("feat: big", {
            "src/main/java/Big.java": big,
            "src/main/resources/application.properties": "quarkus.flyway.clean-at-start=true\n",
        })
        result = self.evaluate()
        self.assertFalse(result["auto_merge"])
        self.assertTrue(any("fetta troppo grande" in r for r in result["motivi"]), result["motivi"])
        self.assertTrue(any("schema" in r for r in result["motivi"]), result["motivi"])

    def test_symbolic_links_need_a_human(self):
        os.symlink("../../../../docs/METODO.md", os.path.join(self.repo, "src/main/java/link.txt"))
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "chore: link")
        self.assertHuman("collegamento simbolico: src/main/java/link.txt")

    def test_microprofile_config_is_checked(self):
        self.commit("feat: cfg", {"src/main/resources/META-INF/microprofile-config.properties": "quarkus.flyway.clean-at-start=true\n"})
        self.assertHuman("schema")

    def test_the_last_verdict_counts(self):
        self.commit("feat: x", {"src/main/java/X.java": "class X {}\n"})
        old = self.head()
        self.commit("fix: y", {"src/main/java/X.java": "class X { int y; }\n"})
        meta = self.meta(sha=old)
        meta["body"] += "\n<!-- verdetto-revisore: " + json.dumps({"bloccanti": 0, "sha": self.head()}) + " -->"
        self.assertAuto(meta)

    def test_a_broken_check_sends_the_pr_to_a_human(self):
        broken = json.loads(json.dumps(GOALS))
        broken["traguardi"][0]["giri"] = None
        broken["traguardi"][0]["stato"] = "in-corso"
        self.commit("docs: goals", {"docs/lab/traguardi.json": json.dumps(broken, indent=2)})
        self.assertHuman("non riuscito")

    def test_markdown_lists_the_reasons(self):
        self.commit("chore: x", {".github/workflows/x.yml": "x\n"})
        text = pr_guard.markdown(self.evaluate())
        self.assertIn("Revisione umana", text)
        self.assertIn(".github/workflows/x.yml", text)


if __name__ == "__main__":
    unittest.main()
