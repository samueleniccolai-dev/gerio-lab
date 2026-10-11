---
giro: 001
traguardo: T1
fase: costruzione
inizio: 2026-10-11T09:43:32Z
ricerca: no
accettazione: fbc416d
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
| `infra/docker-compose.yml` | `name: gerio-lab` in testa (nome del progetto fisso, niente `-p` da ricordare). Servizi `caddy`, `app`, `db` (SPEC §11.3, App. B.2) senza `imgproxy`. Solo `caddy` pubblica porte (80, 443, 443/udp) e riceve `LAB_BASE_DOMAIN` nell'`environment`. `app` usa `image: ${APP_IMAGE}` (riferimento con digest) e prende `PLATFORM_BASE_DOMAIN`, `QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://db:5432/platform`, `QUARKUS_DATASOURCE_USERNAME`, `QUARKUS_DATASOURCE_PASSWORD` dal `.env`; ha un `healthcheck` su `/q/health/ready` dall'interno del container (se l'immagine UBI non ha `curl`, con `bash` e `/dev/tcp`: [DA VERIFICARE]). `db` con healthcheck `pg_isready`, volume `pgdata`; `app` ha `depends_on: db: condition: service_healthy`. Rete con sottorete fissa `172.30.0.0/24`, dentro `trusted-proxies=172.16.0.0/12`. Ogni immagine fissata: tag esatto diverso da `latest` oppure digest `@sha256:`. |
| `infra/Caddyfile` | App. B.3 ridotto: un solo sito `{$LAB_BASE_DOMAIN}`, header di sicurezza (HSTS, nosniff, Referrer-Policy, X-Frame-Options, Permissions-Policy, `-Server`), `@internal path /internal/* /q/*` → `respond @internal 404`, `reverse_proxy app:8080 { health_uri /q/health/ready }`. Niente `on_demand_tls`, niente `/img/*`. Montato come file singolo: per questo, dopo ogni copia, `validate` + `reload` (vedi `remote.sh`). |
| `infra/env.example` | modello del `.env` del server, solo valori finti: `DB_PASSWORD=change-me`, `PLATFORM_BASE_DOMAIN=lab.example.invalid`, `LAB_BASE_DOMAIN=lab.example.invalid`, `APP_IMAGE=ghcr.io/example/platform@sha256:` + 64 cifre esadecimali complete. Serve anche a `docker compose config` nei test. |
| `infra/remote.sh` | la parte che gira **sul server**, unica per deploy e rollback (`remote.sh deploy <immagine>` / `remote.sh rollback`). Tutto sotto `flock /opt/gerio-lab/.deploy.lock`, anche il rollback. Vedi «Sequenza sul server». |
| `infra/deploy.sh <immagine>` | dal runner. `set -euo pipefail`, niente `set -x`. Controlli locali prima di tutto: argomento non `ghcr.io/…@sha256:<64 hex>` → **uscita 2**, messaggio su stderr con `invalid image reference`; argomento valido ma manca una tra `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS`, `LAB_BASE_DOMAIN`, `GHCR_USER`, `GHCR_TOKEN` → **uscita 3**, messaggio `missing variable <NOME>` (solo il nome, mai il valore). Poi: chiave SSH e `known_hosts` in file temporanei `umask 077`, cancellati con `trap`; `StrictHostKeyChecking=yes`. `scp` di `docker-compose.yml`, `Caddyfile`, `remote.sh` in `/opt/gerio-lab/incoming/`. `GHCR_TOKEN` passa **solo su stdin** di `ssh` (`printf '%s' "$GHCR_TOKEN" \| ssh … /opt/gerio-lab/incoming/remote.sh deploy "$IMAGE" "$LAB_BASE_DOMAIN" "$GHCR_USER"`): mai come argomento, mai in un file sul server. |
| `infra/rollback.sh` | dal runner. Stessi controlli delle variabili `DEPLOY_*` (uscita 3, `missing variable …`). Poi `ssh … /opt/gerio-lab/remote.sh rollback`. Se non c'è un deploy precedente (primo deploy) esce ≠ 0 con un messaggio, senza fermare niente. |
| `infra/smoke.sh` | dal runner, verso `https://$LAB_BASE_DOMAIN`, con tentativi (default 24 × 5 s, `SMOKE_ATTEMPTS`, `SMOKE_DELAY`): `/` → 200 e contiene «Negozi locali online»; header `Strict-Transport-Security`; `http://…/` → 308 verso `https://`; `/q/health/ready` → 404; `/internal/tls/allowed` → 404. Controllo fallito → **uscita 1**. Senza `LAB_BASE_DOMAIN` → **uscita 3**. |
| `docs/runbook.md` §1 | «Deploy e rollback»: quando, passi, come verifico, ultima prova; più i casi sotto. |
| `docs/verifiche.md` | i punti [DA VERIFICARE] sotto, con fonte e data. |

Codici d'uscita comuni ai tre script del runner: 0 riuscito, 1 controllo o passo fallito,
2 argomento non valido, 3 configurazione mancante. Leggono solo file in `infra/` (contratto di
`infra/README.md`) e non stampano mai variabili segrete.

### Sequenza sul server (`remote.sh`)
**deploy** (sotto `flock`):
1. Se `.env` manca **e** il volume `gerio-lab_pgdata` esiste → uscita ≠ 0 con messaggio: una password
   nuova non aprirebbe il DB esistente. Lo dice anche il runbook (si ripristina il `.env` a mano).
2. Se `.env` manca: lo crea (password DB casuale con `openssl rand`, mai stampata), in un file
   temporaneo `umask 077` rinominato con `mv` (atomico), `chmod 600`.
3. Salva lo stato in uso in `/opt/gerio-lab/previous/`: `docker-compose.yml`, `Caddyfile` e la riga
   `APP_IMAGE=` del `.env`, **prima** di riscriverla (se il deploy precedente era riuscito).
4. Scrive nel `.env` (riscrittura atomica) `APP_IMAGE`, `PLATFORM_BASE_DOMAIN` e `LAB_BASE_DOMAIN` dal
   valore del runner; sposta compose e Caddyfile da `incoming/` al loro posto.
5. `docker login ghcr.io --password-stdin` (stdin di `ssh`); `trap` con `docker logout ghcr.io` a
   ogni uscita. `docker compose pull app`.
6. `docker compose up -d --remove-orphans --wait --wait-timeout 180`: la readiness viene dal
   healthcheck di `app` e `db`, **non da Caddy**.
7. `docker compose exec -T caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile`,
   poi `caddy reload` con gli stessi argomenti. Uscita ≠ 0 se uno dei due fallisce.
8. Verifica che il container `app` giri proprio il digest richiesto (`docker inspect`).
9. Segna il deploy come riuscito (file `last-good`), base per il passo 3 del prossimo.

**rollback** (sotto lo stesso `flock`): se `previous/` manca → uscita ≠ 0 «nessun deploy precedente».
Altrimenti rimette `docker-compose.yml`, `Caddyfile` e la riga `APP_IMAGE` da `previous/`, poi
passi 6–8. Il rollback non tira immagini nuove (l'immagine precedente è già sul server).

## Fatto quando — controlli eseguibili
In JUnit (`Giro001DeployAcceptanceTest`, `@QuarkusTest`; `ProcessBuilder` per script e
`docker compose config --format json`, letto con `JsonPath` di RestAssured). Se `docker` manca, il
test **fallisce** (niente skip):
- [ ] `infra/deploy.sh`, `infra/smoke.sh`, `infra/rollback.sh`, `infra/remote.sh` esistono e sono
      eseguibili; `bash -n` → uscita 0 per ognuno; nessuno contiene `set -x`.
- [ ] `docker compose -f infra/docker-compose.yml --env-file infra/env.example config -q` → uscita 0.
- [ ] Nel compose risolto: `name` = `gerio-lab`; servizi esattamente `app`, `caddy`, `db`; solo
      `caddy` ha `ports`; ogni immagine ha un digest `@sha256:` o un tag esatto diverso da `latest`.
- [ ] `app`: `PLATFORM_BASE_DOMAIN=lab.example.invalid`,
      `QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://db:5432/platform`, un `healthcheck`,
      `depends_on.db.condition=service_healthy`, immagine = quella di `env.example`.
- [ ] `caddy`: `LAB_BASE_DOMAIN=lab.example.invalid` nell'environment. `db`: un `healthcheck`.
- [ ] Una rete con sottorete `172.30.0.0/24`.
- [ ] `infra/env.example`: `APP_IMAGE` corrisponde a `ghcr.io/…@sha256:[0-9a-f]{64}`; contiene
      `PLATFORM_BASE_DOMAIN=` e `LAB_BASE_DOMAIN=`.
- [ ] `infra/Caddyfile` contiene `/internal/*`, `/q/*`, `respond @internal 404`,
      `health_uri /q/health/ready`, `Strict-Transport-Security`, `{$LAB_BASE_DOMAIN}`.
- [ ] `infra/remote.sh` contiene `flock`, `caddy validate`, `caddy reload`, `--wait`,
      `--password-stdin`, `docker logout`, `previous`.
- [ ] `infra/deploy.sh` senza argomenti e con `ghcr.io/example/platform:latest` → uscita 2,
      stderr con `invalid image reference`, in meno di 5 s, senza variabili `DEPLOY_*`.
- [ ] `infra/deploy.sh ghcr.io/example/platform@sha256:<64 hex>` senza variabili `DEPLOY_*` → uscita 3,
      stderr con `missing variable DEPLOY_HOST`, in meno di 5 s (caso positivo: l'argomento passa).
- [ ] `infra/rollback.sh` senza variabili `DEPLOY_*` → uscita 3 con `missing variable DEPLOY_HOST`.
- [ ] `infra/smoke.sh` senza `LAB_BASE_DOMAIN` → uscita 3; con `LAB_BASE_DOMAIN=lab.example.invalid
      SMOKE_ATTEMPTS=1 SMOKE_DELAY=0` → uscita 1 in meno di 20 s.
- [ ] Nessun file in `infra/` contiene `BEGIN OPENSSH`, `BEGIN RSA`, `ghp_`, `sk_live_`, `sk_test_`.
- [ ] Guardia lato app (già verde, resta verde): `GET /` con `Host: localhost` → 200 con
      «Negozi locali online»; `GET /q/health/ready` → 200 con `UP`.

In revisione (non provabili in JUnit senza server): `GHCR_TOKEN` mai come argomento di `ssh`;
rollback che rimette compose e Caddyfile; stop su `.env` mancante con volume esistente.

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
- Approvazione manuale del deploy (da R3); zero downtime durante la ricreazione di `app`.
- Eseguire gli script verso il server: mai da un giro (CLAUDE.md regola 7).
- Da `traguardi.json → fuori_perimetro`: back-office del negozio; tipo di consegna CEMETERY; capacità
  per fascia oraria; impianto consensi e GDPR oltre la pagina privacy; SPF e DKIM completi.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|
| `src/test/java/it/gerio/platform/acceptance/Giro001DeployAcceptanceTest.java` | dati i file di `infra/` … quando li si controlla, si risolve il compose con `env.example` e si lanciano gli script senza segreti … allora valgono le righe JUnit del «Fatto quando»; dato `Host: localhost` … quando `GET /` e `GET /q/health/ready` … allora 200 landing e `UP`. |

## Porte
- Doppio senso previste: contenuto di `infra/` (script, compose, Caddyfile: si rifanno con un'altra
  PR); sottorete `172.30.0.0/24`; nome progetto `gerio-lab`; percorso `/opt/gerio-lab` e
  `previous/` (dal runbook §0); `.env` generato al primo deploy; codici d'uscita 1/2/3; versioni
  esatte di `caddy` e `postgres`.
- Senso unico: nessuna da fermare il giro. Note per la revisione umana:
  - Immagini `caddy:2` e `postgres:17`: non sono dipendenze nuove, sono già scelte nello stack fisso
    (CLAUDE.md «Docker Compose + Caddy», SPEC §4.2, App. B.2; `postgres:17` già usato da Dev
    Services). Il giro fissa solo la patch. `imgproxy` resta fuori proprio per non aggiungerne.
  - Il primo certificato Let's Encrypt pubblica il nome `gerio-lab.samueleniccolai.com` nei log di
    Certificate Transparency: irreversibile, ma il nome è già scelto e nel DNS dall'umano (runbook §0.4).
  - Nessuna dipendenza Maven, plugin o action nuova; il runner usa solo `ssh`, `scp`, `curl` di `ubuntu-latest`.

## Rischi di concorrenza
- Due deploy insieme: `consegna` ha `concurrency: consegna-deploy`; in più `remote.sh` prende un
  `flock` su `/opt/gerio-lab/.deploy.lock`, **anche per il rollback**. Si legge in revisione.
- Deploy ripetuto con lo stesso digest: stesso stato finale (`.env` non rigenerato, `up -d` non
  ricrea niente). Nel runbook; prova: secondo run manuale (riga fuori JUnit).
- `.env` mancante con volume `pgdata` esistente: stop ≠ 0, mai una password nuova. Nel runbook.
- `.env` scritto a metà: file temporaneo + `mv` atomico.
- 503 da Caddy per qualche secondo mentre `app` viene ricreato: atteso, scritto nel runbook; lo
  smoke ha i tentativi per questo.
- Smoke rosso dopo un deploy riuscito: `rollback.sh` rimette immagine, compose e Caddyfile da
  `previous/`; al primo deploy non c'è, e lo dice con uscita ≠ 0 (il run resta rosso).
- Migrazioni all'avvio con due container `app`: non succede, una sola replica e `up -d` lo ricrea.

## Budget
Produzione: ≤ 350 righe tra script e configurazione, ≤ 9 file (7 in `infra/`, runbook, verifiche).
Nessuna riga Java. Deve stare in una sessione.

## Punti [DA VERIFICARE] per il costruttore (in `docs/verifiche.md`, con fonte e data)
- Ultima patch di `caddy:2` e di `postgres:17` su Docker Hub; sintassi `{$VAR}` nel Caddyfile.
- Percorso del volume per `postgres:17` (App. B.2 avverte che cambia con la 18).
- `docker compose up --wait --wait-timeout`, riferimento `@sha256:` e `--remove-orphans`.
- `caddy validate` / `caddy reload` dentro il container con Caddyfile montato come file singolo.
- Presenza di `curl` in `ubi9/openjdk-25-runtime` (altrimenti healthcheck con `/dev/tcp`).

## Domande per il ricercatore (al massimo 3)
Nessuna: la fetta è infrastruttura, ricerca `no`.

## Critica ricevuta e cosa cambia
Accolti tutti i 5 «DA CORREGGERE», le semplificazioni e i test proposti:
1. Caddyfile file singolo → `caddy validate` + `caddy reload` dopo `up`, uscita ≠ 0 se falliscono.
2. Rollback → `previous/` con compose, Caddyfile e riga `APP_IMAGE`; readiness dal healthcheck di
   `app` con `up -d --wait` (non più da Caddy); rollback sotto lo stesso `flock`. Per questo la
   logica server sta in un solo `infra/remote.sh` condiviso.
3. `PLATFORM_BASE_DOMAIN` e `LAB_BASE_DOMAIN` scritti nel `.env` a ogni deploy dal runner; `caddy`
   riceve `LAB_BASE_DOMAIN`; test sul compose risolto.
4. `GHCR_TOKEN` solo su stdin di `ssh` verso `remote.sh` (copiato con `scp`), che lo passa a
   `docker login --password-stdin`; `docker logout` nel `trap`.
5. Immagini «tag esatto ≠ latest oppure digest»; `env.example` con digest di 64 hex.
- Semplificazioni: `previous-image` = riga `APP_IMAGE` salvata prima della riscrittura; stop se
  `.env` manca ma il volume esiste; deploy ripetuto e 503 nel runbook.
- Test: caso positivo con codici distinti (2 argomento, 3 configurazione) e messaggi asseriti;
  `caddy`/`LAB_BASE_DOMAIN`, healthcheck di `db` e `app`, `depends_on: service_healthy`; docker
  mancante = fallimento.
- Facoltativi accolti: `name: gerio-lab` nel compose (equivale a `-p` esplicito, ma non si può
  dimenticare) e controllo della sottorete.
- Scartato: niente. Costo: un file in più in `infra/` e budget a 350 righe.

## Idee per dopo
- Prova end-to-end di `remote.sh` contro un Docker locale (dind) in CI, senza server.
