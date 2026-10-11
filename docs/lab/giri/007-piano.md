---
giro: 007
traguardo: T3
fase: revisione
inizio: 2026-10-11T21:35:29Z
ricerca: no
accettazione: n/a
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
| catalogo del negozio demo con dati da fioraia | `infra/smoke.sh`: `https://demo.$LAB_BASE_DOMAIN/catalogo` contiene «Fioreria Il Glicine»; in più, `curl` qui sotto: il catalogo elenca «Mazzo di rose rosse» |
| pagina prodotto | `infra/smoke.sh`: `https://demo.$LAB_BASE_DOMAIN/prodotti/rose-rosse` contiene «Mazzo di rose rosse» |
| in produzione, dopo il merge | run `consegna` 38166479287 su 26d5e92, `success`; job `deploy`, passo «Deploy and smoke test» `success` |
| comportamento in JUnit | `Giro005*AcceptanceTest` e `Giro006DemoHostAcceptanceTest` verdi su `main` |

## Fatto quando — controlli eseguibili
- [ ] `gh run view 38166479287 --json conclusion,headSha` → `success`, `26d5e92…`
- [ ] `gh run view 38166479287 --json jobs --jq '.jobs[]|select(.name=="deploy").steps[]|select(.name=="Deploy and smoke test").conclusion'` → `success`
- [ ] `curl -fsS https://demo.$LAB_BASE_DOMAIN/catalogo | grep -c "Fioreria Il Glicine"` → ≥ 1
- [ ] `curl -fsS https://demo.$LAB_BASE_DOMAIN/catalogo | grep -c "Mazzo di rose rosse"` → ≥ 1
- [ ] `curl -fsS https://demo.$LAB_BASE_DOMAIN/prodotti/rose-rosse | grep -c "Mazzo di rose rosse"` → ≥ 1
- [ ] dopo l'apertura della PR #10: `python3 scripts/lab/traguardo.py T3 --stato passato --giro 007 --pr 10` poi `--mostra` → T3 `passato`, PR 10
- [ ] il rapporto `007-rapporto.md` riporta l'output dei tre `curl`, con data e ora UTC dell'esecuzione
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
Accolto:
1. Prova del catalogo debole: aggiunto `curl … /catalogo | grep -c "Mazzo di rose rosse"` ≥ 1
   (prova che il catalogo elenca un prodotto, non solo il nome del negozio). `infra/` resta fuori
   perimetro: lo smoke non cambia.
2. `traguardo.py --pr` va lanciato dopo l'apertura della PR: la PR del giro è la #10, quindi
   `--pr 10`.
3. (facoltativo) Accolto: il rapporto registra l'output dei `curl` con data e ora UTC. Costa una
   riga di testo e rende la prova databile.

Scartato: nulla. Nessun test di accettazione nuovo (`accettazione: n/a`): giro di soli documenti.
