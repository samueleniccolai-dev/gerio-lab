# Runbook — procedure operative del laboratorio

Procedure da seguire quando qualcosa va fatto o va storto in produzione (SPEC §11.8).
Ogni procedura: **quando**, **passi numerati**, **come verifico che ha funzionato**, **ultima prova**.
Nessun segreto in questo file: solo dove si trovano (gestore di password, variabili sul server).

## 0. Setup iniziale del server — una volta, a mano (prerequisito di T1)

**Quando:** server nuovo o ricostruito. Un VPS piccolo in UE (2 vCPU / 4 GB), Ubuntu 24.04 LTS.
Dominio del laboratorio: base `gerio-lab.samueleniccolai.com`, negozio demo
`demo.gerio-lab.samueleniccolai.com` (variabile `LAB_BASE_DOMAIN`).

### 0.1 Due chiavi SSH, dal tuo computer
```bash
ssh-keygen -t ed25519 -f ~/.ssh/lab_admin  -C "admin"       # per te
ssh-keygen -t ed25519 -f ~/.ssh/lab_deploy -C "deploy-ci"   # SOLO per la CI
```
Due chiavi perché hanno vite diverse: quella admin resta tua; quella deploy vive nel segreto
GitHub e, se compromessa, si ruota senza toccare il tuo accesso.

### 0.2 Console del provider
1. Crea il server con `lab_admin.pub`. Annota l'**IPv4**.
2. Firewall: inbound TCP **22, 80, 443**, outbound tutto.

### 0.3 Sul server, come root (una volta)
```bash
apt-get update && apt-get -y upgrade && apt-get -y install unattended-upgrades
curl -fsSL https://get.docker.com | sh
adduser --disabled-password --gecos "" deploy
usermod -aG docker deploy          # gruppo docker ≈ root: accettato, server dedicato al laboratorio
install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
install -d -o deploy -g deploy /opt/gerio-lab
# incolla il contenuto di lab_deploy.pub (il .pub, non la privata):
nano /home/deploy/.ssh/authorized_keys
chown deploy:deploy /home/deploy/.ssh/authorized_keys && chmod 600 /home/deploy/.ssh/authorized_keys
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
systemctl restart ssh
```

### 0.4 DNS (dove è ospitato il DNS di samueleniccolai.com)
| Record | Nome | Valore |
|---|---|---|
| A | `gerio-lab` | IPv4 del server |
| A | `demo.gerio-lab` | IPv4 del server |

### 0.5 Segreti e variabili in GitHub
I segreti del deploy stanno nell'**environment `produzione`** (Settings → Environments →
`produzione` → Environment secrets), che vale solo per `main`: un branch non li può leggere.
**Mai** come segreti del repository, mai in repo o in chat.
| Nome | Dove | Valore |
|---|---|---|
| `DEPLOY_HOST` | segreto di `produzione` | l'IPv4 del server |
| `DEPLOY_USER` | segreto di `produzione` | `deploy` |
| `DEPLOY_SSH_KEY` | segreto di `produzione` | il contenuto **intero** di `~/.ssh/lab_deploy`, da `-----BEGIN` a `-----END` |
| `DEPLOY_KNOWN_HOSTS` | segreto di `produzione` | la riga `known_hosts` del server, verificata (niente fiducia al primo contatto) |
| `LAB_BASE_DOMAIN` | variabile del repository | `gerio-lab.samueleniccolai.com` |
| `GERIO_SMTP_USER` | segreto di `produzione` | login SMTP di Brevo (§5), opzionale |
| `GERIO_SMTP_PASSWORD` | segreto di `produzione` | chiave SMTP di Brevo (§5), opzionale |
| `GERIO_SMTP_HOST`, `GERIO_SMTP_PORT` | variabili di `produzione` | `smtp-relay.brevo.com`, `587` (§5) |
| `GERIO_MAIL_FROM`, `GERIO_MAIL_ENABLED` | variabili di `produzione` | mittente verificato in Brevo; `true` per accendere l'invio (§5) |

### 0.6 Come verifico che ha funzionato
```bash
ssh -i ~/.ssh/lab_deploy deploy@IPV4 docker ps   # risponde con la tabella vuota di Docker
```
**Ultima prova:** 2026-10-11, prova indiretta (il deploy del run 38151028698 è riuscito, quindi il setup del server funziona).

## 1. Deploy e rollback

**Quando:** il deploy parte da solo dopo ogni merge su `main` (workflow `consegna`, job `deploy`).
A mano: `consegna` con `workflow_dispatch` e `deploy=true`. Il rollback parte da solo se deploy o
smoke falliscono. Contratto degli script: `infra/README.md`.

**Passi (li fa `consegna`, mai a mano dai giri):**
1. `infra/deploy.sh <immagine@sha256:…>` copia compose, Caddyfile e `remote.sh` in
   `/opt/gerio-lab/incoming/` e lancia `remote.sh deploy` sul server. Il token GHCR passa solo su stdin.
2. Sul server, sotto `flock` (`/opt/gerio-lab/.deploy.lock`): salva lo stato in uso in `previous/`,
   aggiorna `.env`, `docker compose pull app`, `up -d --wait`, `caddy validate` + `caddy reload`,
   verifica il digest in esecuzione.
3. `infra/smoke.sh` prova `https://$LAB_BASE_DOMAIN` (24 tentativi da 5 s).
4. Se qualcosa è rosso: `infra/rollback.sh` rimette compose, Caddyfile e immagine da `previous/`.

**Come verifico:**
```bash
curl -s -o /dev/null -w '%{http_code}\n' https://gerio-lab.samueleniccolai.com/        # 200
curl -sI http://gerio-lab.samueleniccolai.com/ | head -1                                # 308
curl -s -o /dev/null -w '%{http_code}\n' https://gerio-lab.samueleniccolai.com/q/health/ready  # 404
```

**Casi particolari:**
- **Deploy ripetuto con lo stesso digest:** idempotente, `.env` non rigenerato, `up -d` non ricrea niente.
- **503 per qualche secondo** mentre `app` viene ricreato: atteso; lo smoke riprova.
- **Primo deploy fallito:** non c'è `previous/`, `rollback.sh` esce con errore e non ferma niente.
- **`.env` mancante ma volume `gerio-lab_pgdata` esistente:** `remote.sh` si ferma. Una password nuova
  non aprirebbe il database. Ripristina a mano `/opt/gerio-lab/.env` (chmod 600) dal gestore di password.
- **Codici d'uscita degli script:** 0 ok, 1 passo fallito, 2 argomento non valido, 3 configurazione mancante.

**Ultima prova:** 2026-10-11, run `consegna` 38151028698 (merge della PR #2, SHA `de8495e`): esito ok; smoke riuscito al tentativo 5 (4 tentativi a vuoto, ~20 s di 503).

## 2. Ripristino di database e foto
*(da scrivere)* — Ultima prova: —

## 3. Cambio dei segreti
*(da scrivere)*

## 4. Webhook Stripe che non arrivano o falliscono
Passi dell'umano (giro 012, per la prova del giro 013):
1. Stripe in modalità test, Connect attivo; un account collegato di prova per «Fioreria Il Glicine».
2. Endpoint webhook Connect sul dominio della piattaforma, `https://<LAB_BASE_DOMAIN>/webhooks/stripe/connect`
   (sul dominio del demo risponde 404), eventi `checkout.session.completed` e `checkout.session.expired`.
3. Environment `produzione` di GitHub: segreti `STRIPE_SECRET_KEY` (di test), `STRIPE_CONNECT_WEBHOOK_SECRET`;
   variabile `DEMO_STRIPE_ACCOUNT_ID`.
4. In `.github/workflows/consegna.yml` passare le tre variabili al passo di deploy.

Prova con la carta di test (a mano, mai nello smoke: crea un ordine vero `PENDING_PAYMENT`):
1. `infra/prova-stripe.sh demo.<LAB_BASE_DOMAIN>` porta un carrello demo (104,80 €, ritiro in negozio) fino a
   Stripe Checkout e stampa solo l'URL. Exit 1 con «checkout spento» = chiavi Stripe o account collegato mancanti.
   Dopo le 12:00 (ora di Roma) il ritiro per oggi è chiuso (orario limite del demo): lo script deve usare una data da domani, o va lanciato prima delle 12:00.
2. L'URL va in un commento della PR: **è pubblico** e vale 24 ore, poi la sessione scade. Dati solo inventati.
3. Paga con la carta `4242 4242 4242 4242`, scadenza futura e CVC qualsiasi.
4. `infra/prova-stripe.sh esito demo.<LAB_BASE_DOMAIN> <url>` chiede l'esito fino a 15 volte, ogni 2 s, e stampa il
   numero d'ordine. Exit 1: «scaduta» (sessione scaduta o non completata), «sconosciuta» (404, dominio o sessione
   sbagliati), «in attesa» (il webhook non è arrivato: vedi sotto).
5. Dashboard Stripe (test): l'evento `checkout.session.completed` deve avere risposta 200.

Se il webhook non arriva (dashboard Stripe → Webhook → Consegne):
| Risposta | Significato |
|---|---|
| 400 | segreto di firma sbagliato (`STRIPE_CONNECT_WEBHOOK_SECRET` non è quello dell'endpoint) |
| 503 | segreto assente nell'app (variabile non passata al deploy) |
| 404 | l'endpoint punta al dominio del demo: deve puntare a `<LAB_BASE_DOMAIN>` |

Con il segreto configurato `infra/smoke.sh` pretende 400 dal webhook: 503 lo fa fallire con un messaggio «webhook».
Rinvio di un evento (dashboard → Rinvia): deve dare 200 senza cambiare l'ordine (idempotenza).

## 5. Email d'ordine (Brevo)
Le email di conferma partono dall'outbox (`outbox_message`, canale `EMAIL`) via SMTP. In produzione
l'invio è **spento** finché `GERIO_MAIL_ENABLED` non è `true`.

**Impostazione (una volta, dell'umano):**
1. Account Brevo, mittente (o dominio) verificato; SPF e DKIM del dominio mittente come indicato da Brevo.
2. Brevo → SMTP e API → chiave SMTP. Login e chiave sono due valori diversi.
3. GitHub → environment `produzione`: segreti `GERIO_SMTP_USER`, `GERIO_SMTP_PASSWORD`; variabili
   `GERIO_SMTP_HOST` (`smtp-relay.brevo.com`), `GERIO_SMTP_PORT` (`587`), `GERIO_MAIL_FROM` (il mittente
   verificato), `GERIO_MAIL_ENABLED` (`true`).
4. In `.github/workflows/consegna.yml` passare le sei variabili al passo di deploy (come per Stripe).
   `infra/deploy.sh` le legge dall'ambiente, tutte facoltative; i segreti viaggiano su stdin, il resto
   come argomenti (contratto: `infra/README.md`). Non sono mai stampati.
5. Un deploy (merge su `main` o `consegna` a mano) le porta nel `.env` del server e ad `app`.
   Un valore già scritto nel `.env` del server non si toglie togliendo il segreto o la variabile da
   GitHub (il deploy scrive solo i valori presenti). Per spegnere l'invio imposta `GERIO_MAIL_ENABLED=false`.

**Verifica:** fai un ordine di prova con la carta `4242 4242 4242 4242` (§4), con un'email tua. Poi:
```sql
select id, status, attempts, last_error, created_at from outbox_message
 where channel = 'EMAIL' order by id desc limit 5;
```
Atteso: `status = 'SENT'` entro un minuto circa e l'email nella casella (controlla anche lo spam).

**Rotazione della chiave:** crea la nuova chiave in Brevo, aggiorna il segreto `GERIO_SMTP_PASSWORD`,
lancia `consegna` con `deploy=true`, ripeti la verifica, poi disattiva la vecchia chiave in Brevo.

| Sintomo | Causa probabile | Cosa fare |
|---|---|---|
| Nessuna riga `EMAIL` in outbox | ordine non pagato o webhook non arrivato | §4 |
| `PENDING` che non si muove | `GERIO_MAIL_ENABLED` non `true` | variabile e nuovo deploy |
| `FAILED`/tentativi, `last_error` con 535 o «authentication» | login o chiave SMTP sbagliati o revocati | rigenera la chiave, aggiorna i segreti, deploy |
| `last_error` con «connect» o «timeout» | host o porta sbagliati, porta bloccata | `smtp-relay.brevo.com` e `587` |
| `SENT` ma non arriva | in spam, mittente non verificato, SPF/DKIM mancanti | log di Brevo, verifica mittente e dominio |
| Rimbalza | indirizzo del cliente sbagliato | log di Brevo; l'ordine resta valido |
| Deploy esce con 2 e «invalid GERIO_…» | valore non valido (porta non numerica, enabled né `true` né `false`, spazi o virgolette) | correggi la variabile |

## 6. La catena dei giri si è fermata
1. Guarda le PR aperte con etichetta `giro`: `domanda`, `revisione-umana` o `bloccato` dicono chi
   aspetta chi. Una PR `fuori-catena` è entrata in main senza controlli: guardala per prima.
2. Guarda l'ultimo run del workflow `consegna`: il passo «Start the next round» dice se il limite
   giornaliero, l'interruttore o i segreti della Routine l'hanno fermato.
3. Per ripartire subito: pagina della Routine → **Run now**.
