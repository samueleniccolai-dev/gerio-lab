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

### 0.6 Come verifico che ha funzionato
```bash
ssh -i ~/.ssh/lab_deploy deploy@IPV4 docker ps   # risponde con la tabella vuota di Docker
```
**Ultima prova:** —

## 1. Deploy e rollback
*(da scrivere con il traguardo T1: `infra/deploy.sh`, `infra/smoke.sh`, `infra/rollback.sh`,
contratto in `infra/README.md`)*

## 2. Ripristino di database e foto
*(da scrivere)* — Ultima prova: —

## 3. Cambio dei segreti
*(da scrivere)*

## 4. Webhook Stripe che non arrivano o falliscono
*(da scrivere con il traguardo T5)*

## 5. Email in spam o rimbalzate
*(da scrivere con il traguardo T6)*

## 6. La catena dei giri si è fermata
1. Guarda le PR aperte con etichetta `giro`: `domanda`, `revisione-umana` o `bloccato` dicono chi
   aspetta chi. Una PR `fuori-catena` è entrata in main senza controlli: guardala per prima.
2. Guarda l'ultimo run del workflow `consegna`: il passo «Start the next round» dice se il limite
   giornaliero, l'interruttore o i segreti della Routine l'hanno fermato.
3. Per ripartire subito: pagina della Routine → **Run now**.
