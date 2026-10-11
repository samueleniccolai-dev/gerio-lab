---
giro: 001
traguardo: T1
fase: critica
inizio: 2026-10-11T09:43:32Z
ricerca: no
accettazione:
ripreso: no
---
# Piano del giro 001 — deploy automatico: compose, Caddy, deploy/smoke/rollback

## Fetta e traguardo
T1 intero: i file in `infra/` che il job `deploy` di `consegna` già cerca, così il prossimo merge su
`main` porta l'immagine sul server. Adesso perché tutti gli altri traguardi si misurano in produzione.

Stato di partenza: `consegna` su `main` è verde ma con `esito=saltato`, perché `infra/deploy.sh` non
esiste (riga «No infra/deploy.sh yet»). Il job `immagine` e il `Dockerfile.jvm` ci sono già; la
landing (`templates/platform/landing.html`, «Negozi locali online») e `/q/health/ready`
(`quarkus-smallrye-health`) anche. Il giro non tocca il codice Java.

### Cosa si costruisce (tutto in `infra/`, più il runbook)
| File | Contenuto |
|---|---|
| `infra/docker-compose.yml` | servizi `caddy`, `app`, `db` (SPEC §11.3, App. B.2) senza `imgproxy`. Solo `caddy` pubblica porte (80, 443, 443/udp). `app` usa `image: ${APP_IMAGE}` (riferimento con digest) e prende `PLATFORM_BASE_DOMAIN`, `QUARKUS_DATASOURCE_JDBC_URL`, `QUARKUS_DATASOURCE_USERNAME`, `QUARKUS_DATASOURCE_PASSWORD` dal `.env` del server. `db` con healthcheck `pg_isready`, volume `pgdata`; `app` dipende da `db: service_healthy`. Rete con sottorete fissa `172.30.0.0/24`, dentro `trusted-proxies=172.16.0.0/12` di `application.properties`. Immagini con versione esatta, niente `latest`. |
| `infra/Caddyfile` | App. B.3 ridotto: un solo sito `{$LAB_BASE_DOMAIN}`, header di sicurezza (HSTS, nosniff, Referrer-Policy, X-Frame-Options, Permissions-Policy, `-Server`), `@internal path /internal/* /q/*` → `respond 404`, `reverse_proxy app:8080 { health_uri /q/health/ready }`. Niente `on_demand_tls`, niente `/img/*`. |
| `infra/env.example` | modello del `.env` del server, solo valori finti (`DB_PASSWORD=change-me`, `PLATFORM_BASE_DOMAIN=lab.example.invalid`, `APP_IMAGE=ghcr.io/example/platform@sha256:000…`). Serve anche a `docker compose config` nei test. |
| `infra/deploy.sh <immagine>` | `set -euo pipefail`. Rifiuta argomenti che non siano `ghcr.io/…@sha256:<64 hex>` (uscita 2, senza contattare nessuno). Chiave SSH in un file temporaneo `umask 077`, cancellato con `trap`; `known_hosts` da `DEPLOY_KNOWN_HOSTS`, `StrictHostKeyChecking=yes`. Copia `docker-compose.yml` e `Caddyfile` in `/opt/gerio-lab`. Sul server, sotto `flock`: crea `.env` solo se manca (password del DB casuale con `openssl rand`, mai stampata, `chmod 600`); salva l'immagine in esecuzione in `previous-image`; `docker login ghcr.io --password-stdin`, `pull`, scrive `APP_IMAGE`, `docker compose up -d --remove-orphans`; aspetta fino a 3 min che `/q/health/ready` risponda `UP` dalla rete interna (`docker compose exec caddy wget -qO- http://app:8080/q/health/ready`); verifica che il container `app` giri proprio quel digest; `docker logout ghcr.io`. Uscita ≠ 0 a ogni passo fallito. |
| `infra/smoke.sh` | dal runner, verso `https://$LAB_BASE_DOMAIN`, con tentativi (default 24 × 5 s, `SMOKE_ATTEMPTS` e `SMOKE_DELAY` sovrascrivibili): `/` → 200 e contiene «Negozi locali online»; header `Strict-Transport-Security` presente; `http://…/` → 308 verso `https://`; `/q/health/ready` → 404; `/internal/tls/allowed` → 404. Uscita ≠ 0 se una manca. Senza `LAB_BASE_DOMAIN`: uscita 2. |
| `infra/rollback.sh` | legge `previous-image` sul server e rifà `APP_IMAGE` + `up -d` + attesa readiness. Se non c'è un'immagine precedente (primo deploy) esce ≠ 0 con un messaggio, senza fermare niente. Senza variabili SSH: uscita 2. |
| `docs/runbook.md` §1 | procedura «Deploy e rollback»: quando, passi, come verifico, ultima prova. |
| `docs/verifiche.md` | i punti [DA VERIFICARE] sotto, con fonte e data. |

Tutti e tre gli script leggono solo file in `infra/` (contratto di `infra/README.md`) e non stampano
mai variabili segrete (`set -x` vietato).

## Fatto quando — controlli eseguibili
In JUnit (accettazione, `@QuarkusTest` dove serve l'app, `ProcessBuilder` per gli script):
- [ ] `test -x infra/deploy.sh -a -x infra/smoke.sh -a -x infra/rollback.sh` → vero.
- [ ] `bash -n infra/deploy.sh infra/smoke.sh infra/rollback.sh` → uscita 0 per ognuno.
- [ ] `docker compose -f infra/docker-compose.yml --env-file infra/env.example config -q` → uscita 0.
- [ ] `docker compose … config --services` → esattamente `app`, `caddy`, `db`.
- [ ] Nel compose risolto: solo `caddy` ha `ports`; nessuna immagine con tag `latest` o senza tag;
      `app` ha `PLATFORM_BASE_DOMAIN` e `QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://db:5432/platform`.
- [ ] `infra/Caddyfile` contiene `/internal/*`, `/q/*`, `respond @internal 404`,
      `health_uri /q/health/ready`, `Strict-Transport-Security`, `{$LAB_BASE_DOMAIN}`.
- [ ] `infra/deploy.sh` senza argomenti → uscita ≠ 0; con `ghcr.io/example/platform:latest` → uscita
      ≠ 0 (non è un digest), senza tentare la connessione (nessuna variabile `DEPLOY_*` impostata,
      termina in meno di 5 s).
- [ ] `LAB_BASE_DOMAIN=lab.example.invalid SMOKE_ATTEMPTS=1 SMOKE_DELAY=0 infra/smoke.sh` → uscita ≠ 0
      in meno di 20 s; senza `LAB_BASE_DOMAIN` → uscita ≠ 0.
- [ ] `infra/rollback.sh` senza variabili `DEPLOY_*` → uscita ≠ 0.
- [ ] Nessun file in `infra/` contiene `BEGIN OPENSSH`, `BEGIN RSA`, `ghp_`, `sk_live_`, `sk_test_`.
- [ ] Guardia lato app (già verde, resta verde): `GET /` con `Host: localhost` → 200 con
      «Negozi locali online»; `GET /q/health/ready` → 200 con `"status": "UP"`.

Fuori da JUnit (dopo il merge, li controlla chi chiude il giro o l'umano):
- [ ] Run di `consegna` sul merge: job `deploy` verde con `esito=ok` (non `saltato`).
- [ ] `curl -s -o /dev/null -w '%{http_code}' https://gerio-lab.samueleniccolai.com/` → `200`, corpo con
      «Negozi locali online».
- [ ] `curl -sI http://gerio-lab.samueleniccolai.com/` → `308` con `Location: https://…`.
- [ ] `curl -s -o /dev/null -w '%{http_code}' https://gerio-lab.samueleniccolai.com/q/health/ready` → `404`.
- [ ] Un secondo run di `consegna` (stesso digest, `workflow_dispatch` con `deploy=true`) → di nuovo `esito=ok` (idempotenza).

Se i prerequisiti umani (runbook §0: server, DNS, segreti di `produzione`, `LAB_BASE_DOMAIN`) mancano,
`consegna` scrive `saltato`: T1 resta `in-corso` finché l'umano non li completa, e il rapporto lo dice.
La PR tocca `infra/`: va comunque in revisione umana (METODO §4).

## Fuori perimetro
- `imgproxy`, bucket S3, `/img/*` (arrivano con le foto, non servono alla landing).
- On-demand TLS e l'endpoint `/internal/tls/allowed` (SPEC §11.4); il sottodominio
  `demo.gerio-lab…` nel Caddyfile e i dati del negozio demo: con T3.
- Backup notturno (App. B.4, §11.6), monitoraggio e avvisi (§11.7), staging (§11.1).
- `Content-Security-Policy`; cache del resolver (scostamento 12).
- Qualsiasi modifica a `.github/workflows/consegna.yml` (protetto) o al codice Java e ai template.
- Approvazione manuale del deploy (da R3).
- Eseguire gli script verso il server: mai da un giro (CLAUDE.md regola 7).
- Da `traguardi.json → fuori_perimetro`: back-office del negozio; tipo di consegna CEMETERY; capacità
  per fascia oraria; impianto consensi e GDPR oltre la pagina privacy; SPF e DKIM completi.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|
| `src/test/java/it/gerio/platform/acceptance/Giro001DeployAcceptanceTest.java` | dati i file di `infra/` … quando li si controlla e si lanciano gli script senza segreti … allora valgono le righe JUnit del «Fatto quando»; dato `Host: localhost` … quando `GET /` e `GET /q/health/ready` … allora 200 landing e `UP`. |

## Porte
- Doppio senso previste: contenuto di `infra/` (script, compose, Caddyfile: si rifanno con un'altra
  PR); sottorete `172.30.0.0/24`; percorso `/opt/gerio-lab` (dal runbook §0); `.env` generato al primo
  deploy; formato `previous-image` per il rollback; versioni esatte di `caddy` e `postgres`.
- Senso unico: nessuna da fermare il giro. Note per la revisione umana:
  - Immagini `caddy:2` e `postgres:17`: non sono dipendenze nuove, sono già scelte nello stack fisso
    (CLAUDE.md «Docker Compose + Caddy», SPEC §4.2, App. B.2; `postgres:17` già usato da Dev
    Services). Il giro fissa solo la patch. `imgproxy` resta fuori proprio per non aggiungerne.
  - Il primo certificato Let's Encrypt pubblica il nome `gerio-lab.samueleniccolai.com` nei log di
    Certificate Transparency: irreversibile, ma il nome è già scelto e nel DNS dall'umano (runbook §0.4).
  - Nessuna dipendenza Maven, plugin o action nuova; il runner usa solo `ssh`, `scp`, `curl` di `ubuntu-latest`.

## Rischi di concorrenza
- Due deploy insieme: `consegna` ha `concurrency: consegna-deploy`; in più `deploy.sh` prende un
  `flock` su `/opt/gerio-lab/.deploy.lock` sul server. Non si prova in JUnit; si legge in revisione.
- Ripetere un deploy con lo stesso digest: deve finire con lo stesso stato (`.env` non rigenerato,
  `previous-image` uguale al digest). Prova: secondo run manuale (riga fuori JUnit).
- `.env` creato a metà: si scrive in un file temporaneo e si rinomina (`mv` atomico).
- Smoke rosso dopo un deploy riuscito: `rollback.sh` rimette `previous-image`; al primo deploy non
  c'è, e lo dice con uscita ≠ 0 (il run resta rosso, l'umano viene avvisato).
- Migrazioni all'avvio con due container `app`: non succede, un solo replica e `up -d` lo ricrea.

## Budget
Produzione: ≤ 300 righe tra script e configurazione, ≤ 8 file (6 in `infra/`, runbook, verifiche).
Nessuna riga Java. Deve stare in una sessione.

## Punti [DA VERIFICARE] per il costruttore (in `docs/verifiche.md`, con fonte e data)
- Ultima patch di `caddy:2` e di `postgres:17` su Docker Hub; sintassi `{$VAR}` nel Caddyfile.
- Percorso del volume per `postgres:17` (App. B.2 avverte che cambia con la 18).
- `docker compose up` con `image` vuoto o riferimento `@sha256:` e comportamento di `--remove-orphans`.

## Domande per il ricercatore (al massimo 3)
Nessuna: la fetta è infrastruttura, ricerca `no`.

## Critica ricevuta e cosa cambia
(dopo il critico)
