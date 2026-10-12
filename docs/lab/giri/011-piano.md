---
giro: 011
traguardo: T5
fase: revisione
inizio: 2026-10-12T09:37:41Z
ricerca: sì
accettazione: cb7b385
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
    `processed_at`), tabella **globale** senza `tenant_id` (§5.1).
  - `customer_order` + `stripe_payment_intent_id text`, `paid_at timestamptz` (nomi di §6).
- **`OrderStatus`** + `PAID`, `EXPIRED`. **`OrderStateMachine`**: `markPaid(order, sessionId,
  paymentIntentId)` e `expire(order)`; transizioni solo da `PENDING_PAYMENT`; da qualunque altro
  stato non fanno nulla (idempotenti, §8.4) e restituiscono «nessun cambio». Ogni cambio scrive un
  `order_event` (`PENDING_PAYMENT → PAID` / `→ EXPIRED`); `paid_at` = `clock.instant()`.
  `markPaid` salva in `stripe_checkout_session_id` la sessione **che ha pagato** (può essere una
  precedente: vedi sotto) e il `payment_intent`.
- **`StripeWebhookResource`** `POST /webhooks/stripe/connect` (package `payment`), solo sul
  **dominio della piattaforma** (Host di un negozio → 404: il webhook vive nel contesto
  `__platform__`). Corpo letto come testo grezzo (la firma è sui byte esatti).
  1. Segreto non configurato → 503, nulla scritto (Stripe ritenta).
  2. Firma con `Webhook.constructEvent(payload, header, secret)` (tolleranza 300 s): header
     mancante, firma sbagliata, timestamp vecchio, corpo alterato → 400, nessuna riga in
     `stripe_event`. **La tolleranza usa l'ora di sistema, non il `Clock` iniettato**: Stripe firma
     con l'ora reale; eccezione alla convenzione «`Clock` ovunque», da scrivere in DECISIONI e in
     un commento. Il test la prova con il `Clock` spostato di un giorno e all'istante della firma.
  3. `insert into stripe_event … on conflict (id) do nothing` (SQL nativo, transazione propria);
     se esiste con `processed_at` valorizzato → 200 e basta.
  4. Negozio da `event.account` → `tenant.stripe_account_id` (query globale), **qualunque sia lo
     stato del negozio** (anche `SUSPENDED`: il pagamento è avvenuto). Account sconosciuto o
     mancante → evento segnato elaborato, 200, nessun cambio. `TenantContext.switchTo(shop)`.
  5. **Lettura dei campi** della sessione (`id`, `status`, `payment_status`, `payment_intent`,
     `client_reference_id`, `metadata.tenant_id`) dal JSON grezzo (`getRawJson()` o
     `deserializeUnsafe()`), **non** da `getObject()`, che è vuoto quando l'`api_version`
     dell'evento è diversa da quella della libreria. Campo con tipo inatteso o JSON illeggibile →
     eccezione → 500, evento non elaborato.
  6. Elaborazione in **`StripeEventProcessor`** (`@Transactional(REQUIRES_NEW)`, altro bean):
     ordine cercato per **`client_reference_id`** (= id dell'ordine, già impostato dal giro 010)
     **nel negozio corrente**, con `PESSIMISTIC_WRITE`; non trovato, oppure
     `metadata.tenant_id` diverso dal negozio dell'account → nessun cambio.
     - `checkout.session.completed` con `status = complete` **e** `payment_status = paid` →
       `markPaid` con il session id dell'evento, **qualunque** sessione dell'ordine sia (anche una
       precedente, sovrascritta da un nuovo tentativo). Se è cambiato stato, **svuota il carrello**
       `cart_id` (righe cancellate, DECISIONI n. 22) con un metodo di `CartService` (vedi
       «Rischi»). Ordine già `PAID` e `payment_intent` diverso → nessun cambio e log `WARN` (id
       ordine, id evento; mai il corpo): è un secondo pagamento da rimborsare a mano.
       `no_payment_required`, `unpaid`, `status ≠ complete` → nessun cambio (log `INFO`).
     - `checkout.session.expired` → `expire` **solo se** `data.object.id` è la sessione corrente
       dell'ordine (`stripe_checkout_session_id`); la scadenza di una sessione precedente non tocca
       l'ordine, che ha un tentativo più recente ancora aperto.
     - Altri tipi → nessun cambio.
     Alla fine `processed_at` = ora, nella stessa transazione.
  7. Eccezione nell'elaborazione → 500, `processed_at` resta null (Stripe rinvia).
  - Log: id e tipo dell'evento, id ordine; mai il corpo.
- **CSRF** (sostituisce DECISIONI n. 18, nuova riga di DECISIONI): in `quarkus-rest-csrf` 3.33.4
  (bytecode di `CsrfRequestResponseReactiveFilter`, verificato) con `require-form-url-encoded=true`
  un `POST` non form senza token prende **400 «wrong media type»**: il webhook JSON di Stripe
  verrebbe rifiutato. La ricerca diceva il contrario (punto 2 della ricerca: errato).
  Scelta: **`quarkus.rest-csrf.require-form-url-encoded=false`** (i `POST` form restano verificati;
  quelli non form saltano il token) **più** un filtro della vetrina (`@ServerRequestFilter` nel
  package `storefront`, dopo `TenantRequestFilter`): su un Host di negozio, `POST`/`PUT`/`PATCH`/
  `DELETE` con tipo diverso da `application/x-www-form-urlencoded` o `multipart/form-data`,
  **o senza `Content-Type`**, → 415 senza toccare nulla. Più `@Consumes(APPLICATION_FORM_URLENCODED)`
  su tutti i `@POST` di `CartResource` e `CheckoutResource`. Motivo del filtro: `@Consumes` da solo
  non copre una richiesta senza `Content-Type` (un `fetch` senza corpo), che con `false` salterebbe
  il token e arriverebbe a `/carrello/righe/{id}/rimuovi`, che non ha campi. Il webhook vive solo
  sul dominio della piattaforma, quindi il filtro non lo tocca. Scartata la rotta Vert.x
  (`@Observes Router`): salta `TenantRequestFilter` e il contesto di richiesta, va rifatto a mano.
  **Per dopo:** i futuri `POST` della piattaforma (area admin) devono avere lo stesso filtro o
  `@Consumes`.
- **Pagina di esito** `GET /ordine/esito?session_id=…` (Host del negozio; piattaforma 404):
  ordine cercato per `stripe_checkout_session_id` **nel negozio corrente**; sconosciuto, vuoto o
  di un altro negozio → 404. Mostra solo numero, stato e totale (niente nome, email, telefono: il
  session id sta nell'URL).
  - `PAID`: «Pagamento ricevuto», «Ordine 2026-0001», «104,80 €», «Riceverai un'email di conferma».
  - `PENDING_PAYMENT`: «Stiamo confermando il pagamento…» e ricarica ogni 2 s con
    `<meta http-equiv="refresh" content="2;url=/ordine/esito?session_id=…&n=k+1">`, dove il session
    id è **quello salvato nell'ordine** (non il parametro ricopiato); fino a `n=15` (30 s); a
    `n ≥ 15` niente ricarica e «Riceverai l'email di conferma appena il pagamento sarà confermato.».
    `n` non numerico o fuori 0–14 → trattato come 15. **Scostamento da §7.5** (htmx è una
    dipendenza nuova): in `docs/scostamenti-4-10.md`.
  - `EXPIRED`: «Il pagamento non è stato completato» e link «Torna al carrello» (`/carrello`).
  - **Sessioni precedenti:** il cliente che ha pagato una sessione precedente torna con quel
    session id; dopo il webhook lo trova, perché `markPaid` lo salva nell'ordine. Prima del webhook
    (o per una sessione precedente non pagata) → 404: caso raro (la sessione precedente è già
    scaduta per Stripe quando nasce la nuova), accettato per la demo.
- `CheckoutService`: invariato; un ordine `PAID`/`EXPIRED` non si riusa mai (cerca solo
  `PENDING_PAYMENT`; lo conferma il test P2).

## Dati del negozio demo (per i test)
Quelli del giro 010 (`Giro010CheckoutData`): «Fioreria Il Glicine» `demo.localhost`,
`acct_1GlicineDemoTest`; «Fiori di Marco» `fiori-di-marco.localhost`, `acct_1MarcoDemoTest`;
carrello 1 × rose 4500 + 2 × orchidea 2990 = **10480** («104,80 €»); Giulia Bianchi; `Clock`
12/10/2026 08:00 UTC; ritiro `2026-10-13`. In più:
- Segreto di firma di test: `whsec_giro011_finto` (inventato), impostato da `Giro011FakeStripe`
  (lo Stripe finto del giro 010 più il segreto).
- Eventi (JSON scritto nel test, firmato con HMAC-SHA256 sull'ora reale, header
  `Stripe-Signature: t=<epoch>,v1=<hex>`), `api_version` = `Stripe.API_VERSION` della libreria
  (`2026-09-30.endive`) salvo il caso `2024-06-20`: `evt_giro011_paid_1` (`completed`, `complete`,
  `paid`, `pi_giro011_demo_1`), `evt_giro011_expired_1`, `evt_giro011_unpaid_*`; `data.object` con
  `client_reference_id` = id ordine e `metadata.tenant_id`/`order_number` come il checkout.
- Seconda sessione dello stesso ordine: `Clock` a 08:32 (sessione 1 scaduta), stesso carrello e
  stessi dati → stesso ordine, `attempt-2`, sessione nuova; pagamento della vecchia con
  `pi_giro011_old`.
- Cambio prezzo: rose da 4500 a **4800** tra due tentativi → nuovo totale **10780**.

## Fatto quando — controlli eseguibili
Test JUnit (commit `cb7b385`): `Giro011WebhookAcceptanceTest` (W),
`Giro011WebhookWithoutSecretAcceptanceTest` (S), `Giro011OrderOutcomeAcceptanceTest` (E),
`Giro011CheckoutPriceChangeAcceptanceTest` (P), `Giro011FormsCsrfAcceptanceTest` (C). Ogni ordine
nasce dal vero `POST /checkout` con lo Stripe finto.
- [ ] (W) `completed`/`paid` firmato su Host `localhost` → 200; ordine `PAID`, `paid_at` = istante
      del `Clock`, `stripe_payment_intent_id = pi_giro011_demo_1`, un `order_event`
      `PENDING_PAYMENT → PAID`; `stripe_event` con tipo, account e `processed_at`; `GET /carrello`
      col cookie del cliente → «Il tuo carrello è vuoto.».
- [ ] (W) Stesso evento rinviato → 200, un solo `order_event` `→ PAID`, una riga `stripe_event`.
- [ ] (W) Stesso evento due volte **in parallelo** (barriera), ripetuto su **10 ordini** → sempre
      due 200 e un solo `order_event` `→ PAID`.
- [ ] (W) Riga `stripe_event` preesistente con `processed_at` null → l'evento si elabora.
- [ ] (W) Secondo `completed`/`paid` con altro `payment_intent` → resta il primo, nessun evento.
- [ ] (W) `complete`+`unpaid`, `complete`+`no_payment_required`, `open`+`paid` → 200, ordine
      `PENDING_PAYMENT`, evento elaborato, carrello pieno.
- [ ] (W) `expired` → `EXPIRED`, `order_event` `PENDING_PAYMENT → EXPIRED`, carrello pieno.
- [ ] (W) Fuori ordine: `expired` dopo `PAID` → `PAID`; `completed` dopo `EXPIRED` → `EXPIRED`;
      nessun `order_event` nuovo.
- [ ] (W) Sessione precedente: `completed` della sessione 1 dopo la sessione 2 → `PAID`,
      `pi_giro011_old`, `stripe_checkout_session_id` = sessione 1; `expired` della sessione 1 →
      ordine invariato; `expired` della sessione 2 → `EXPIRED`.
- [ ] (W) `api_version: 2024-06-20` → `PAID`. `payment_status` con tipo sbagliato → 500, evento
      non elaborato, ordine invariato.
- [ ] (W) Firma: senza header, segreto sbagliato, `t` di 10 minuti fa (anche con il `Clock`
      all'istante della firma), corpo alterato → 400, zero righe `stripe_event`; firma fresca con
      `Clock` un giorno avanti → 200 `PAID`.
- [ ] (W) CSRF: `POST` JSON firmato senza cookie né token → 200.
- [ ] (W) Isolamento: account di Marco, account sconosciuto, account assente, `metadata.tenant_id`
      di Marco con l'account del demo → 200, ordine del demo invariato; webhook su Host
      `demo.localhost` e `fiori-di-marco.localhost` → 404 e nessuna riga; controllo positivo:
      l'ordine di Marco con l'account di Marco → `PAID`.
- [ ] (W) Negozio `SUSPENDED` → il pagamento si registra comunque (`PAID`).
- [ ] (S) Senza segreto → 503, zero righe `stripe_event`.
- [ ] (E) Dopo `PAID` → «Pagamento ricevuto», «2026-0001», «104,80 €», «Riceverai un'email di
      conferma», nessuna ricarica; niente nome, email, telefono.
- [ ] (E) `PENDING_PAYMENT` → «Stiamo confermando il pagamento», ricarica verso `n=1`; `n=3` →
      `n=4`; `n=14` → `n=15`; `n=15`, `abc`, `16`, `-1`, `999999999999` → niente ricarica,
      «Riceverai l'email di conferma».
- [ ] (E) Dopo `EXPIRED` → «Il pagamento non è stato completato», link «Torna al carrello».
- [ ] (E) Sessione precedente pagata → la pagina con quel session id dice «Pagamento ricevuto».
- [ ] (E) Session id inesistente, vuoto o assente → 404; del demo su Host di Marco → 404; Host
      della piattaforma → 404.
- [ ] (P) Rose a 4800 tra due tentativi → ordine nuovo `2026-0002`, 10780, rose 4800 a Stripe,
      chiave `order-<nuovo id>-attempt-1`. *Passa già: guardia.*
- [ ] (P) Dopo `PAID`, carrello di nuovo pieno e `POST /checkout` → ordine nuovo `2026-0002`.
- [ ] (C) Senza token, `POST` JSON, `text/plain` o **senza `Content-Type`** su `/carrello/aggiungi`,
      `/carrello/righe/{id}`, `/carrello/righe/{id}/rimuovi`, `/checkout` → 4xx, carrello e
      ordini invariati, Stripe non chiamato. *Passano già: guardie per il cambio di
      `require-form-url-encoded`.* Il test CSRF del giro 010 (form senza token → 400) resta verde.
- [ ] Test del costruttore: isolamento del repository per la ricerca per session id e per
      `client_reference_id` (regola 1); unità di `OrderStateMachine`; **stallo** webhook/checkout
      (vedi «Rischi»): checkout che tiene il blocco del carrello fermo su un latch mentre arriva il
      webhook dello stesso ordine → entrambi finiscono, nessun 500.
- [ ] `SchemaRulesTest` e `FlywaySafetyTest` verdi con `V6`.
- [ ] `./mvnw -B -ntp verify` verde; `python3 -I scripts/guard/pr_guard.py --base origin/main --head HEAD` verde.

## Fuori perimetro
- Prova in produzione con chiavi Stripe test vere e Stripe CLI (giro 012). Stripe live: mai.
- `account.updated`, `charge.refunded`, `charge.dispute.created`, `async_payment_*`: non servono
  a T5. Rimborso automatico del secondo pagamento: solo log `WARN`.
- Scadenza esplicita (`expire` via API) della sessione precedente: in «Idee per dopo».
- «Riprova il pagamento» dalla pagina di annullamento (§7.5 punto 6): `cancel_url` torna a
  `/checkout`, basta per la demo.
- Email di conferma, outbox e pagina di stato dell'ordine con `public_token`: T6.
- htmx (dipendenza nuova): la ricarica usa `meta refresh`.
- Fasce e capacità, `over_capacity`: T7 / fuori. Endpoint webhook della piattaforma.
- Da `traguardi.json`: back-office del negozio (piano B: email e dashboard Stripe); tipo
  `CEMETERY`; capacità per fascia; impianto consensi e GDPR oltre la pagina privacy; SPF e DKIM
  completi.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|
| `Giro011WebhookAcceptanceTest` | dato un ordine `PENDING_PAYMENT` creato dal checkout, quando arriva un evento firmato (doppio, in parallelo, fuori ordine, di una sessione precedente, con altra `api_version`, illeggibile, non firmato, di un altro account), allora `PAID`/`EXPIRED` una sola volta, carrello svuotato al `PAID`, 400 sulle firme cattive, 500 se illeggibile, niente CSRF |
| `Giro011WebhookWithoutSecretAcceptanceTest` | dato nessun segreto, quando arriva un evento, allora 503 e nessuna riga |
| `Giro011OrderOutcomeAcceptanceTest` | dato un ordine pagato, in attesa o scaduto, quando il cliente torna su `/ordine/esito`, allora vede il messaggio giusto senza dati personali; 404 fuori dal negozio |
| `Giro011CheckoutPriceChangeAcceptanceTest` | dato un primo tentativo, quando cambia il prezzo o l'ordine è pagato, allora ordine nuovo |
| `Giro011FormsCsrfAcceptanceTest` | dato un carrello, quando arriva un `POST` senza token e non form, allora 4xx e nulla cambia |
| `Giro011Webhooks`, `Giro011Data`, `Giro011FakeStripe` | firma HMAC-SHA256 (`javax.crypto`), eventi JSON, letture JDBC, segreto di test |

## Porte
- Doppio senso: `V6__stripe_events.sql` (solo aggiunte); percorsi `/webhooks/stripe/connect` e
  `/ordine/esito`; `require-form-url-encoded=false` con filtro 415 della vetrina (si torna indietro
  con una riga); 503 senza segreto; `meta refresh` al posto di htmx (scostamento registrato);
  carrello svuotato al `PAID`; account sconosciuto → 200 elaborato; firma sull'ora di sistema.
- Senso unico: nessuna (stripe-java già approvata, DECISIONI n. 21; niente htmx; nessuna
  dipendenza nuova: `javax.crypto` e `com.stripe.Stripe` nei test sono già sul classpath).

## Rischi di concorrenza
- Evento duplicato in parallelo: entrambe le richieste trovano la riga con `processed_at` null;
  il `select … for update` sull'ordine le mette in fila e la seconda vede `PAID` → nessun cambio.
  Provato dal test W su 10 coppie.
- `completed` ed `expired` dello stesso ordine in corsa: stesso blocco; vince il primo.
- **Webhook e `POST /checkout` sullo stesso carrello.** `prepare` blocca **prima il carrello, poi
  l'ordine** (`lastPending`). Il webhook **non blocca il carrello**: blocca solo l'ordine e poi
  cancella le righe con un metodo di `CartService` (`deleteItems(cartId)`, un `delete` di
  `cart_item`, senza `PESSIMISTIC_WRITE` sul `Cart`). Così non c'è un ordine di blocco inverso:
  il webhook al più aspetta l'ordine che il checkout tiene, mai il contrario mentre tiene qualcosa
  che il checkout aspetta. Test del costruttore con latch (vedi «Fatto quando»).
- Hibernate fissa il negozio all'apertura della sessione (§5.2): registrazione dell'evento come
  piattaforma, elaborazione in `REQUIRES_NEW` su un altro bean dopo `switchTo`. Provato dai test W.

## Budget
Righe di produzione ≤ 450; file ≤ 14 (migrazione, 2 enum/servizi toccati, `CartService`, risorsa
webhook, processore, filtro della vetrina, 2 risorse con `@Consumes`, risorsa e template di
esito, configurazione, scostamenti, DECISIONI). Una sessione.

## Domande per il ricercatore (al massimo 3)
1. `quarkus-rest-csrf` (Quarkus 3.33): come si esclude un solo percorso o un `POST` JSON?
2. `stripe-java` 34.0.0: `constructEvent` e `getObject()` con `api_version` diversa.
3. Stripe: `completed` dopo `expired`, `no_payment_required`, ritentativi Connect.
(Risposte in `011-ricerca.md`; la 1 corretta dal critico, vedi sotto.)

## Idee per dopo
- `expire` via API della sessione vecchia quando nasce un nuovo tentativo (giro 012 o dopo).
- Rimborso guidato del secondo pagamento (oggi solo `WARN`).
- `success_url` con un riferimento all'ordine oltre al session id, per trovare l'ordine anche
  prima del webhook su una sessione precedente.
- htmx 2 con file locale (dipendenza nuova, da chiedere) per la pagina di esito come §7.5.
- `account.updated` per `stripe_charges_enabled` (con l'onboarding §8.2).

## Critica ricevuta e cosa cambia
| Punto | Esito | Cosa cambia |
|---|---|---|
| 1. CSRF: con `require-form-url-encoded=true` il JSON prende 400 | **Accolto**, verificato sul bytecode di 3.33.4 | Opzione (a) con un'aggiunta: `false` + `@Consumes` + filtro 415 della vetrina, perché `@Consumes` non copre la richiesta senza `Content-Type` (che con `false` salta il token). Test C, con il caso senza `Content-Type`. Nuova riga DECISIONI che sostituisce la n. 18; correzione in coda a `011-ricerca.md`. Scartata (b) rotta Vert.x: salta `TenantRequestFilter` e il contesto di richiesta. |
| 2. Ricerca per session id perde i pagamenti di sessioni precedenti | **Accolto** | Ricerca per `client_reference_id` nel negozio, controllo `metadata.tenant_id`; `markPaid` con qualunque sessione e salvataggio della sessione pagante; `expire` solo per la sessione corrente; `WARN` sul secondo pagamento. Test W e E sulle sessioni precedenti. Esito: la pagina trova la sessione precedente dopo il webhook; prima → 404 (accettato, idea per dopo). |
| 3. `api_version` diversa → `getObject()` vuoto | **Accolto** | Campi dal JSON grezzo; errore di lettura → 500. Test con `2024-06-20` e con un campo di tipo sbagliato. |
| 4. Stallo carrello/ordine | **Accolto** | Il webhook non blocca il carrello: `delete` delle righe via `CartService`. Test del costruttore con latch. Tolta dal piano la frase «il costruttore blocca anche il carrello dopo l'ordine». |
| Parallelo ripetuto 10 coppie | Accolto | Test W su 10 ordini, barriera per coppia. |
| Firma con l'ora di sistema | Accolto | Scritto come eccezione al `Clock`; due test (Clock avanti di un giorno; Clock all'istante di una firma vecchia). |
| PAID solo con `complete` e `paid` | Accolto | Test con `open`+`paid` e `no_payment_required`. |
| Negozio non `ACTIVE` elabora comunque | Accolto | Test con `SUSPENDED`. |
| Meta refresh con il session id salvato | Accolto | Nel piano; non distinguibile a scatola chiusa (l'id deve coincidere), nessun test dedicato. |
