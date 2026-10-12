---
giro: 010
traguardo: T5
fase: critica
inizio: 2026-10-12T06:53:38Z
ricerca: sì
accettazione:
ripreso: no
---
# Piano del giro 010 — dal carrello alla sessione Stripe Checkout (T5, parte 1)

## DOMANDA (porta a senso unico: dipendenza nuova)

**Contesto.** T5 chiede di creare sessioni Stripe Checkout sull'account collegato del negozio
(header `Stripe-Account`) e di verificare la firma dei webhook. SPEC §8.3 dice «con le API Java di
Stripe». Nel `pom.xml` oggi non c'è nessuna libreria Stripe. `pom.xml` è un percorso protetto:
la PR va comunque in revisione umana.

| Opzione | Cosa entra | Pro | Contro |
|---|---|---|---|
| **A. `com.stripe:stripe-java` 34.0.0** (ultima stabile su Maven Central al 12/10/2026; dipende da `gson`) | 1 dipendenza Maven + `gson` transitiva | come da SPEC §8.3; `RequestOptions` con `stripeAccount` e `idempotencyKey`; `Webhook.constructEvent` verifica firma e tolleranza sul tempo; `Stripe.overrideApiBase` permette uno Stripe finto nei test | libreria grande; versione dell'API Stripe legata alla versione della libreria (aggiornamenti periodici) |
| B. Nessuna dipendenza: `java.net.http.HttpClient` + form-encoding a mano + HMAC-SHA256 a mano per la firma | niente | nessuna porta; codice piccolo e visibile | verifica della firma scritta da noi (sicurezza); parsing JSON a mano o con Jackson già presente; scostamento da SPEC §8.3 da registrare |

**Raccomandazione: A.** La verifica della firma è codice di sicurezza: meglio quello ufficiale.

**Dopo ogni risposta.**
- Sì ad A: il giro riprende con questo piano; il costruttore aggiunge la dipendenza al `pom.xml`.
- B: riscrivo il piano con client HTTP e firma a mano, e registro lo scostamento da §8.3.
- No a entrambe: T5 resta fermo; il giro si chiude con solo documenti.

**Per dopo (non blocca questo giro, serve per la prova in produzione, giro 012):** su Stripe in
**modalità test** l'umano crea l'account della piattaforma con Connect, un account collegato di
prova per «Fioreria Il Glicine», l'endpoint webhook `/webhooks/stripe/connect` per gli eventi degli
account collegati, e mette sul server le variabili `STRIPE_SECRET_KEY` (sk_test_…),
`STRIPE_CONNECT_WEBHOOK_SECRET` e l'id dell'account collegato del demo. Nessuna spesa, niente live.

## Fetta e traguardo
T5 parte 1: dal carrello si preme «Ordina e paga», nasce l'ordine `PENDING_PAYMENT` e il cliente
va alla sessione Stripe Checkout del negozio. T4 è passato, T5 è il primo traguardo aperto.
**Resta per T5:** giro 011 webhook `/webhooks/stripe/connect` firmato e idempotente
(`stripe_event`, `PAID`/`EXPIRED`, esclusione CSRF, DECISIONI n. 18) e pagina di esito con htmx;
giro 012 prova sul demo in produzione con Stripe test.

## Dati del negozio demo (per i test)
- Negozio: `demo`, «Fioreria Il Glicine»; `stripe_account_id = 'acct_1GlicineDemoTest'`,
  `stripe_charges_enabled = true` nei test (in produzione l'id vero arriva da configurazione).
- Carrello: 1 × «Mazzo di rose rosse — 12 rose» 4500 + 2 × «Orchidea Phalaenopsis bianca — Vaso
  12 cm» 2990 = subtotale **10480** («104,80 €»); ritiro in negozio, costo 0; totale **10480**.
- Cliente: Giulia Bianchi, `giulia.bianchi@example.com`, `+39 333 000 1234`; ritiro il giorno dopo
  «oggi» del `Clock`.
- Numero del primo ordine dell'anno: `2026-0001`.

## Fatto quando — controlli eseguibili
- [ ] `GET /checkout` col carrello sopra (Host del demo) → 200, riepilogo con le due righe,
      «104,80 €», campi nome, email, telefono, data di ritiro, link alle condizioni, pulsante
      «Ordina e paga».
- [ ] `GET /checkout` con carrello vuoto o senza cookie → 303 verso `/carrello`.
- [ ] `POST /checkout` con dati validi e token CSRF → 303 con `Location` uguale all'`url` della
      sessione restituita dallo Stripe finto; lo Stripe finto ha ricevuto `mode=payment`,
      `currency=eur`, le righe con importi dal database, `client_reference_id`, `metadata[tenant_id]`,
      `metadata[order_number]=2026-0001`, `customer_email`, `expires_at` = ora + 30 min, header
      `Stripe-Account: acct_1GlicineDemoTest` e una chiave di idempotenza `order-<id>-attempt-1`.
- [ ] `POST /checkout` con email vuota o data passata → 400 (o 200 con errori nel form), nessun
      ordine creato, nessuna chiamata a Stripe.
- [ ] Prezzi inviati dal browser ignorati: un campo `price_cents` nel form non cambia il totale.
- [ ] Negozio con `stripe_charges_enabled = false` → il riepilogo non mostra «Ordina e paga» e
      `POST /checkout` non chiama Stripe (SPEC §8.2).
- [ ] Isolamento: il cookie del carrello del negozio A usato sull'Host di B non crea ordini in B
      né in A; gli ordini di A non sono visibili con il contesto di B.
- [ ] Numerazione: due ordini di fila nello stesso negozio → `2026-0001`, `2026-0002`; in un altro
      negozio si riparte da `2026-0001`.
- [ ] `./mvnw -B -ntp verify` verde; `python3 -I scripts/guard/pr_guard.py --base origin/main --head HEAD` verde.

## Fuori perimetro
- Webhook, `stripe_event`, stati `PAID`/`EXPIRED`, pagina di esito, esclusione CSRF (giro 011).
- Prova in produzione con chiavi Stripe test vere (giro 012). Stripe live: mai.
- Consegna a domicilio, zone, orario limite, fasce e capacità (`pg_advisory_xact_lock`): T7.
- Fattura e dati fiscali, biglietto, destinatario diverso: dopo la demo.
- Email di conferma e pagina di stato dell'ordine: T6.
- Onboarding Connect dall'area admin (§8.2): fuori, l'account di prova lo crea l'umano.
- Da `traguardi.json`: back-office del negozio; tipo `CEMETERY`; capacità per fascia; impianto
  consensi e GDPR oltre la pagina privacy (niente `consent_record`); SPF e DKIM completi.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|
| `Giro010CheckoutAcceptanceTest` | una riga per controllo JUnit del «Fatto quando»; Stripe finto configurato con `app.stripe.api-base` nel profilo di test |

## Porte
- Doppio senso previste: percorsi `/checkout` (riepilogo e invio); `V5__orders.sql` con
  `customer_order`, `order_item`, `order_event`, `tenant_counter` (solo aggiunte, colonne di §6
  necessarie ora, le altre si aggiungono dopo); configurazione `app.stripe.*` da variabili
  d'ambiente; Stripe finto solo in `src/test`; `OrderStateMachine` con la sola transizione
  iniziale.
- Senso unico: **DOMANDA** sopra (dipendenza Maven nuova).

## Rischi di concorrenza
- Doppio clic su «Ordina e paga»: due ordini. Si blocca il carrello (`PESSIMISTIC_WRITE`, come
  DECISIONI n. 16) e si riusa l'ordine `PENDING_PAYMENT` dello stesso carrello se esiste; prova:
  due POST in parallelo → un solo ordine.
- Numerazione: `update tenant_counter … returning` nella stessa transazione (§7.2); prova con
  due ordini in parallelo → numeri distinti.
- Chiamata a Stripe fuori dalla transazione dell'ordine (niente connessione DB tenuta durante
  l'HTTP); se Stripe fallisce l'ordine resta `PENDING_PAYMENT` e il riepilogo mostra un errore.

## Budget
Righe di produzione ≤ 450; file ≤ 14; deve stare in una sessione.

## Domande per il ricercatore (al massimo 3)
1. Con stripe-java 34: come si impostano `Stripe-Account`, chiave di idempotenza ed `expires_at`
   su `SessionCreateParams`, e come puntare il client a uno Stripe finto nei test?
2. Il riepilogo prima del pagamento: cosa mostrano Shopify e due fiorai online italiani (campi
   obbligatori, condizioni di vendita, data di ritiro)?
3. Un account collegato in modalità test con dashboard completa (proprietà `controller`): quali
   valori, e si può creare dalla dashboard senza codice?

## Idee per dopo
- «Riprova il pagamento» dopo l'annullo (§7.5 punto 6) quando arriva il webhook `expired`.

## Critica ricevuta e cosa cambia
(dopo il critico)
