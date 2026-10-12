---
giro: 011
traguardo: T5
fase: critica
inizio: 2026-10-12T09:37:41Z
ricerca: sì
accettazione:
ripreso: no
---
# Piano del giro 011 — webhook Stripe firmato e idempotente, `PAID`/`EXPIRED`, pagina di esito (T5, parte 2)

## Fetta e traguardo
T5 parte 2: il webhook `/webhooks/stripe/connect` segna l'ordine `PAID` (o `EXPIRED`) e il cliente,
al ritorno da Stripe, vede l'esito. È la metà mancante del «Fatto quando» di T5; il giro 010 ha
già ordine e sessione. **Resta per T5:** giro 012, prova sul demo in produzione con Stripe test
(chiavi, account collegato e endpoint webhook creati dall'umano).

## Cosa si costruisce
- **`V6__stripe_events.sql`** (solo aggiunte):
  - `stripe_event` come SPEC §6 (`id text primary key`, `type`, `account`, `received_at`,
    `processed_at`), tabella **globale** senza `tenant_id` (§5.1): entra nell'elenco delle
    globali di `SchemaRulesTest` solo se la regola lo chiede (non ha `tenant_id`, quindi non
    dovrebbe servire).
  - `customer_order` + `stripe_payment_intent_id text`, `paid_at timestamptz` (nomi di §6).
- **`OrderStatus`** + `PAID`, `EXPIRED`. **`OrderStateMachine`**: `markPaid(order, paymentIntentId)`
  e `expire(order)`; transizioni solo da `PENDING_PAYMENT`; da qualunque altro stato non fanno
  nulla (idempotenti, §8.4) e restituiscono «nessun cambio». Ogni cambio scrive un `order_event`
  (`PENDING_PAYMENT → PAID` / `→ EXPIRED`); `paid_at` = `clock.instant()`.
- **`StripeWebhookResource`** `POST /webhooks/stripe/connect` (package `payment`), solo sul
  **dominio della piattaforma** (Host di un negozio → 404, come §5.2: il webhook vive nel contesto
  `__platform__`). Corpo letto come testo grezzo (la firma è sui byte esatti).
  1. Segreto non configurato → 503, nulla scritto (Stripe ritenta).
  2. Firma con `Webhook.constructEvent(payload, header, secret)` (tolleranza 300 s, predefinita):
     header mancante, firma sbagliata o timestamp vecchio → 400, nessuna riga in `stripe_event`.
  3. `insert into stripe_event … on conflict (id) do nothing` (SQL nativo, transazione propria);
     se esiste con `processed_at` valorizzato → 200 e basta.
  4. Negozio da `event.account` → `tenant.stripe_account_id` (query globale); account sconosciuto
     o mancante → evento segnato elaborato, 200, nessun cambio. `TenantContext.switchTo(shop)`.
  5. Elaborazione in **`StripeEventProcessor`** (`@Transactional(REQUIRES_NEW)`, altro bean):
     ordine cercato per `stripe_checkout_session_id` = `data.object.id` nel negozio, con
     `PESSIMISTIC_WRITE`; non trovato → nessun cambio.
     - `checkout.session.completed` con `payment_status = paid` → `markPaid`, salva il
       `payment_intent`; se è cambiato stato, **svuota il carrello** `cart_id` (righe cancellate,
       DECISIONI n. 22). Con `payment_status` diverso da `paid` → nessun cambio.
     - `checkout.session.expired` → `expire`.
     - Altri tipi → nessun cambio.
     Alla fine `processed_at` = ora, nella stessa transazione.
  6. Eccezione nell'elaborazione → 500, `processed_at` resta null (Stripe rinvia).
  - Log: id e tipo dell'evento, id ordine; mai il corpo.
- **Esclusione CSRF** (DECISIONI n. 18): il `POST` JSON del webhook non deve prendere 400 dal
  filtro di `quarkus-rest-csrf`. Meccanismo dalla ricerca (domanda 1); i form della vetrina restano
  protetti (lo prova il test CSRF del giro 010, che resta verde).
- **Pagina di esito** `GET /ordine/esito?session_id=…` (Host del negozio; piattaforma 404):
  ordine cercato per session id **nel negozio corrente**; sconosciuto o di un altro negozio → 404.
  Mostra solo numero, stato e totale (niente nome, email, telefono: il session id sta nell'URL).
  - `PAID`: «Pagamento ricevuto», «Ordine 2026-0001», «104,80 €», «Riceverai un'email di conferma».
  - `PENDING_PAYMENT`: «Stiamo confermando il pagamento…» e ricarica automatica ogni 2 s con
    `<meta http-equiv="refresh" content="2;url=/ordine/esito?session_id=…&n=k+1">`, fino a `n=15`
    (30 s); a `n ≥ 15` niente ricarica e il testo «Riceverai l'email di conferma appena il
    pagamento sarà confermato.». `n` non numerico o fuori 0–15 → trattato come 15.
    **Scostamento da §7.5** (htmx non è ancora nel progetto: aggiungerlo è una dipendenza nuova):
    in `docs/scostamenti-4-10.md`.
  - `EXPIRED`: «Il pagamento non è stato completato» e link «Torna al carrello» (`/carrello`).
- `CheckoutService`: invariato, salvo che un ordine `PAID`/`EXPIRED` non si riusa mai (oggi cerca
  solo `PENDING_PAYMENT`: da confermare con il test R2 sotto).

## Dati del negozio demo (per i test)
Quelli del giro 010 (`Giro010CheckoutData`): «Fioreria Il Glicine» `demo.localhost`,
`acct_1GlicineDemoTest`; «Fiori di Marco» `fiori-di-marco.localhost`, `acct_1MarcoDemoTest`;
carrello 1 × rose 4500 + 2 × orchidea 2990 = **10480** («104,80 €»); Giulia Bianchi; `Clock`
12/10/2026 08:00 UTC; ritiro `2026-10-13`. In più:
- Segreto di firma di test: `whsec_giro011_finto` (inventato), impostato con `@WithTestResource`
  insieme allo Stripe finto del giro 010.
- Eventi (JSON scritto nel test, firmato con HMAC-SHA256 sul tempo reale, header
  `Stripe-Signature: t=<epoch>,v1=<hex>`): `evt_giro011_paid_1` (`checkout.session.completed`,
  `payment_status: paid`, `payment_intent: pi_giro011_demo_1`), `evt_giro011_expired_1`
  (`checkout.session.expired`), `evt_giro011_unpaid_1` (`payment_status: unpaid`);
  `account` = `acct_1GlicineDemoTest`, `data.object.id` = session id salvato dal checkout.
- Cambio prezzo (dubbio 1 del revisore 010): rose da 4500 a **4800** tra due tentativi → nuovo
  totale **10780** («107,80 €»).

## Fatto quando — controlli eseguibili
Test JUnit in `Giro011WebhookAcceptanceTest` (W), `Giro011OrderOutcomeAcceptanceTest` (E),
`Giro011CheckoutPriceChangeAcceptanceTest` (P). Ogni ordine nasce dal vero `POST /checkout` con lo
Stripe finto.
- [ ] (W) `completed`/`paid` firmato su Host `localhost` → 200; ordine `PAID`, `paid_at` = istante
      del `Clock`, `stripe_payment_intent_id = pi_giro011_demo_1`, un `order_event`
      `PENDING_PAYMENT → PAID`; `stripe_event` con `processed_at` valorizzato; `GET /carrello` col
      cookie del cliente → «Il tuo carrello è vuoto.».
- [ ] (W) Stesso evento rinviato → 200, sempre un solo `order_event` `→ PAID`, una riga
      `stripe_event`.
- [ ] (W) Stesso evento inviato due volte **in parallelo** → due 200, un solo `order_event` `→ PAID`.
- [ ] (W) Riga `stripe_event` preesistente con `processed_at` null (elaborazione fallita prima) →
      l'evento si elabora: ordine `PAID`, `processed_at` valorizzato.
- [ ] (W) `expired` → ordine `EXPIRED`, `order_event` `PENDING_PAYMENT → EXPIRED`, carrello pieno.
- [ ] (W) Eventi fuori ordine: `expired` dopo `PAID` → resta `PAID`; `completed`
      dopo `EXPIRED` → resta `EXPIRED`; in entrambi i casi 200 e nessun `order_event` nuovo.
- [ ] (W) `completed` con `payment_status: unpaid` → 200, ordine `PENDING_PAYMENT`.
- [ ] (W) Firma: senza header, con segreto sbagliato, con `t` di 10 minuti fa, con corpo alterato
      dopo la firma → 400, zero righe in `stripe_event`, ordine invariato.
- [ ] (W) CSRF: `POST` JSON firmato **senza** cookie né campo CSRF → 200 (non 400).
- [ ] (W) Isolamento: evento con `account = acct_1MarcoDemoTest` e il session id dell'ordine del
      demo → 200, ordine del demo `PENDING_PAYMENT`; account sconosciuto `acct_1Sconosciuto` →
      200, nessun cambio; lo stesso evento valido inviato con Host `demo.localhost` → 404.
- [ ] (E) Dopo `PAID`: `GET /ordine/esito?session_id=<id>` su `demo.localhost` → 200 con
      «Pagamento ricevuto», «2026-0001», «104,80 €»; senza «Giulia», senza l'email.
- [ ] (E) Ordine ancora `PENDING_PAYMENT` → 200, «Stiamo confermando il pagamento», meta refresh
      verso `n=1`; con `n=15` → niente meta refresh, «Riceverai l'email di conferma»; con
      `n=abc` → come 15.
- [ ] (E) Dopo `EXPIRED` → «Il pagamento non è stato completato», link a `/carrello`.
- [ ] (E) `session_id` inesistente → 404; session id del demo con Host di Marco → 404; senza
      `session_id` → 404; Host della piattaforma → 404.
- [ ] (P) Primo tentativo (10480), poi rose a 4800 nel DB, stesso carrello e stessi dati →
      ordine nuovo `2026-0002`, totale 10780, a Stripe rose 4800 con chiave
      `order-<nuovo id>-attempt-1`. *Probabilmente passa già: resta come guardia del dubbio 1.*
- [ ] (P) Dopo `PAID` del primo ordine, nuovo carrello e `POST /checkout` → ordine nuovo
      `2026-0002`, l'ordine pagato non si riusa.
- [ ] Test d'isolamento del repository per la ricerca per session id (regola 1) e test di unità di
      `OrderStateMachine`, scritti dal costruttore.
- [ ] `SchemaRulesTest` e `FlywaySafetyTest` verdi con `V6`.
- [ ] `./mvnw -B -ntp verify` verde; `python3 -I scripts/guard/pr_guard.py --base origin/main --head HEAD` verde.

## Fuori perimetro
- Prova in produzione con chiavi Stripe test vere e Stripe CLI (giro 012). Stripe live: mai.
- `account.updated` (stato di abilitazione del negozio), `charge.refunded`, `charge.dispute.created`,
  `async_payment_*`: non servono a T5.
- Scadenza esplicita (`expire` via API) della sessione precedente quando il carrello cambia o dopo
  un timeout di rete: con Stripe la sessione scade da sola in 31 min e il webhook `expired` la
  chiude; resta in «Idee per dopo».
- «Riprova il pagamento» dalla pagina di annullamento (§7.5 punto 6): oggi `cancel_url` torna a
  `/checkout`, che crea o riusa l'ordine: basta per la demo.
- Email di conferma, outbox e pagina di stato dell'ordine con `public_token`: T6.
- htmx (dipendenza nuova): la ricarica usa `meta refresh`.
- Fasce e capacità, `over_capacity`: T7 / fuori. Endpoint webhook della piattaforma (R3).
- Da `traguardi.json`: back-office del negozio (piano B: email e dashboard Stripe); tipo
  `CEMETERY`; capacità per fascia; impianto consensi e GDPR oltre la pagina privacy; SPF e DKIM
  completi.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|
| `Giro011WebhookAcceptanceTest` | dato un ordine `PENDING_PAYMENT` creato dal checkout, quando arriva un evento firmato (anche doppio, in parallelo, fuori ordine, non firmato, di un altro account), allora `PAID`/`EXPIRED` una sola volta, carrello svuotato al `PAID`, 400 sulle firme cattive, niente CSRF |
| `Giro011OrderOutcomeAcceptanceTest` | dato un ordine pagato, in attesa o scaduto, quando il cliente torna su `/ordine/esito`, allora vede il messaggio giusto senza dati personali; 404 fuori dal negozio |
| `Giro011CheckoutPriceChangeAcceptanceTest` | dato un primo tentativo, quando cambia il prezzo o l'ordine è pagato, allora ordine nuovo con il prezzo nuovo |
| `Giro011Webhooks` | firma HMAC-SHA256 nel test (`javax.crypto`), costruzione degli eventi JSON |

## Porte
- Doppio senso previste: `V6__stripe_events.sql` (solo aggiunte); percorsi
  `/webhooks/stripe/connect` e `/ordine/esito`; esclusione CSRF limitata al webhook; 503 senza
  segreto; `meta refresh` al posto di htmx (scostamento da §7.5 registrato); carrello svuotato al
  `PAID`; account sconosciuto → 200 elaborato.
- Senso unico: nessuna (stripe-java già approvata, DECISIONI n. 21; niente htmx).

## Rischi di concorrenza
- Evento duplicato in parallelo: entrambe le richieste trovano la riga con `processed_at` null;
  il `select … for update` sull'ordine le mette in fila e la seconda vede `PAID` → nessun cambio.
  Provato dal test W in parallelo.
- `completed` ed `expired` dello stesso ordine in corsa: lo stesso blocco; vince il primo, il
  secondo non fa nulla (transizioni solo da `PENDING_PAYMENT`).
- Webhook e `POST /checkout` sullo stesso carrello: il checkout blocca il carrello, il webhook
  blocca l'ordine e poi cancella le righe del carrello. Ordine di blocco diverso → rischio di
  stallo raro: il costruttore blocca anche il carrello (`PESSIMISTIC_WRITE`) **dopo** l'ordine e il
  checkout non tocca ordini `PAID`; un eventuale stallo dà 500 e Stripe ritenta.
- Hibernate fissa il negozio all'apertura della sessione (§5.2): registrazione dell'evento come
  piattaforma, elaborazione in `REQUIRES_NEW` su un altro bean dopo `switchTo`. Provato dai test W.

## Budget
Righe di produzione ≤ 400; file ≤ 12 (migrazione, 2 enum/servizi toccati, risorsa webhook,
processore, risorsa e template di esito, configurazione, scostamenti). Una sessione.

## Domande per il ricercatore (al massimo 3)
1. `quarkus-rest-csrf` (Quarkus 3.33): come si esclude un solo percorso o un `POST` JSON dalla
   verifica? `require-form-url-encoded=false` basta e cosa cambia per i form? C'è un'opzione per
   percorso?
2. `stripe-java` 34.0.0: `Webhook.constructEvent` (tolleranza, eccezioni) e deserializzazione di
   `data.object` in `Session` quando `api_version` dell'evento è diversa da quella della libreria
   (`getObject()` vuoto? `deserializeUnsafe()`?). Quale `api_version` mettere negli eventi di test.
3. Stripe: un `checkout.session.completed` può arrivare per una sessione già `expired`, o
   `payment_status` può essere `no_payment_required` nel nostro caso? Ordine e ritentativi dei
   webhook Connect (durata, risposte che fermano i rinvii).

## Idee per dopo
- `expire` via API della sessione vecchia quando il carrello cambia (giro 012 o dopo).
- htmx 2 con file locale (dipendenza nuova, da chiedere) per la pagina di esito come §7.5.
- `account.updated` per `stripe_charges_enabled` (con l'onboarding §8.2).

## Critica ricevuta e cosa cambia
(dopo il critico)
