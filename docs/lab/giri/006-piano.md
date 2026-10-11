---
giro: 006
traguardo: T3
fase: costruzione        # piano → critica → costruzione → revisione → chiusura
inizio: 2026-10-11T18:43:38Z
ricerca: no
accettazione: 56485d2d61e6e8af028fdce4a0cd15068044adc4
ripreso: no
---
# Piano del giro 006 — il negozio demo raggiungibile su `demo.<LAB_BASE_DOMAIN>`

## Fetta e traguardo
T3, parte finale. Il giro 005 ha fatto catalogo e scheda prodotto (PR #8, su `main`), ma in
produzione `https://demo.gerio-lab.samueleniccolai.com` non ha certificato (oggi: errore TLS,
`tlsv1 alert internal error`), perché il `Caddyfile` serve solo il dominio base. Questa fetta:

1. `infra/Caddyfile`: lo stesso blocco serve due nomi espliciti,
   `{$LAB_BASE_DOMAIN}, demo.{$LAB_BASE_DOMAIN} { … }`, con le protezioni di oggi (niente snippet:
   un solo blocco basta e non duplica nulla).
2. `infra/smoke.sh`: catalogo e scheda `rose-rosse` sul demo; i controlli comuni (HSTS con `-I`,
   http→308, `/q/health/ready` e `/internal/tls/allowed` → 404) in un ciclo `for host` sui due
   host, dentro lo stesso `check()` ritentato.

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
- [ ] (J) `docker run --rm -i -e LAB_BASE_DOMAIN=lab.example.invalid caddy:2.11.7 caddy adapt
      --config /dev/stdin --adapter caddyfile < infra/Caddyfile` → uscita 0; ogni route di primo
      livello ha un matcher `host`; l'insieme degli host è esattamente
      {`lab.example.invalid`, `demo.lab.example.invalid`} (niente catch-all, niente `*.`).
- [ ] (J) Per **ciascuno** dei due host esiste una route il cui matcher `host` lo contiene (anche
      la stessa per entrambi) e nel cui sottoalbero ci sono: `Strict-Transport-Security`, una
      route con `path` `/internal/*` → `static_response` 404, una con `/q/*` → 404,
      `reverse_proxy` con upstream `app:8080`.
- [ ] (J) `smoke.sh` con un `curl` finto nel `PATH` che risponde «tutto giusto»
      (`SMOKE_ATTEMPTS=1`, `SMOKE_DELAY=0`, `LAB_BASE_DOMAIN=lab.example.invalid`) → uscita 0, e
      il registro delle chiamate del `curl` finto contiene
      `https://demo.lab.example.invalid/catalogo` e
      `https://demo.lab.example.invalid/prodotti/rose-rosse`.
      Nessuna riga `UNEXPECTED` nel registro.
- [ ] (J) Ogni caso seguente parte dal finto «tutto giusto», cambia **un solo** difetto, e
      verifica uscita 1 **e** che il registro contenga la richiesta difettosa:
      | Difetto | Registro contiene |
      |---|---|
      | catalogo del demo senza `Fioreria Il Glicine` | `https://demo.lab.example.invalid/catalogo` |
      | scheda del demo senza `Mazzo di rose rosse` | `https://demo.lab.example.invalid/prodotti/rose-rosse` |
      | `/q/health/ready` del demo → 200 | `https://demo.lab.example.invalid/q/health/ready` |
      | `/internal/tls/allowed` del demo → 200 | `https://demo.lab.example.invalid/internal/tls/allowed` |
      | nessuna risposta `-I` con host demo ha HSTS | `head https://demo.lab.example.invalid/…` |
      | ogni `http://demo.lab.example.invalid/*` → 200 | `http://demo.lab.example.invalid/…` |
- [ ] (J) I test del giro 001 su `infra/` restano verdi:
      `./mvnw -q -Dtest='Giro001*AcceptanceTest' test` → verde.
- [ ] `./mvnw -B -ntp verify` → verde (nessun test esistente rotto).
- [ ] `python3 -I scripts/guard/pr_guard.py --base origin/main --head HEAD` → revisione umana
      (tocca `infra/`), nessun altro motivo di blocco.
- [ ] Dopo il merge (umano): il run di `consegna` sul commit di merge ha il passo «Deploy and
      smoke test» verde (`gh run list --workflow consegna --commit <sha del merge>` →
      `success`).
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
File: `src/test/java/it/gerio/platform/acceptance/Giro006DemoHostAcceptanceTest.java` (JUnit
semplice, senza `@QuarkusTest`: non serve l'app). 9 test, commit `56485d2`.

| Test | Dato … quando … allora … |
|---|---|
| `caddyAdaptsTheCaddyfileWithExactlyTheBaseAndDemoHosts` | `caddy adapt` (stdin) → uscita 0, ogni route ha `host`, host = i due attesi |
| `eachHostHasHstsHiddenManagementPathsAndTheProxy` | per ciascun host, una route che lo contiene con HSTS, 404 su `/internal/*` e `/q/*`, proxy `app:8080` |
| `smokePassesAndAsksForTheDemoCatalogAndProduct` | finto «tutto giusto» → uscita 0, catalogo e scheda chiesti, nessun `UNEXPECTED` |
| `smokeFailsWhen…` (6 test) | un difetto per volta (tabella sopra) → uscita 1 e URL difettoso nel registro |

Il `curl` finto (bash, scritto dal test in una cartella temporanea in testa al `PATH`):
- registra una riga per chiamata, `<modo> <url>`, con modo `w` (c'è `-w`), `head` (c'è `-I`) o
  `body`;
- scompone le opzioni brevi unite (`-sI` = `-s -I`, `-fsS`); `-o`, `-m`, `-w`, `-H`, `-X`, `-D`
  prendono un argomento (anche attaccato);
- `-w` ha precedenza su `-I`: stampa solo la stringa di `-w` con `%{http_code}` sostituito;
  `-f` con codice ≥ 400 esce 22; `-o FILE` scrive lì il contenuto;
- URL conosciuti, per ciascuno dei due host: `https://H/` (200), `/q/*` e `/internal/*` (404),
  `http://H/*` (308 con `location`); solo sul demo `/catalogo` e `/prodotti/rose-rosse` (200).
  HSTS su ogni risposta `-I` in https. Qualunque altro URL: riga `UNEXPECTED` e uscita 6.

Il costruttore quindi controlla HSTS con `curl -I` (non con `-D`) e chiede solo quegli URL.
Docker assente è un fallimento, non uno skip (come il giro 001). Rossi oggi per asserzione (9/9);
verdi con un prototipo di ~15 righe (blocco a due nomi + ciclo `for host`), poi scartato.

## Porte
- Doppio senso previste:
  - secondo nome esplicito nel `Caddyfile` invece dell'on-demand TLS di App. B.3 (si toglie
    con un commit; decisione da registrare in `DECISIONI.md` e in `docs/scostamenti-4-10.md`);
  - un solo blocco a due nomi invece di uno snippet;
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
| Punto | Esito | Perché / cosa cambia |
|---|---|---|
| DA CORREGGERE 1: `curl` finto con opzioni brevi unite, `-w` prima di `-I`, `-f` → 22, URL non previsto → errore registrato | Accolto | Senza, il finto dava falsi verdi o falsi rossi secondo come `smoke.sh` scrive le opzioni. Descritto sopra. |
| DA CORREGGERE 2: difetti HSTS e 308 legati all'host demo | Accolto | HSTS: nessuna risposta `-I` del demo ha l'intestazione; 308: ogni `http://demo…/*` → 200. Non dipende da quale percorso lo smoke usa. |
| PIÙ SEMPLICE: un blocco a due nomi o snippet; test `adapt` per matcher che contiene l'host | Accolto: blocco a due nomi | Caddy unisce i due nomi in una route con `host: [a, b]`; il test accetta sia una route comune sia due. |
| PIÙ SEMPLICE: `for host` nello smoke | Accolto | Controlli comuni una volta sola per i due host. |
| TEST: ogni «→ 1» parte dal tutto-giusto, un difetto, URL nel registro | Accolto | Tabella nel «Fatto quando». |
| TEST: `gh run list --commit <sha>` | Accolto | Il run giusto, non l'ultimo di `main`. |
| TEST: caso extra `/internal/tls/allowed` del demo 200 → 1 | Accolto | Settimo test. |
| Facoltativo 1: `Caddyfile` a `caddy adapt` via stdin | Accolto | Niente mount: funziona anche con Docker remoto in CI. |
| Nota | — | In locale Docker Hub ha risposto 429 al primo pull di `caddy:2.11.7`, poi è passato. Se succede in CI il test fallisce (non salta): si ritenta il job. |
