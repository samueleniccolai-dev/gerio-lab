---
giro: 010
traguardo: T5
fase: costruzione
fonti_ricerca: 7
inizio: 2026-10-12T06:53:38Z
ricerca: sì
accettazione: 73a0343
ripreso: no
---
# Piano del giro 010 — dal carrello alla sessione Stripe Checkout (T5, parte 1)

## RISPOSTA RICEVUTA: opzione A (`com.stripe:stripe-java` 34.0.0), 12/10, DECISIONI n. 21

## DOMANDA (porta a senso unico: dipendenza nuova) — risolta

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

## Cosa si costruisce (dopo la critica)
- **Configurazione** `app.stripe.*` (`@ConfigMapping`): `secret-key` (`STRIPE_SECRET_KEY`),
  `api-base` (solo test) e `connect-webhook-secret` (`STRIPE_CONNECT_WEBHOOK_SECRET`, per il giro
  011), **tutte `Optional`**. Senza chiave l'app parte e il checkout è spento (come
  `stripe_charges_enabled = false`). Nessun valore `%test.` per la chiave: lo mette solo lo Stripe
  finto dei test.
- **`StripeClient` da CDI** (`@ApplicationScoped`, prodotto solo se c'è la chiave; niente globali
  statici `Stripe.apiKey`): `setApiBase` se configurato, timeout espliciti (connessione 5 s,
  lettura 20 s) e `setMaxNetworkRetries(2)` (ritenta 409 e 5xx con la stessa chiave: provato con lo
  Stripe finto). Nomi esatti dei metodi del builder → `docs/verifiche.md`.
- **`V5__orders.sql`** (solo aggiunte, importi `bigint` come DECISIONI n. 10):
  - `customer_order` con le colonne di §6 servono ora: `number`, `public_token` (≥128 bit),
    `status`, `fulfillment_type` (`'PICKUP'`), `customer_name/email/phone`, `delivery_date`,
    `subtotal_cents`, `delivery_fee_cents`, `total_cents`, `vat_breakdown` (jsonb, imponibile e
    imposta per aliquota), `terms_version` (costante, per ora `'2026-10-12'`), `hold_expires_at`,
    `stripe_checkout_session_id unique`, audit e `version`; **in più** (scostamento da §6, in
    `docs/scostamenti-4-10.md`): `cart_id` (FK composta su `cart`, `on delete set null`),
    `checkout_attempt int not null default 0`, `checkout_expires_at timestamptz`, `checkout_url text`.
    `unique (tenant_id, id)`, `unique (tenant_id, number)`, indice per `tenant_id`.
  - `order_item` e `order_event` come §6 (FK composte con `tenant_id`, `actor_user_id` rimandato).
  - `tenant_counter` con `id` identity e `unique (tenant_id, id)` (regole di `SchemaRulesTest`)
    e `unique (tenant_id, name, year)`.
- **Numerazione** (§7.2): `insert into tenant_counter (tenant_id, name, year, value) values
  (?, 'order', ?, 1) on conflict (tenant_id, name, year) do update set value =
  tenant_counter.value + 1 returning value`, nella transazione che crea l'ordine. SQL nativo: non
  filtrato, quindi `tenant_id` passato esplicitamente da `TenantContext`. Anno = `LocalDate.now`
  del `Clock` in `Europe/Rome`. Formato `%d-%04d`.
- **`OrderStateMachine`** ridotta a `start(order)`: stato `PENDING_PAYMENT` e una riga
  `order_event` (`from_status` null).
- **`CheckoutService`**, due transazioni brevi e la chiamata a Stripe in mezzo, senza connessione
  DB tenuta durante l'HTTP:
  1. Transazione A: carrello in `PESSIMISTIC_WRITE` (DECISIONI n. 16); validazione (nome e email
     non vuoti, email con `@`, data ISO ≥ oggi a Roma + `lead_time_days` massimo); prezzi letti
     ora dal catalogo. Cerca l'ultimo ordine `PENDING_PAYMENT` dello stesso `cart_id`: si riusa
     solo se righe, quantità, prezzi, dati cliente e data coincidono; altrimenti ordine nuovo
     (numero nuovo). Se l'ordine riusato ha `checkout_url` e `checkout_expires_at` > ora → fine,
     redirect senza chiamare Stripe. Altrimenti `checkout_attempt += 1` solo se il tentativo
     precedente è fallito o scaduto; `checkout_expires_at` = ora + 31 min (fissato una volta per
     tentativo, così i parametri restano identici); `hold_expires_at` = ora + 35 min.
  2. Fuori transazione: sessione Stripe con `RequestOptions` (`stripeAccount`, chiave
     `order-<id>-attempt-<n>`), parametri di §8.3, nessun `payment_method_types`; `success_url`
     `https://<dominio primario o slug.dominio-base>/ordine/esito?session_id={CHECKOUT_SESSION_ID}`
     (la pagina arriva al giro 011: fino ad allora 404), `cancel_url` `…/checkout`.
  3. Transazione B: salva `stripe_checkout_session_id` e `checkout_url`. Se Stripe fallisce:
     l'ordine resta `PENDING_PAYMENT`, il tentativo è segnato fallito (il prossimo usa n+1),
     risposta 502 con «Non è stato possibile avviare il pagamento. Riprova.».
  - Il carrello **non** si svuota al checkout: si svuota quando l'ordine diventa `PAID` (giro 011).
- **`CheckoutResource`** (`/checkout`, solo Host di un negozio; piattaforma 404): `GET` riepilogo
  e form; `POST` → 303 a Stripe, 303 a `/carrello` se vuoto, 400 con il form se i dati non vanno,
  409 se il checkout è spento (chiave assente o `stripe_charges_enabled = false`, con i contatti del
  negozio), 502 se Stripe fallisce. Il carrello mostra il link «Procedi all'ordine» a `/checkout`.
- **Log:** mai il form né il corpo delle risposte Stripe; solo id ordine, tentativo e codice errore.

## Dati del negozio demo (per i test)
- Negozio: `demo`, «Fioreria Il Glicine»; `stripe_account_id = 'acct_1GlicineDemoTest'`,
  `stripe_charges_enabled = true` nei test (in produzione l'id vero arriva da configurazione).
- Secondo negozio: «Fiori di Marco» (`fiori-di-marco.localhost`), `acct_1MarcoDemoTest`, 1 ×
  «Bouquet di stagione — Unico» 3000.
- Carrello: 1 × «Mazzo di rose rosse — 12 rose» 4500 + 2 × «Orchidea Phalaenopsis bianca — Vaso
  12 cm» 2990 = subtotale **10480** («104,80 €»); ritiro in negozio, costo 0; totale **10480**.
  Con un'orchidea in più: **13470**.
- Cliente: Giulia Bianchi, `giulia.bianchi@example.com`, `+39 333 000 1234`.
- `Clock` dei test: 12/10/2026 08:00 UTC; ritiro `2026-10-13`. Casi limite: 12/10 22:30 UTC
  (a Roma è il 13) e 31/12/2026 23:30 UTC (a Roma è il 2027 → `2027-0001`).
- Numero del primo ordine dell'anno: `2026-0001`.
- Campi del form: `customerName`, `customerEmail`, `customerPhone`, `pickupDate` (`type=date`,
  ISO), più `csrf-token`.

## Fatto quando — controlli eseguibili
Test JUnit in `Giro010CheckoutAcceptanceTest` (A), `Giro010CheckoutRetryAcceptanceTest` (R),
`Giro010CheckoutWithoutStripeAcceptanceTest` (S); Stripe finto `Giro010FakeStripe`.
- [ ] (A) Il carrello pieno ha il link a `/checkout`; `GET /checkout` → 200, le due righe,
      «104,80 €», form POST `/checkout` con i quattro campi (data `type=date`), token CSRF, link a
      `/condizioni-di-vendita` (la pagina arriva con T8), pulsante «Ordina e paga».
- [ ] (A) `GET /checkout` senza cookie o con carrello svuotato → 303 a `/carrello`.
- [ ] (A) `POST /checkout` valido → 303 all'`url` della sessione; Stripe ha ricevuto: chiave
      `Bearer`, `Stripe-Account: acct_1GlicineDemoTest`, chiave `order-<id>-attempt-1`,
      `mode=payment`, `currency=eur`, le due righe (4500 × 1, 2990 × 2), `client_reference_id` =
      id, `metadata[tenant_id]`, `metadata[order_number]=2026-0001`, `customer_email`,
      `expires_at` in (ora+30 min, ora+35 min], `success_url` e `cancel_url` https sul dominio del
      negozio, nessun `payment_method_types`. Nel DB: ordine `PENDING_PAYMENT`, `PICKUP`, importi
      10480/0/10480, data `2026-10-13`, `public_token` ≥ 22 caratteri, `terms_version`, session id,
      `hold_expires_at` = ora + 35 min, un `order_event` (null → `PENDING_PAYMENT`). Il carrello
      resta pieno.
- [ ] (A) Prezzo della rosa cambiato nel DB dopo l'aggiunta (3900 → 4500) e campi `price_cents`,
      `unit_amount`, `total_cents` nel form: a Stripe arriva 4500; `order_item` 4500/4500 e
      2990×2/5980; totale 10480.
- [ ] (A) Email vuota o senza `@`, nome vuoto, data passata, data non ISO, data mancante → 400 col
      form, zero ordini, zero chiamate a Stripe; poi il form valido → 303 (controllo positivo).
- [ ] (A) `stripe_charges_enabled = false` → riepilogo senza «Ordina e paga», con il telefono del
      negozio; `POST` → 409, zero ordini, zero chiamate.
- [ ] (A) `POST` senza campo CSRF → 400, zero ordini, zero chiamate.
- [ ] (A) Dominio della piattaforma: `GET` e `POST /checkout` (con token valido) → 404, zero
      ordini, zero chiamate. *Passa già oggi: resta come guardia.*
- [ ] (A) Isolamento: il cookie del carrello del demo sull'Host di Marco → `GET` e `POST` 303 a
      `/carrello`, zero ordini nei due negozi, zero chiamate a Stripe.
- [ ] (A) Numerazione: due ordini nel demo `2026-0001`, `2026-0002`; Marco `2026-0001` con
      `Stripe-Account: acct_1MarcoDemoTest` e totale 3000.
- [ ] (R) Secondo `POST` col carrello invariato → stesso URL, nessuna nuova chiamata, un ordine.
- [ ] (R) Doppio clic: Stripe finto in ritardo di 500 ms, due `POST` in parallelo → un ordine, una
      sessione, due 303 allo stesso URL.
- [ ] (R) Carrello cambiato dopo il primo tentativo → ordine nuovo `2026-0002` (13470), chiave
      `order-<nuovo id>-attempt-1`, righe aggiornate, nuovo URL.
- [ ] (R) Stripe risponde 500 → 502 con «Riprova», ordine `PENDING_PAYMENT`, tutte le chiamate con
      `attempt-1`; il `POST` successivo usa `attempt-2`, 303, stesso ordine, session id salvato.
- [ ] (R) Primi due ordini dell'anno in parallelo (due carrelli) → `2026-0001` e `2026-0002`.
- [ ] (R) `Clock` 31/12/2026 23:30 UTC → numero `2027-0001`.
- [ ] (R) `Clock` 12/10 22:30 UTC: ritiro `2026-10-12` → 400 senza chiamate; `2026-10-13` → 303.
- [ ] (S) Senza `app.stripe.secret-key` l'app parte; riepilogo senza «Ordina e paga» col telefono;
      `POST` → 409, zero ordini.
- [ ] `SchemaRulesTest` e `FlywaySafetyTest` verdi con `V5`; test di isolamento del repository
      degli ordini (regola 1) scritti dal costruttore.
- [ ] `./mvnw -B -ntp verify` verde; `python3 -I scripts/guard/pr_guard.py --base origin/main --head HEAD` verde.

## Fuori perimetro
- Webhook, `stripe_event`, stati `PAID`/`EXPIRED`, pagina di esito, esclusione CSRF (giro 011).
- Prova in produzione con chiavi Stripe test vere (giro 012). Stripe live: mai.
- Consegna a domicilio, zone, orario limite, fasce e capacità (`pg_advisory_xact_lock`): T7.
- Fattura e dati fiscali, biglietto, destinatario diverso: dopo la demo.
- Email di conferma e pagina di stato dell'ordine: T6.
- Onboarding Connect dall'area admin (§8.2): fuori, l'account di prova lo crea l'umano.
- Pagina `/ordine/esito` (404 fino al giro 011) e pagina `/condizioni-di-vendita` (T8).
- Scadenza esplicita (`expire`) della sessione precedente quando il carrello cambia o dopo un
  timeout di rete: giro 011.
- Svuotare il carrello: al `PAID`, giro 011.
- Da `traguardi.json`: back-office del negozio; tipo `CEMETERY`; capacità per fascia; impianto
  consensi e GDPR oltre la pagina privacy (niente `consent_record`); SPF e DKIM completi.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|
| `Giro010CheckoutAcceptanceTest` | dato il carrello del piano, quando si apre e si invia `/checkout`, allora riepilogo, ordine, parametri Stripe, validazione, prezzi dal DB, CSRF, piattaforma, isolamento, numerazione |
| `Giro010CheckoutRetryAcceptanceTest` | dato un primo tentativo, quando si ripete, si clicca due volte, cambia il carrello, Stripe fallisce o cambia giorno a Roma, allora un ordine e una sessione, chiavi `attempt-n` giuste, numeri e date di Roma |
| `Giro010CheckoutWithoutStripeAcceptanceTest` | dato il profilo di test senza chiave, quando si apre e si invia `/checkout`, allora checkout spento e nessun ordine |
| `Giro010FakeStripe`, `Giro010CheckoutData`, `Giro010Checkout` | Stripe finto (`HttpServer` JDK, 16 thread; rigioca le risposte per chiave di idempotenza come Stripe: 409 in corso, 400 parametri diversi, 500 conservato), dati e aiuti |

Lo Stripe finto è attivato con `@WithTestResource`: imposta `app.stripe.api-base` e
`app.stripe.secret-key`. Provato fuori dal progetto con `stripe-java` 34.0.0 (`StripeClient`,
`RequestOptions`, `setMaxNetworkRetries(2)`): parametri e header arrivano come attesi, il 409 si
ritenta e rigioca la stessa sessione, il 500 si rigioca per la stessa chiave.

## Porte
- Doppio senso previste: percorsi `/checkout`; `V5__orders.sql` (solo aggiunte); colonne fuori
  §6 (`cart_id`, `checkout_attempt`, `checkout_expires_at`, `checkout_url`) e importi `bigint`
  registrati in `docs/scostamenti-4-10.md`; `app.stripe.*` opzionali da variabili d'ambiente;
  Stripe finto solo in `src/test`; `OrderStateMachine.start`.
- Senso unico: dipendenza `stripe-java` 34.0.0 — **risolta** (opzione A, DECISIONI n. 21). Il
  `pom.xml` è protetto: PR in revisione umana.
- Ratifica (DECISIONI): carrello non svuotato al checkout; carrello cambiato → ordine nuovo; 409
  con contatti se il checkout è spento.

## Rischi di concorrenza
- Doppio clic: carrello in `PESSIMISTIC_WRITE` nella transazione A; il secondo `POST` riusa lo
  stesso ordine e, se la sessione non è ancora salvata, richiama Stripe con **parametri identici**
  e la stessa chiave (`checkout_expires_at` salvato): Stripe risponde 409
  `idempotency_key_in_use` durante la prima chiamata (ritentato dal client) e poi rigioca la stessa
  sessione. Prova: test con Stripe in ritardo di 500 ms.
- Numerazione: `insert … on conflict … do update … returning` (anche il primo ordine dell'anno è
  atomico); prova con due ordini in parallelo.
- Chiamata a Stripe fuori transazione; timeout espliciti sul client. Timeout di rete dopo che
  Stripe ha creato la sessione: il tentativo successivo usa n+1 e la sessione vecchia resta aperta
  fino a scadenza → giro 011 (`expire`).

## Budget
Righe di produzione ≤ 650 (era 450: idempotenza per tentativo, numerazione, configurazione
opzionale); file ≤ 16; deve stare in una sessione.

## Domande per il ricercatore (al massimo 3)
1. Con stripe-java 34: come si impostano `Stripe-Account`, chiave di idempotenza ed `expires_at`
   su `SessionCreateParams`, e come puntare il client a uno Stripe finto nei test?
2. Il riepilogo prima del pagamento: cosa mostrano Shopify e due fiorai online italiani (campi
   obbligatori, condizioni di vendita, data di ritiro)?
3. Un account collegato in modalità test con dashboard completa (proprietà `controller`): quali
   valori, e si può creare dalla dashboard senza codice?

## Idee per dopo
- «Riprova il pagamento» dopo l'annullo (§7.5 punto 6) quando arriva il webhook `expired`.
- Giro 011: `expire` della sessione dell'ordine abbandonato (carrello cambiato, timeout di rete).
- Casella esplicita «accetto le condizioni» se T8 o un professionista la chiedono.

## Critica ricevuta e cosa cambia
Accolto (tutti i 5 «DA CORREGGERE» e le proposte di semplificazione):
1. Idempotenza: sull'ordine `checkout_attempt`, `checkout_expires_at`, session id e URL (seconda
   transazione); URL valido → redirect senza Stripe; dopo errore `attempt-(n+1)`. Il costruttore
   registra in `docs/verifiche.md` il comportamento delle chiavi (409, 400, 500 conservato 24 h).
2. Ordine riusato solo se carrello e dati coincidono; altrimenti **ordine nuovo** (scelto rispetto
   ad `attempt+1`: l'ordine resta immutabile dopo la creazione). `cart_id` su `customer_order`
   (scostamento in `docs/scostamenti-4-10.md`). Il carrello non si svuota fino al `PAID`.
3. `tenant_counter` con upsert `on conflict … returning`, `tenant_id` esplicito, `id` identity e
   `unique (tenant_id, id)`, anno dal `Clock` in `Europe/Rome`; test di Capodanno e del primo
   ordine dell'anno in parallelo.
4. `expires_at` = ora + 31 min, `hold_expires_at` = ora + 35 min; il test accetta (30, 35] min.
5. `app.stripe.*` opzionali, checkout spento senza chiave, test dedicato in un profilo senza chiave.
6. Più semplice: `StripeClient` da CDI, Stripe finto con `HttpServer` multi-thread (niente
   WireMock), `OrderStateMachine.start`.
7. Concorrenza: doppio clic con ritardo 500 ms; `maxNetworkRetries` 2 (verificato che ritenta il
   409); timeout espliciti; `expire` annotato per il giro 011.
8. Test mancanti: tutti scritti (prezzo cambiato nel DB, isolamento con zero chiamate e zero righe,
   piattaforma `GET`/`POST` 404, `POST` senza CSRF, doppio `POST`, carrello cambiato, Stripe 500,
   data di Roma alle 22:30 UTC, avvio senza chiave).
9. Facoltativi accolti: colonne `not null` di §6 valorizzate (`fulfillment_type`, `terms_version`,
   `vat_breakdown`), importi `bigint`, `success_url` https sul dominio del negozio, niente form né
   corpo Stripe nei log.

Scartato o rimandato:
- «Gli ordini di A non visibili con il contesto di B» non si prova a scatola chiusa (nessuna pagina
  ordini ancora): resta nei test di isolamento del repository scritti dal costruttore (regola 1).
- Contenuto di `vat_breakdown` non verificato dai test di accettazione (nessuna pagina lo mostra):
  basta che la colonna `not null` sia valorizzata.
- Il test della piattaforma (404) passa già oggi: tenuto come guardia contro una rotta `/checkout`
  aperta su ogni host.
