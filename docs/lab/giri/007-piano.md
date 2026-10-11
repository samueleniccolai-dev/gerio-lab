---
giro: 007
traguardo: T3
fase: critica
inizio: 2026-10-11T21:35:29Z
ricerca: no
accettazione:
ripreso: no
---
# Piano del giro 007 — prova del demo in produzione (T3)

## Fetta e traguardo
Chiusura di T3. Il codice è su `main` (PR #8, giro 005; PR #9, giro 006) e il deploy di 26d5e92 è
verde. Manca solo la prova registrata. È un giro di soli documenti: nessuna riga di produzione e
nessun test nuovo.

## Prova
| Criterio di T3 | Prova |
|---|---|
| catalogo del negozio demo con dati da fioraia | `infra/smoke.sh`: `https://demo.$LAB_BASE_DOMAIN/catalogo` contiene «Fioreria Il Glicine» |
| pagina prodotto | `infra/smoke.sh`: `https://demo.$LAB_BASE_DOMAIN/prodotti/rose-rosse` contiene «Mazzo di rose rosse» |
| in produzione, dopo il merge | run `consegna` 38166479287 su 26d5e92, `success`; job `deploy`, passo «Deploy and smoke test» `success` |
| comportamento in JUnit | `Giro005*AcceptanceTest` e `Giro006DemoHostAcceptanceTest` verdi su `main` |

## Fatto quando — controlli eseguibili
- [ ] `gh run view 38166479287 --json conclusion,headSha` → `success`, `26d5e92…`
- [ ] `gh run view 38166479287 --json jobs --jq '.jobs[]|select(.name=="deploy").steps[]|select(.name=="Deploy and smoke test").conclusion'` → `success`
- [ ] `curl -fsS https://demo.$LAB_BASE_DOMAIN/catalogo | grep -c "Fioreria Il Glicine"` → ≥ 1
- [ ] `curl -fsS https://demo.$LAB_BASE_DOMAIN/prodotti/rose-rosse | grep -c "Mazzo di rose rosse"` → ≥ 1
- [ ] `python3 scripts/lab/traguardo.py T3 --stato passato --giro 007 --pr <n>` poi `--mostra` → T3 `passato`
- [ ] `docs/lab/PROGRESS.md` aggiornato: prossimo traguardo T4

## Fuori perimetro
- Qualsiasi codice, template, migrazione, test o file di `infra/`.
- Le FK di V3 senza `tenant_id` (dubbio in PROGRESS): resta per un giro di codice, prima del
  back-office o di T4 se lo tocca.
- Il passaggio di `ubuntu-latest` a Ubuntu 26: `.github/` è protetto, decide l'umano.
- T4 (carrello) e seguenti.
- Da `traguardi.json → fuori_perimetro`: back-office del negozio; consegna CEMETERY; capacità per
  fascia oraria; consensi e GDPR oltre la pagina privacy; SPF e DKIM completi.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
Nessuno: giro di soli documenti. La prova in JUnit esiste già (giri 005 e 006); la prova in
produzione è lo smoke del workflow `consegna` e i comandi qui sopra.

## Porte
- Doppio senso previste: nessuna (solo documenti e lo stato di T3).
- Senso unico: nessuna.

## Rischi di concorrenza
Nessuno: nessun codice cambia.

## Budget
Righe di produzione = 0; file ≤ 4 (piano, rapporto, `traguardi.json`, `PROGRESS.md`).

## Domande per il ricercatore (al massimo 3)
Nessuna (`ricerca: no`).

## Critica ricevuta e cosa cambia
(dopo il critico)
