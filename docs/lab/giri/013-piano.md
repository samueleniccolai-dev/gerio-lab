---
giro: 013
traguardo: T5
fase: domanda (da: piano)
inizio: 2026-10-15T09:10:56Z
ricerca: no
accettazione:
ripreso: no
---
# Piano del giro 013 — T5, prova in produzione con Stripe test

## DOMANDA (porta a senso unico: account e chiavi Stripe dell'umano)

**Contesto.** Il giro 012 è in `main` e deployato (67f0560); `consegna.yml` passa già le tre
variabili Stripe al deploy (a055b94). Ma la produzione dice che i segreti non ci sono:

| Controllo (15/10, 09:15 UTC) | Risultato | Significato |
|---|---|---|
| `curl -X POST https://gerio-lab.samueleniccolai.com/webhooks/stripe/connect` | **503** | il server non ha `STRIPE_CONNECT_WEBHOOK_SECRET` (con il segreto sarebbe 400) |
| `https://demo.gerio-lab.samueleniccolai.com/catalogo` | 200 | sito su |

Quindi i passi 1–3 del runbook §4 (account Stripe test con Connect, account collegato per
«Fioreria Il Glicine», endpoint webhook, segreti e variabile nell'environment `produzione`) non
risultano fatti, oppure sono stati fatti dopo l'ultimo deploy. Gli agenti non possono farli
(account, chiavi e segreti sono dell'umano; il guard blocca `gh secret`). Senza di essi il
«Fatto quando» di T5 («si paga con Stripe Checkout in modalità test e il webhook segna l'ordine
pagato») non si può provare.

**Opzioni.**

| | Cosa | Pro | Contro |
|---|---|---|---|
| **A** (raccomandata) | Fai i passi 1–3 del runbook §4 e rilancia il deploy (`consegna`, `workflow_dispatch` con `deploy`). Poi il giro costruisce smoke rigoroso, script di prova e runbook; a fine giro l'agente crea l'ordine in produzione e ti passa il link di Checkout; **tu paghi** con la carta di test 4242 e commenti `pagato` sulla PR; l'agente verifica la pagina di esito e segna T5 `passato`. | prova vera, T5 chiuso in questo giro; un pagamento a mano solo una volta | serve una tua azione a metà giro (pochi minuti) |
| **B** | Il giro fa solo gli strumenti (smoke rigoroso quando il segreto c'è, script, runbook) senza prova vera; T5 resta `in-corso`, la prova va al giro 014. | la catena non si ferma oggi | un giro in più; strumenti scritti senza poterli provare sul vero |
| **C** | Come A, ma il pagamento lo fa l'agente con Playwright sulla pagina ospitata da Stripe. | nessuna azione tua per pagare | pagina di terzi con iframe e anti-bot: fragile; Stripe sconsiglia di automatizzare Checkout. Sconsigliata |

**Dopo la risposta.** A: il giro riparte dalla critica di questo piano quando il webhook risponde
400 (lo controlla l'agente con `curl`). B: tolgo dal «Fatto quando» le righe marcate *(prova vera)*
e T5 resta `in-corso`. C: aggiungo un test E2E in produzione, che ti presento prima.

## Fetta e traguardo
T5, parte 4 (ultima): rendere la prova verificabile e farla. Lo smoke diventa rigoroso quando
il segreto del webhook è configurato (oggi accetta 503 per sempre), uno script a scatola chiusa
porta un ordine del negozio demo fino al link di Stripe Checkout, il runbook §4 spiega prova e
guasti. Poi un pagamento di test vero e T5 → `passato`.

## Dati del negozio demo (inventati, gli stessi dei giri 010–012)
- Negozio: `demo.gerio-lab.samueleniccolai.com`, «Fioreria Il Glicine».
- Carrello: 1 × «Mazzo di rose rosse — 12 rose» 4500 + 2 × «Orchidea Phalaenopsis bianca — Vaso
  12 cm» 2990 = **10480** centesimi («104,80 €»); ritiro in negozio, costo 0.
- Cliente: Giulia Bianchi, `giulia.bianchi@example.com`, `+39 333 000 1234`; ritiro il giorno dopo
  la prova (data locale `Europe/Rome`).
- Carta di test Stripe: 4242 4242 4242 4242, scadenza futura qualsiasi, CVC qualsiasi.
- Nei test: segreto finto `whsec_giro013_finto`, account finto `acct_1GlicineDemoTest0`, dominio
  finto `lab.example.test` (curl finto come in `Giro006DemoHostAcceptanceTest`).

## Fatto quando — controlli eseguibili
- [ ] `infra/smoke.sh` con `STRIPE_CONNECT_WEBHOOK_SECRET` non vuoto nell'ambiente: `POST
      https://$LAB_BASE_DOMAIN/webhooks/stripe/connect` deve dare **400**; 503 → smoke rosso con
      messaggio che nomina il webhook (mai il segreto). Senza la variabile resta come oggi (400 o
      503). Il demo resta 404 → `Giro013SmokeStripeAcceptanceTest` (curl finto).
- [ ] `infra/smoke.sh` non crea ordini né sessioni Stripe (nessun `POST /checkout`) →
      `Giro013SmokeStripeAcceptanceTest`.
- [ ] `infra/prova-stripe.sh <dominio-demo>`: con cookie del carrello e token CSRF letti dalle
      pagine, aggiunge il carrello dei dati, apre `/checkout` e vuole «Ordina e paga» e «104,80 €»,
      invia i dati di Giulia e vuole **303** con `Location` che inizia con
      `https://checkout.stripe.com/`; stampa solo quell'URL; exit 0. Riepilogo senza «Ordina e
      paga» → exit 1 con «checkout spento». `bash -n` e `shellcheck` puliti → `Giro013ProvaStripeAcceptanceTest` (curl finto).
- [ ] `infra/prova-stripe.sh esito <dominio-demo> <session_id>`: `GET /ordine/esito?session_id=…`
      contiene il testo di ordine pagato della pagina di esito → exit 0; in attesa → exit 1 →
      `Giro013ProvaStripeAcceptanceTest` (curl finto).
- [ ] `docs/runbook.md` §4 completo: passi dell'umano, prova con lo script e la carta 4242, cosa
      guardare se il webhook non arriva (dashboard Stripe → consegne del webhook, 400 = segreto
      sbagliato, 503 = segreto assente, 404 = dominio del demo), rinvio di un evento. Revisione.
- [ ] *(prova vera)* `curl -s -o /dev/null -w '%{http_code}' -X POST
      https://gerio-lab.samueleniccolai.com/webhooks/stripe/connect` → `400`.
- [ ] *(prova vera)* `infra/prova-stripe.sh demo.gerio-lab.samueleniccolai.com` → exit 0 e URL
      `https://checkout.stripe.com/…`.
- [ ] *(prova vera)* L'umano paga con 4242 e commenta `pagato`; `infra/prova-stripe.sh esito …` →
      exit 0 entro 30 s; nella dashboard Stripe (test) l'evento `checkout.session.completed` ha
      risposta 200. T5 → `passato`, `passato_il` = ora della verifica dell'esito.
- [ ] Test dei giri precedenti verdi; `./mvnw -B -ntp verify` verde (esclusi i 2 visivi senza Chromium).

## Fuori perimetro
- Modifiche all'app Java: checkout e webhook sono quelli dei giri 010–012. Se la prova vera trova un
  difetto, si registra e si corregge in un giro di riparazione, non qui.
- `.github/` (protetto): nessuna modifica; lo smoke legge solo variabili già passate al passo.
- Pagamento automatico su Checkout (opzione C), scenari rifiutato/3-D Secure/scaduto in produzione.
- Modalità live, ordine reale con rimborso (SPEC §8.5, prima del go-live), `charge.refunded`,
  `charge.dispute.created`, `account.updated`, onboarding dall'area admin.
- Email di conferma e pagina stato ordine (T6).
- Da `traguardi.json → fuori_perimetro`: back-office del negozio (piano B: email e dashboard
  Stripe); consegna `CEMETERY`; capacità per fascia oraria; impianto consensi e GDPR oltre la
  pagina privacy; SPF e DKIM completi.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|
| `Giro013SmokeStripeAcceptanceTest` | segreto presente e webhook 503 → smoke rosso, senza segreto nel log; segreto presente e 400 → verde; segreto assente e 503 → verde; nessun `POST /checkout` |
| `Giro013ProvaStripeAcceptanceTest` | curl finto: carrello → riepilogo con «Ordina e paga» → 303 su `checkout.stripe.com` → exit 0 e solo l'URL; checkout spento → exit 1; esito pagato/in attesa → 0/1 |

## Porte
- Doppio senso: smoke rigoroso legato a una variabile (si toglie con una riga); script di prova
  nuovo in `infra/` (si cancella); testo del runbook.
- `infra/` è percorso protetto: PR in revisione umana (METODO §4).
- Senso unico: **DOMANDA** in testa (account, chiavi e segreti Stripe test dell'umano; chi paga).
  Nessuna dipendenza, nessuna migrazione.

## Rischi di concorrenza
- Lo script crea un ordine `PENDING_PAYMENT` vero nel negozio demo a ogni lancio: si lancia a mano,
  non nello smoke; un ordine non pagato scade con Stripe (`checkout.session.expired`).
- Webhook duplicato o rinviato dalla dashboard: idempotenza già provata nel giro 011; nella prova
  vera un «Rinvia» dalla dashboard deve dare 200 senza cambiare l'ordine (riga del runbook).

## Budget
Righe di produzione ≤ 120 (shell ≤ 90, runbook ≤ 30); file ≤ 4; una sessione.

## Domande per il ricercatore (al massimo 3)
Nessuna (`ricerca: no`: binari e verifica; il cliente non vede nulla di nuovo).

## Idee per dopo
- Prova periodica a scatola chiusa del checkout (senza pagare) per accorgersi di chiavi revocate.
- Scenari di prova in produzione: carta rifiutata (4000 0000 0000 0002), 3-D Secure, sessione scaduta.

## Critica ricevuta e cosa cambia
(dopo il critico)
