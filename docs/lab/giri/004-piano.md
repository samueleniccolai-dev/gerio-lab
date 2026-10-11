---
giro: 004
traguardo: T2
fase: chiusura
inizio: 2026-10-11T17:59:36Z
ricerca: no
accettazione:
ripreso: no
---
# Piano del giro 004 — chiusura di T2 con la prova in CI

## Fetta
Il codice di T2 è su `main` (PR #5, giro 003). Manca la prova: run `ci` verde con artefatto
`e2e-screenshots`. Solo documenti, nessuna riga di produzione.

## Prova
| Criterio di T2 | Prova |
|---|---|
| E2E con browser reale | 5 test Playwright (giro 003), run `ci` 38161053367 verde |
| artefatto `e2e-screenshots` | `gh api .../actions/runs/38161053367/artifacts` → `e2e-screenshots`, 15423 byte, non scaduto |
| dipendenza nuova approvata | DECISIONI n. 7 (risposta umana, 11/10) |
| deploy di `main` dopo il merge | run `consegna` 38161757998 su 72bc23f, success |

## Fatto quando
- [ ] `python3 scripts/lab/traguardo.py --mostra` → T2 `passato`
