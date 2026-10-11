---
giro: 002
traguardo: T1
fase: critica
inizio: 2026-10-11T15:25:50Z
ricerca: no
accettazione:
ripreso: no
---
# Piano del giro 002 — chiusura di T1 con la prova del primo deploy reale

## Fetta e traguardo
T1 intero, parte finale: il codice c'è già (giro 001, PR #2); manca registrare la prova del primo
deploy reale e segnare T1 `passato`. Nessuna riga di produzione.

**Prova già raccolta (run `consegna` 38151028698 su `main`, SHA `de8495e`, 2026-10-11 ~15:24Z):**

| Criterio di T1 | Prova |
|---|---|
| merge su `main` fa partire `consegna` | run 38151028698, trigger `push` del merge di PR #2 |
| build e pubblicazione su GHCR | job `immagine` verde; immagine `ghcr.io/samueleniccolai-dev/gerio-lab@sha256:1e6c2a75…5bd28` |
| il server scarica e riavvia (Compose + Caddy con TLS) | job `deploy` verde (1m21s), `infra/deploy.sh` → `remote.sh deploy` |
| `https://gerio-lab.samueleniccolai.com` 200 con la landing | `smoke.sh` controlla 200 + testo «Negozi locali online» + HSTS; da qui `curl` → 200 |
| smoke post-deploy verde | log: `smoke ok (attempt 5)` → 4 tentativi a vuoto, ~20 s di attesa (coerente con DECISIONI n. 5) |
| deploy in `infra/deploy.sh` secondo `infra/README.md` | sì, dal giro 001 |

Conclusione: i criteri di T1 sono soddisfatti. Resta solo la parte documentale.

## Fatto quando — controlli eseguibili
- [ ] `gh run view 38151028698 --repo samueleniccolai-dev/gerio-lab` → tutti i job ✓ tranne `decidi` (saltato)
- [ ] `gh run view --job=114504528632 --repo samueleniccolai-dev/gerio-lab --log | grep 'smoke ok'` → `smoke ok (attempt 5)`
- [ ] `curl -s -o /dev/null -w '%{http_code}' https://gerio-lab.samueleniccolai.com/` → `200`
- [ ] `curl -s https://gerio-lab.samueleniccolai.com/ | grep -c 'Negozi locali online'` → ≥ 1
- [ ] `curl -sI http://gerio-lab.samueleniccolai.com/ | head -1` → `308`
- [ ] `curl -s -o /dev/null -w '%{http_code}' https://gerio-lab.samueleniccolai.com/q/health/ready` → `404`
- [ ] `docs/runbook.md` §1: «**Ultima prova:**» con data 2026-10-11, run 38151028698, esito ok, tentativo 5 dello smoke
- [ ] `docs/runbook.md` §0: «**Ultima prova:**» con data 2026-10-11 (il setup del server ha funzionato, prova indiretta dal deploy)
- [ ] `docs/verifiche.md`: nuova riga del 2026-10-11 che aggiorna la riga «non verificato» di `up --wait`, `@sha256:`, `--remove-orphans`, `caddy reload` → `confermato (prova)`, fonte run 38151028698 (la riga vecchia resta: il registro si aggiunge, non si riscrive)
- [ ] `python3 -c "import json;t=json.load(open('docs/lab/traguardi.json'))['traguardi'][0];print(t['stato'],t['passato_il'],t['giri'])"` → `passato 2026-10-11T15:2…Z ['001','002']` (`passato_il` = fine del job `deploy`, preso dal run; cambiano solo `stato`, `giri`, `pr`, `passato_il`)
- [ ] `./mvnw -B -ntp verify` → verde (nessun codice toccato, controllo di non regressione)
- [ ] `python3 -I scripts/guard/pr_guard.py --base origin/main --head HEAD` → ok

Test JUnit di accettazione: nessuno. Ogni riga si prova con un comando contro il server reale o
contro i documenti; il comportamento di `infra/` è già coperto dai 19 test del giro 001.

## Fuori perimetro
- Qualunque modifica a `infra/` e al codice Java.
- I 4 DUBBI del revisore 001 (`mkdir`/`scp` fuori dal `flock`, `flock` senza test, 503 fino a ~30 s, rollback che non ricrea `last-good`): in «Idee per dopo».
- Prova del rollback sul server reale (richiederebbe un deploy rotto apposta).
- Avviso `ubuntu-latest` → Ubuntu 26 dal 19/10: riguarda `.github/` (protetto); segnalarlo all'umano nel rapporto.
- T2 (verifica visiva, dipendenza nuova) e oltre.
- Da `traguardi.json → fuori_perimetro`: back-office del negozio; consegna CEMETERY; capacità per fascia oraria; consensi/GDPR oltre la pagina privacy; SPF e DKIM completi.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|
| — | nessun test JUnit: vedi «Fatto quando» |

## Porte
- Doppio senso previste: testi di `docs/runbook.md`, riga nuova in `docs/verifiche.md`, campi di stato di T1 in `docs/lab/traguardi.json`.
- Senso unico: nessuna.

## Rischi di concorrenza
Nessuno: solo documenti. `traguardi.json` può essere controllato da `consegna`: si cambiano solo i
quattro campi ammessi.

## Budget
Righe di produzione 0; file ≤ 4 (`runbook.md`, `verifiche.md`, `traguardi.json`, poi PROGRESS e rapporto in chiusura).

## Domande per il ricercatore (al massimo 3)
Nessuna (ricerca: no).

## Idee per dopo
- Spostare `mkdir`/`scp` di `incoming/` sotto il `flock` e coprire il `flock` con un test (DUBBI 1–2 del giro 001).
- Ridurre il 503 dopo il deploy (`health_interval` di Caddy più corto) se lo smoke supera spesso il tentativo 5.
- Rollback che ricrea `last-good` (DUBBIO 4).

## Critica ricevuta e cosa cambia
(dopo il critico)
