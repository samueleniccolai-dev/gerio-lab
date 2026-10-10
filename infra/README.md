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

Regole:
- Tutto ciò che sta in `infra/` è un percorso protetto: le PR che lo toccano vanno sempre in
  revisione umana (METODO §4). Per questo il job `deploy` può eseguirli con i segreti.
- **Gli script leggono solo file dentro `infra/`** (compose, Caddyfile, modelli di `.env`): un file
  letto fuori da lì potrebbe arrivare su `main` con un merge automatico e girare con i segreti.
- Il job `deploy` ha solo Docker e gli strumenti standard di `ubuntu-latest`; niente Java, niente
  build. Il server ha Docker e l'utente di deploy (`docs/runbook.md` §0).
- Nessun segreto nel repository: sul server stanno in un `.env` generato al primo deploy.
- Un giro non esegue mai questi script (CLAUDE.md regola 7): il guard blocca `ssh` e simili.
