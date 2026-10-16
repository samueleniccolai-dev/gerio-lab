# infra — contratto del deploy (traguardo T1)

Dopo ogni merge su `main`, il workflow `consegna` (versione su `main`) fa due cose separate:

1. **Job `immagine`**, senza segreti: `./mvnw package -DskipTests`, poi
   `docker build -f src/main/docker/Dockerfile.jvm` e push su GHCR con tag `<sha>`. È l'unico job
   che esegue il codice del giro. Salta i merge che toccano solo `docs/` e `metrics/`.
2. **Job `deploy`**, environment `produzione`: i segreti esistono solo per `main`. Esegue, se
   esistono e sono eseguibili, gli script qui sotto, presi dal commit appena mergiato.

| Script | Quando | Deve |
|---|---|---|
| `infra/deploy.sh <immagine>` | dopo il job `immagine` | portare sul server l'immagine indicata (`ghcr.io/<owner>/<repo>@sha256:…`, già costruita: **non costruisce niente**), aggiornare Docker Compose + Caddy e verificare che giri proprio quel digest. Idempotente. Uscita ≠ 0 se qualcosa va storto. |
| `infra/smoke.sh` | dopo `deploy.sh` riuscito | provare dall'esterno che `https://$LAB_BASE_DOMAIN` risponde come atteso e che gli endpoint di gestione non sono pubblici. Uscita ≠ 0 se no. |
| `infra/rollback.sh` | se deploy o smoke falliscono | rimettere sul server l'immagine dell'ultimo deploy riuscito. |

Variabili d'ambiente che `consegna` passa agli script (mai stamparle):

| Variabile | Contenuto |
|---|---|
| `DEPLOY_HOST`, `DEPLOY_USER` | IPv4 del server e utente di deploy (segreti dell'environment `produzione`) |
| `DEPLOY_SSH_KEY` | chiave privata dedicata alla CI (segreto) |
| `DEPLOY_KNOWN_HOSTS` | host key del server, fissata: niente fiducia al primo contatto (segreto) |
| `LAB_BASE_DOMAIN` | dominio base del laboratorio, es. `gerio-lab.samueleniccolai.com` (variabile) |
| `IMAGE` | lo stesso riferimento passato come argomento |
| `GHCR_USER`, `GHCR_TOKEN` | credenziali del run, solo lettura dei pacchetti, scadono a fine job: servono al server per `docker login ghcr.io` e `docker pull` |
| `STRIPE_SECRET_KEY`, `STRIPE_CONNECT_WEBHOOK_SECRET` | opzionali, segreti dell'environment `produzione`: chiave segreta (o limitata) **di test** di Stripe e segreto del webhook Connect. Viaggiano su stdin con il token GHCR (tre righe, vuota = assente) e finiscono nel `.env` del server (600). Una chiave live è rifiutata dall'app. |
| `DEMO_STRIPE_ACCOUNT_ID` | opzionale, variabile: account collegato di prova del demo (`acct_` + lettere e cifre, altrimenti uscita 2). Non è un segreto: va come quinto argomento a `remote.sh` |
| `GERIO_SMTP_USER`, `GERIO_SMTP_PASSWORD` | opzionali, segreti di `produzione` (Brevo SMTP). Su stdin dopo i tre segreti sopra (righe 4 e 5; vuota o mancante = non impostato). Mai nel comando ssh. |
| `GERIO_SMTP_HOST`, `GERIO_SMTP_PORT`, `GERIO_MAIL_FROM`, `GERIO_MAIL_ENABLED` | opzionali, variabili di `produzione`, non segrete: argomenti 6-9 di `remote.sh` (dopo l'account demo). `deploy.sh` li valida prima di tutto (uscita 2): host e from solo `A-Za-z0-9.@_+-`, porta 1-65535, enabled `true` o `false`. `remote.sh` li riconvalida e scrive nel `.env` solo i non vuoti. |

Contratto per `consegna`: `deploy.sh` legge dall'ambiente le sei variabili `GERIO_*` con questi nomi,
tutte facoltative. Compose le passa ad `app` con default `GERIO_MAIL_ENABLED=false` e
`GERIO_SMTP_PORT=587`, `GERIO_SMTP_HOST=localhost` e `GERIO_MAIL_FROM=ordini@<dominio>`; utente e password vuoti.
Una variabile vuota NON fa scattare i default dell'app (SRCFG00040): per questo il compose ha default non vuoti.

Regole:
- Tutto ciò che sta in `infra/` è un percorso protetto: le PR che lo toccano vanno sempre in
  revisione umana (METODO §4). Per questo il job `deploy` può eseguirli con i segreti.
- **Gli script leggono solo file dentro `infra/`** (compose, Caddyfile, modelli di `.env`): un file
  letto fuori da lì potrebbe arrivare su `main` con un merge automatico e girare con i segreti.
- Il job `deploy` ha solo Docker e gli strumenti standard di `ubuntu-latest`; niente Java, niente
  build. Il server ha Docker e l'utente di deploy (`docs/runbook.md` §0).
- Nessun segreto nel repository: sul server stanno in un `.env` generato al primo deploy.
- Un giro non esegue mai questi script (CLAUDE.md regola 7): il guard blocca `ssh` e simili.
