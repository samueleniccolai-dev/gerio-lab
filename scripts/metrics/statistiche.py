#!/usr/bin/env python3
"""Writes docs/lab/STATISTICHE.md: the lab (method v3) against Gerio (method v2), goal by goal.

    python3 scripts/metrics/statistiche.py

Inputs: metrics/giri.csv (scripts/metrics/raccogli.py), metrics/baseline-gerio.csv,
docs/lab/traguardi.json, metrics/abbonamento.csv. Definitions: docs/ESPERIMENTO.md.
"""
import csv
import datetime
import json
import os
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
OUT = os.path.join(ROOT, "docs", "lab", "STATISTICHE.md")
GOALS = [f"T{i}" for i in range(1, 9)]


def read_csv(name):
    path = os.path.join(ROOT, "metrics", name)
    return list(csv.DictReader(open(path, encoding="utf-8"))) if os.path.exists(path) else []


def num(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def total(rows, key):
    values = [num(r.get(key)) for r in rows]
    values = [v for v in values if v is not None]
    return sum(values) if values else None


def strict_total(rows, key):
    """Like total, but None as soon as one row lacks the value."""
    values = [num(r.get(key)) for r in rows]
    return sum(values) if values and None not in values else None


def fmt(value, decimals=0):
    if value is None or value == "":
        return "—"
    if decimals == 0:
        return f"{round(value):,}".replace(",", ".")
    return f"{value:,.{decimals}f}".replace(",", "X").replace(".", ",").replace("X", ".")


def ratio(a, b):
    return None if a is None or not b else a / b


def main():
    gerio = read_csv("baseline-gerio.csv")
    lab = read_csv("giri.csv")
    usage = read_csv("abbonamento.csv")
    goals = json.load(open(os.path.join(ROOT, "docs", "lab", "traguardi.json"), encoding="utf-8"))["traguardi"]
    state = {g["id"]: g for g in goals}
    lab_t0 = min((r["inizio"] for r in lab if r.get("inizio")), default=None)

    def gerio_goal(goal):
        rows = [r for r in gerio if r["traguardo"] == goal]
        done = [num(r["ore_dal_t0"]) for r in rows if num(r["ore_dal_t0"]) is not None]
        return {
            "unita": len(rows),
            "ore": max(done) if rows and goal != "T8" else None,
            "minuti_umani": total(rows, "minuti_umani_stimati"),
            "decisioni_umano": (total(rows, "decisioni") or 0) + (total(rows, "porte_senso_unico") or 0) if rows else None,
            "rilavorazioni": total(rows, "rilavorazioni"),
            "ci_minuti": total(rows, "ci_minuti"),
            "righe_main": total(rows, "righe_main_aggiunte"),
        }

    def lab_goal(goal):
        rows = [r for r in lab if r.get("traguardo") == goal]
        passed = state.get(goal, {}).get("stato") == "passato"
        merged = [num(r["ore_dal_t0"]) for r in rows if num(r.get("ore_dal_t0")) is not None]
        # Unknown stays unknown: a missing value (no GitHub data) is not a zero.
        rework = [
            None if num(r.get("ci_rossi")) is None else num(r.get("ci_rossi")) + (num(r.get("revisore_bloccanti")) or 0)
            for r in rows
        ]
        return {
            "unita": len(rows),
            "ore": max(merged) if passed and merged else None,
            "minuti_umani": strict_total(rows, "minuti_umani"),
            "decisioni_umano": (total(rows, "decisioni_ratifica") or 0) + (total(rows, "porte_senso_unico") or 0) if rows else None,
            "rilavorazioni": sum(rework) if rows and None not in rework else None,
            "ci_minuti": total(rows, "ci_minuti"),
            "righe_main": total(rows, "righe_main_aggiunte"),
            "passato": passed,
        }

    g = {goal: gerio_goal(goal) for goal in GOALS}
    l = {goal: lab_goal(goal) for goal in GOALS}
    passed = [goal for goal in GOALS if l[goal]["passato"]]

    out = []
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    out.append("# Statistiche — laboratorio (metodo v3) contro Gerio (metodo v2)\n")
    out.append(
        f"> Generato da `scripts/metrics/statistiche.py` il {now}. Dati: `metrics/giri.csv`, "
        "`metrics/baseline-gerio.csv`. Definizioni e cautele: `docs/ESPERIMENTO.md`.\n>\n"
        f"> T0 di Gerio: 2026-10-04 14:06 UTC · T0 del laboratorio: {lab_t0 or 'non ancora (nessun giro)'} · "
        f"traguardi passati dal laboratorio: {len(passed)} su 8.\n"
    )

    out.append("## Traguardo per traguardo\n")
    out.append("Gerio ↔ laboratorio. Ore dal T0 al merge che chiude il traguardo; «decisioni all'umano» = in Gerio ogni")
    out.append("decisione registrata più le domande bloccanti, nel laboratorio ratifiche più porte a senso unico.\n")
    out.append("| Traguardo | Ore dal T0 | Cicli ↔ giri | Minuti umani | Decisioni all'umano | Rilavorazioni | Minuti di CI | Righe in src/main |")
    out.append("|---|---|---|---|---|---|---|---|")
    for goal in GOALS:
        a, b = g[goal], l[goal]
        title = state.get(goal, {}).get("titolo", goal)
        mark = " ✅" if b["passato"] else ""
        out.append(
            f"| {goal} {title}{mark} | {fmt(a['ore'], 1)} ↔ {fmt(b['ore'], 1)} | {a['unita']} ↔ {b['unita']} | "
            f"{fmt(a['minuti_umani'])} ↔ {fmt(b['minuti_umani'])} | {fmt(a['decisioni_umano'])} ↔ {fmt(b['decisioni_umano'])} | "
            f"{fmt(a['rilavorazioni'])} ↔ {fmt(b['rilavorazioni'])} | {fmt(a['ci_minuti'], 1)} ↔ {fmt(b['ci_minuti'], 1)} | "
            f"{fmt(a['righe_main'])} ↔ {fmt(b['righe_main'])} |"
        )
    extra = [r for r in gerio if r["traguardo"] in ("stack", "deriva")]
    out.append(
        f"\nGerio fuori dal piano del 4/10: {len(extra)} cicli "
        f"({', '.join(r['ciclo'] for r in extra)}), {fmt(total(extra, 'righe_main_aggiunte'))} righe in src/main, "
        f"{fmt(total(extra, 'minuti_umani_stimati'))} minuti umani stimati, {fmt(total(extra, 'decisioni'))} decisioni.\n"
    )
    unestimated = [r["ciclo"] for r in gerio if not (r.get("minuti_umani_stimati") or "").strip()]
    if unestimated:
        out.append(
            f"Gerio, cicli senza stima dei minuti umani nel rapporto: {', '.join(unestimated)}. "
            "I minuti umani dei loro traguardi sono un minimo.\n"
        )

    out.append("## Ipotesi (solo sui traguardi già passati dal laboratorio)\n")

    def same_goals(side, key):
        values = [side[goal][key] for goal in passed]
        return sum(values) if values and None not in values else None

    h1 = ratio(same_goals(l, "minuti_umani"), same_goals(g, "minuti_umani"))
    h2 = ratio(same_goals(l, "decisioni_umano"), len(passed))
    merged_rows = [r for r in lab if r.get("pr_mergiata")]  # code that never reached main is not drift
    lab_main = total(merged_rows, "righe_main_aggiunte")
    lab_drift = total([r for r in merged_rows if r.get("traguardo") not in GOALS], "righe_main_aggiunte")
    gerio_main = total(gerio, "righe_main_aggiunte")
    gerio_drift = total([r for r in gerio if r["traguardo"] == "deriva"], "righe_main_aggiunte")
    h4 = ratio(lab_drift or 0, lab_main) if lab_main else None
    h5 = (l["T7"]["ore"], g["T7"]["ore"])

    def verdict(ok):
        return "dati insufficienti" if ok is None else ("in linea" if ok else "non in linea")

    out.append("| # | Ipotesi | Laboratorio | Gerio | Esito |")
    out.append("|---|---|---|---|---|")
    out.append(
        f"| H1 | minuti umani ≤ 50% di Gerio | {fmt(same_goals(l, 'minuti_umani'))} | {fmt(same_goals(g, 'minuti_umani'))} | "
        f"{verdict(None if h1 is None else h1 <= 0.5)}{'' if h1 is None else f' ({fmt(h1 * 100)}%)'} |"
    )
    out.append(
        f"| H2 | ≤ 5 decisioni all'umano per traguardo | {fmt(h2, 1)} | "
        f"{fmt(ratio(same_goals(g, 'decisioni_umano'), len(passed)), 1)} | {verdict(None if h2 is None else h2 <= 5)} |"
    )
    defects = sum(1 for r in lab if (r.get("difetto_di") or "").strip())
    out.append(
        f"| H3 | difetti dopo il merge ≤ Gerio | {defects} giri di correzione (`difetto_di`) | non misurato in Gerio | "
        "da valutare alla fine |"
    )
    out.append(
        f"| H4 | deriva < 10% delle righe di produzione | {fmt(None if h4 is None else h4 * 100, 1)}% | "
        f"{fmt(ratio(gerio_drift, gerio_main) * 100 if gerio_main else None, 1)}% | {verdict(None if h4 is None else h4 < 0.10)} |"
    )
    out.append(
        f"| H5 | ore fino a T7 ≤ Gerio (secondaria) | {fmt(h5[0], 1)} | {fmt(h5[1], 1)} | "
        f"{verdict(None if h5[0] is None else h5[0] <= h5[1])} |"
    )

    out.append("\n## Giri del laboratorio\n")
    if not lab:
        out.append("Nessun giro ancora.\n")
    else:
        out.append("| Giro | Traguardo | Esito | PR | Minuti | Merge automatico | Bloccanti | Dubbi | Mutazioni prese/provate | Minuti umani |")
        out.append("|---|---|---|---|---|---|---|---|---|---|")
        for r in lab:
            out.append(
                f"| {r['giro']} | {r.get('traguardo') or '—'} | {r.get('esito') or '—'} | "
                f"{('#' + r['pr']) if r.get('pr') else '—'} | {r.get('minuti_giro') or '—'} | {r.get('merge_automatico') or '—'} | "
                f"{r.get('revisore_bloccanti') or '0'} | {r.get('revisore_dubbi') or '0'} | "
                f"{r.get('mutazioni_prese') or '0'}/{r.get('mutazioni_provate') or '0'} | {r.get('minuti_umani') or '0'} |"
            )
        outside = [r["giro"] for r in lab if r.get("fuori_catena") == "sì"]
        out.append(
            f"\nMerge fuori catena (PR di un giro entrate in main senza i controlli né la revisione umana): "
            f"{len(outside)}{' (' + ', '.join(outside) + ')' if outside else ''}.\n"
        )
    if usage:
        out.append("\n## Uso dell'abbonamento\n")
        out.append("| Settimana | Uso settimanale | Limiti raggiunti | Note |")
        out.append("|---|---|---|---|")
        for r in usage:
            out.append(f"| {r['settimana']} | {r['uso_settimanale_percento']}% | {r['limiti_raggiunti']} | {r.get('note', '')} |")

    out.append("\n## Cautele\n")
    out.append("- I minuti umani di Gerio sono stime del costruttore nei rapporti; quelli del laboratorio sono dichiarati (`⏱`).")
    out.append("- Il laboratorio parte sapendo come è andata Gerio: le risposte marcate `da Gerio: sì` vanno lette con sconto.")
    out.append("- Le ore di calendario dipendono dalla vita più che dal metodo.\n")

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    print(f"docs/lab/STATISTICHE.md: {len(passed)} traguardi passati, {len(lab)} giri")
    return 0


if __name__ == "__main__":
    sys.exit(main())
