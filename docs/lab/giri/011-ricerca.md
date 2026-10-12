# Ricerca del giro 011 — webhook Stripe, CSRF, api_version

Consultata il 2026-10-12. Letture usate: 8 su 8. Fonti: 5 (+1 senza utilità).

## Casi reali e documentazione
1. **CSRF.** Opzioni `quarkus.rest-csrf.*`: `enabled`, `form-field-name`, `token-header-name`, `cookie-*`, `create-token-path`, `token-size`, `token-signature-key`, `verify-token` (default true), `require-form-url-encoded` (default true). Nessuna lista di esclusione per percorso. [Quarkus CSRF]
2. Con `require-form-url-encoded=true` il controllo gira solo su `application/x-www-form-urlencoded` e `multipart/form-data`: un POST JSON (come il webhook) non è verificato già con il default. Con `false` si salterebbero anche altri tipi. [Quarkus CSRF]
3. **stripe-java.** `Webhook.constructEvent(payload, sigHeader, secret[, tolerance[, clock]])`, tolleranza di default 300 s. Lancia `SignatureVerificationException` (timestamp/firma/segreto mancante o errato, fuori tolleranza), `JsonSyntaxException`, `IllegalArgumentException` per eventi v2. Non controlla `api_version`. [Webhook.java] Stripe: tolleranza 5 minuti, non usare 0. [Stripe webhooks]
4. `EventDataObjectDeserializer.getObject()` → `Optional<StripeObject>`, vuoto se `api_version` dell'evento ≠ `Stripe.API_VERSION` o parsing fallito. `deserializeUnsafe()` salta il controllo e può lanciare `EventDataObjectDeserializationException`; esistono `deserializeUnsafeWith` e `getRawJson()`. [EventDataObjectDeserializer.java]
5. **Stripe.** `payment_status`: `paid`, `unpaid`, `no_payment_required`. `status`: `open`, `complete`, `expired` («nessuna ulteriore elaborazione»). `complete` può avere pagamento ancora in corso. Retry fino a 3 giorni in live (3 tentativi in sandbox); 2xx chiude, 3xx/4xx/5xx/timeout falliscono; ordine non garantito, duplicati possibili: si deduplica per `event.id`. [Checkout Session object] [Stripe webhooks]

## Cosa prendiamo
- CSRF: nessuna esclusione per percorso; webhook = POST JSON, non verificato con il default. Test: POST JSON firmato senza token CSRF. Non impostare `false`.
- `constructEvent` con tolleranza 300 s sul corpo grezzo. Firma/JSON errati → 400; errore transitorio → 500 (Stripe ritenta).
- Deserializzazione: `getObject()`, se vuoto `deserializeUnsafe()` o campi da `getRawJson()`. Negli eventi di test usare la costante `Stripe.API_VERSION`.
- Pagato solo con `status=complete` e `payment_status=paid`; `unpaid` resta `PENDING_PAYMENT`; `no_payment_required` si logga.
- Idempotenza su `event.id`; non dipendere dall'ordine; il negozio si ricava da `account`.

## Cosa evitiamo
`require-form-url-encoded=false`, `verify-token=false`, tolleranza 0; `getObject()` senza il caso vuoto; contare sull'ordine degli eventi.

## Idee per dopo
Pin dell'api_version dell'endpoint a `Stripe.API_VERSION`; job di riconciliazione delle sessioni `PENDING_PAYMENT` vecchie; rotazione del segreto.

## Non verificato
- Valore di `Stripe.API_VERSION` in 34.0.0 (letto solo `master`).
- Se `completed` può seguire `expired`: nessuna pagina lo dice; il piano è prudente (transizioni solo da `PENDING_PAYMENT`, ordine bloccato) e va coperto da test.
- Retry specifici dei webhook Connect (pagina dedicata non aperta).
- Pagina Quarkus riassunta dallo strumento; il comportamento con Content-Type JSON va confermato con un test.

## Fonti
- [Quarkus CSRF] https://quarkus.io/guides/security-csrf-prevention
- [Stripe webhooks] https://docs.stripe.com/webhooks
- [Checkout Session object] https://docs.stripe.com/api/checkout/sessions/object
- [Webhook.java] https://raw.githubusercontent.com/stripe/stripe-java/master/src/main/java/com/stripe/net/Webhook.java
- [EventDataObjectDeserializer.java] https://raw.githubusercontent.com/stripe/stripe-java/master/src/main/java/com/stripe/model/EventDataObjectDeserializer.java
