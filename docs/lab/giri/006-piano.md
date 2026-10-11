---
giro: 006
traguardo: T3
fase: critica        # piano → critica → costruzione → revisione → chiusura
inizio: 2026-10-11T18:43:38Z
ricerca: no
accettazione:
ripreso: no
---
# Piano del giro 006 — il negozio demo raggiungibile su `demo.<LAB_BASE_DOMAIN>`

## Fetta e traguardo
T3, parte finale. Il giro 005 ha fatto catalogo e scheda prodotto (PR #8, su `main`), ma in
produzione `https://demo.gerio-lab.samueleniccolai.com` non ha certificato (oggi: errore TLS,
`tlsv1 alert internal error`), perché il `Caddyfile` serve solo il dominio base. Questa fetta:

1. `infra/Caddyfile`: un secondo sito con nome esplicito `demo.{$LAB_BASE_DOMAIN}`, con le stesse
   protezioni del dominio base, messe in comune in uno snippet `(common)` (come SPEC App. B.3).
2. `infra/smoke.sh`: dopo i controlli di oggi sul dominio base, controlli dall'esterno sul negozio
   demo (catalogo, scheda prodotto, HSTS, http→https, percorsi di gestione chiusi).

Con il merge (in revisione umana: tocca `infra/`) e lo smoke verde nel workflow `consegna`,
T3 passa. Non serve codice Java: l'app riconosce già `demo.<base>` come slug `demo`
(`TenantRequestFilter`, `PLATFORM_BASE_DOMAIN` = `LAB_BASE_DOMAIN` in `remote.sh`), e il seme
`app.demo.seed` gira già in prod. Il DNS c'è: `demo.gerio-lab.samueleniccolai.com` e
`gerio-lab.samueleniccolai.com` puntano allo stesso IPv4 (controllato oggi con `getent hosts`).

## Dati del negozio demo (già nel seme del giro 005; i test li usano)
| Cosa | Valore |
|---|---|
| Host di prova nei test | `demo.lab.example.invalid` (dominio base `lab.example.invalid`, come `env.example`) |
| Host in produzione | `demo.gerio-lab.samueleniccolai.com` |
| Nome del negozio | `Fioreria Il Glicine` |
| Pagina catalogo | `/catalogo` → contiene `Fioreria Il Glicine` e `Mazzo di rose rosse` |
| Pagina prodotto | `/prodotti/rose-rosse` → contiene `Mazzo di rose rosse` e `12 rose` (4500 centesimi) |

Lo smoke cerca solo testi senza spazi speciali (niente prezzi con U+00A0, niente `€`).

## Fatto quando — controlli eseguibili
- [ ] `./mvnw -q -Dtest='Giro006*AcceptanceTest' test` → verde. Copre le righe seguenti marcate (J).
- [ ] (J) `caddy adapt` del `Caddyfile` (immagine `caddy:2.11.7`, già fissata nel compose, con
      `LAB_BASE_DOMAIN=lab.example.invalid`) → uscita 0; nella configurazione JSON c'è una route
      per l'host `demo.lab.example.invalid` e una per `lab.example.invalid`.
- [ ] (J) Per **ciascuno** dei due host, la configurazione adattata ha: header
      `Strict-Transport-Security`, risposta 404 per `/internal/*` e `/q/*`, `reverse_proxy` verso
      `app:8080`. Nessun host oltre questi due (niente `*.`, niente `https://` catch-all).
- [ ] (J) `smoke.sh` con un `curl` finto nel `PATH` che risponde «tutto giusto»
      (`SMOKE_ATTEMPTS=1`, `SMOKE_DELAY=0`, `LAB_BASE_DOMAIN=lab.example.invalid`) → uscita 0, e
      il registro delle chiamate del `curl` finto contiene
      `https://demo.lab.example.invalid/catalogo` e
      `https://demo.lab.example.invalid/prodotti/rose-rosse`.
- [ ] (J) Stesso `curl` finto, ma il catalogo del demo non contiene `Fioreria Il Glicine` → uscita 1.
- [ ] (J) Stesso `curl` finto, ma la scheda `rose-rosse` del demo non contiene `Mazzo di rose rosse`
      → uscita 1.
- [ ] (J) Stesso `curl` finto, ma `https://demo.lab.example.invalid/q/health/ready` risponde 200
      → uscita 1.
- [ ] (J) Stesso `curl` finto, ma la risposta HEAD del demo non ha `Strict-Transport-Security`
      → uscita 1.
- [ ] (J) Stesso `curl` finto, ma `http://demo.lab.example.invalid/` non risponde 308 → uscita 1.
- [ ] (J) I test del giro 001 su `infra/` restano verdi:
      `./mvnw -q -Dtest='Giro001*AcceptanceTest' test` → verde.
- [ ] `./mvnw -B -ntp verify` → verde (nessun test esistente rotto).
- [ ] `python3 -I scripts/guard/pr_guard.py --base origin/main --head HEAD` → revisione umana
      (tocca `infra/`), nessun altro motivo di blocco.
- [ ] Dopo il merge (umano): il run di `consegna` sul commit di merge ha il passo «Deploy and
      smoke test» verde (`gh run list --workflow consegna --branch main -L 1` → `success`).
- [ ] `curl -fsS https://demo.gerio-lab.samueleniccolai.com/catalogo | grep -c 'Fioreria Il Glicine'`
      → ≥ 1; `curl -fsS https://demo.gerio-lab.samueleniccolai.com/prodotti/rose-rosse | grep -c
      'Mazzo di rose rosse'` → ≥ 1.
- [ ] `curl -fsS https://gerio-lab.samueleniccolai.com/ | grep -c 'Negozi locali online'` → ≥ 1
      (il dominio base non si rompe).
- [ ] `traguardi.json`: T3 `stato: passato`, `passato_il` = fine del passo «Deploy and smoke test»
      del run verde (stessa regola della decisione n. 6), `giri` += `006`, `pr` += numero della PR.
      Questo aggiornamento si fa nel giro di verifica dopo il merge, solo documenti.

## Fuori perimetro
- On-demand TLS, endpoint `/internal/tls/allowed`, sito `https://` catch-all e certificati per
  altri slug o domini personalizzati (SPEC §11.4, App. B.3): servono quando c'è più di un negozio
  pubblico. Qui un solo nome esplicito.
- Certificato wildcard con validazione DNS (richiederebbe un plugin Caddy e un token DNS).
- `imgproxy` e `handle_path /img/*` di App. B.3.
- `Content-Security-Policy`.
- Opzione globale `email` di Caddy.
- Qualunque modifica a Java, template, seme, migrazioni, `docker-compose.yml`, `deploy.sh`,
  `remote.sh`, `rollback.sh`.
- Le FK di V3 senza `tenant_id` (dubbio del giro 005): giro a parte.
- Da `traguardi.json → fuori_perimetro`: back-office del negozio; tipo di consegna CEMETERY;
  capacità per fascia oraria; impianto consensi e GDPR oltre la pagina privacy; SPF e DKIM completi.
- T4 (carrello) e seguenti.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|
| `Giro006DemoHostAcceptanceTest` | dato il `Caddyfile` con `LAB_BASE_DOMAIN=lab.example.invalid`, quando `caddy adapt` in `caddy:2.11.7`, allora esistono le route per `demo.lab.example.invalid` e `lab.example.invalid`, ciascuna con HSTS, 404 su `/internal/*` e `/q/*`, proxy verso `app:8080`; nessun altro host |
| `Giro006DemoHostAcceptanceTest` | dato un `curl` finto «tutto giusto», quando gira `smoke.sh`, allora esce 0 e ha chiesto catalogo e scheda `rose-rosse` sul demo |
| `Giro006DemoHostAcceptanceTest` | dato un `curl` finto con un solo difetto sul demo (testo del catalogo, testo della scheda, `/q/health/ready` pubblico, HSTS assente, http non 308), quando gira `smoke.sh`, allora esce 1 |

Il `curl` finto è uno script scritto dal test in una cartella temporanea, in testa al `PATH`: registra
gli argomenti in un file e risponde secondo l'URL e le opzioni (`-w '%{http_code}'` → codice; `-I`
→ intestazioni; altrimenti corpo). Così il test non dipende da come `smoke.sh` organizza i
controlli, solo da quali URL chiede e da come reagisce. Docker assente è un fallimento, non uno
skip (come il giro 001).

## Porte
- Doppio senso previste:
  - secondo sito esplicito nel `Caddyfile` invece dell'on-demand TLS di App. B.3 (si toglie
    con un commit; decisione da registrare in `DECISIONI.md` e in `docs/scostamenti-4-10.md`);
  - snippet `(common)` condiviso dai due siti;
  - controlli in più nello smoke;
  - il test usa l'immagine `caddy:2.11.7`, già fissata nel compose: non è una dipendenza nuova.
- Senso unico: nessuna. Nota: il certificato Let's Encrypt per `demo.gerio-lab.samueleniccolai.com`
  finisce nei registri pubblici di Certificate Transparency; il nome è già pubblico (DNS, runbook,
  repository), quindi non rivela niente di nuovo.
- La PR tocca `infra/`: revisione umana obbligatoria (METODO §4). Non è una domanda: è il percorso
  normale.

## Rischi di concorrenza
- Nessuno stato condiviso nuovo nell'app.
- Al primo deploy Caddy chiede il certificato in modo asincrono dopo il `reload`: lo smoke riprova
  (24 × 5 s) e i nuovi controlli sul demo stanno nello stesso ciclo di tentativi, non in un ciclo a
  parte senza attese.
- Se il DNS fosse sbagliato, le validazioni fallite consumano il limite di Let's Encrypt (5 l'ora
  per nome). DNS controllato oggi; se lo smoke fallisce, `rollback.sh` rimette il `Caddyfile`
  precedente e Caddy smette di chiedere.
- `caddy validate` in `remote.sh` gira prima del `reload`: un `Caddyfile` invalido ferma il deploy.

## Budget
Righe di produzione ≤ 50 (solo `infra/Caddyfile` e `infra/smoke.sh`, più una riga nel runbook se
serve); file ≤ 4. Deve stare in una sessione.

## Domande per il ricercatore (al massimo 3)
Nessuna (ricerca: no).

## Idee per dopo
- On-demand TLS con `/internal/tls/allowed` e cache in memoria (SPEC §11.4) quando i negozi
  pubblici saranno più di uno.
- Smoke del demo anche su una pagina inesistente (`/prodotti/non-esiste` → 404 generico).

## Critica ricevuta e cosa cambia
(dopo il critico)
