# DECISIONI — tre livelli (METODO §3)

- **registro:** dettagli interni reversibili; nessuna ratifica.
- **ratifica:** cosa vede o paga il cliente, dati salvati, costi ricorrenti, scelte care da cambiare.
  Massimo 3 per giro. Per contestarne una, commenta la PR del giro: `contesto n. X: …`.
- **senso unico:** il giro si è fermato con una PR `domanda`; qui va la risposta dell'umano.

Stato: `—` (in vigore) · `contestata` · `cambiata (giro NNN)` · `risposta del GG/MM`.

| N. | Data | Giro | Livello | Decisione | Alternativa scartata | Motivo (una riga) | Stato |
|---|---|---|---|---|---|---|---|
| 1 | 11/10 | 001 | registro | `infra/lib.sh` condiviso da deploy.sh e rollback.sh | codice duplicato | evita di copiare chiave SSH e known_hosts | — |
| 2 | 11/10 | 001 | registro | Immagini fissate: caddy:2.11.7, postgres:17.11; utente DB fisso `platform` | tag larghi, DB_USER variabile | meno stato da ripristinare | — |
| 3 | 11/10 | 001 | registro | Rollback ripristina solo se l'ultimo deploy era riuscito (`last-good`) | salvare sempre lo stato precedente | non salvare come «precedente» uno stato rotto | — |
| 4 | 11/10 | 001 | registro | Token GHCR solo su stdin di ssh verso `remote.sh`; `docker logout` nel trap | token in riga di comando | il token non compare in `ps` | — |
| 5 | 11/10 | 001 | ratifica | Dopo ogni deploy 503 per alcuni secondi (health check di Caddy, fino a ~30 s) | health_interval corto | accettabile per la demo; nel runbook | — |
| 6 | 11/10 | 002 | registro | `passato_il` di T1 = fine del passo «Deploy and smoke test» (15:25:09Z) | ora di esecuzione di traguardo.py | il criterio chiede lo smoke verde | — |
| 7 | 11/10 | 003 | senso unico | Dipendenza E2E: Playwright Java 1.63.0, scope `test`, solo Chromium | Quarkiverse; Selenium + Testcontainers | risposta umana: «Procedi con A» (da Gerio: sì) | risposta del 11/10 |
| 8 | 11/10 | 005 | ratifica | Il negozio demo «Fioreria Il Glicine» (dati inventati) si carica da solo all'avvio in dev e prod (`app.demo.seed`) | migrazione ripetibile `R__` in `db/demo` | non tocca `flyway.locations` né i controlli sulle migrazioni; si toglie senza «missing migration» | — |
| 9 | 11/10 | 005 | ratifica | Scheda prodotto: «IVA inclusa», avviso di stagione, recesso escluso per i deperibili (senza citare l'articolo), prezzi `25,00 €` e «da 25,00 €» | citare art. 59 lett. d | la lettera non è verificata su Normattiva; formula completa in T8 | — |
| 10 | 11/10 | 005 | registro | `product_variant.price_cents` è `bigint` (SPEC §6 dice `int`) | `int` | CLAUDE.md chiede `long`; Hibernate rifiuta long su int4 | — |
| 11 | 11/10 | 005 | registro | Entità e `CatalogService` nel package `catalog`, `CatalogResource` in `storefront`, seme in `demo` | tutto in `storefront` | separa dati e vetrina (§4.3) | — |
| 12 | 11/10 | 005 | registro | Visibilità (prodotto attivo, categoria visibile, ≥1 variante attiva) nel servizio; altrimenti 404 | logica nella risorsa | niente logica nelle risorse | — |
| 13 | 11/10 | 005 | registro | `Prices.format` scritto a mano con U+00A0 | `NumberFormat` | l'uscita non dipende dai dati locale del JDK | — |
| 14 | 11/10 | 006 | registro | Il Caddyfile serve due nomi espliciti (`base, demo.base`) in un solo blocco, senza on-demand TLS | on-demand TLS di App. B.3; snippet | un solo negozio pubblico; si toglie con un commit | — |
| 15 | 11/10 | 008 | senso unico | Dipendenza `io.quarkus:quarkus-rest-csrf` (versione dal BOM) per i form della vetrina | nessuna dipendenza (Origin + SameSite); token fatto a mano | risposta umana: «Procedi con A» (da Gerio: sì) | risposta del 11/10 |
| 16 | 11/10 | 008 | registro | `CartService.add` blocca il `Cart` con `PESSIMISTIC_WRITE`, niente SQL nativo | upsert `on conflict` | resta il filtro per negozio, `Clock`/audit e `version` (critica) | — |
| 17 | 11/10 | 008 | ratifica | Token del carrello sempre generato dal server; token ignoto o di un altro negozio ignorato e cookie nuovo | adottare il token ricevuto | evita fixation e violazione di `unique` | — |
| 18 | 11/10 | 008 | ratifica | Il filtro CSRF dà 400 a ogni POST/PUT non form senza token: il webhook Stripe di T5 richiede un'esclusione | disattivare `require-form-url-encoded` | da decidere in T5 [DA VERIFICARE] | cambiata (giro 011, n. 26) |
| 19 | 11/10 | 008 | registro | `EndpointIsolationTest` e `ProbeItemResource` passano a POST form con token CSRF | disattivare CSRF in test | il test prova l'isolamento, non il CSRF | — |
| 20 | 11/10 | 008 | registro | `CartService` restituisce un esito (DONE/NOT_FOUND/BAD_REQUEST), la risorsa lo traduce in 303/404/400 | eccezioni JAX-RS nel servizio | niente HTTP nel servizio | — |
| 21 | 12/10 | 010 | senso unico | Dipendenza `com.stripe:stripe-java` 34.0.0 per Checkout e firma webhook (SPEC §8.3) | HTTP + HMAC a mano | risposta umana: «Procedi con Opzione A» (da Gerio: sì) | risposta del 12/10 |
| 22 | 12/10 | 010 | ratifica | Il carrello non si svuota al checkout (si svuota al pagamento, giro 011); se il carrello o i dati cambiano nasce un ordine nuovo, altrimenti si riusa lo stesso | modificare l'ordine esistente (`attempt+1`) | l'ordine resta immutabile dopo la creazione; l'ordine abbandonato scade con Stripe | — |
| 23 | 12/10 | 010 | ratifica | Se la chiave Stripe manca o `stripe_charges_enabled` è falso: riepilogo senza «Ordina e paga», con telefono ed email del negozio, `POST` 409 | nascondere la pagina | SPEC §8.2: la vetrina mostra i contatti | — |
| 24 | 12/10 | 010 | registro | `StripeCheckout` costruisce il `StripeClient` all'avvio solo se c'è la chiave (nessun produttore CDI, nessuno stato globale); `checkout_expires_at` null segna il tentativo fallito | `@Produces` con `null`; colonna `failed` | una classe sola; il proxy CDI di un produttore nullo è fragile | — |
| 25 | 12/10 | 010 | registro | Anno del numero d'ordine e «oggi» per la data di ritiro dal fuso del negozio (`Europe/Rome` per ora) | `Europe/Rome` fisso | stesso risultato oggi, giusto se arrivano negozi in altri fusi | — |
| 26 | 12/10 | 011 | ratifica | `quarkus.rest-csrf.require-form-url-encoded=false` + `@Consumes(form)` sui `POST` di carrello e checkout + filtro `FormOnlyFilter` (415 su Host di negozio per `POST`/`PUT`/`PATCH`/`DELETE` non form o senza `Content-Type`); i `POST` form restano con token. Sostituisce n. 18 | `true` (il webhook JSON prende 400 «wrong media type», verificato sul bytecode 3.33.4); rotta Vert.x (salta il filtro del negozio) | il webhook Stripe JSON passa e i form restano protetti; si torna indietro con una riga. I futuri `POST` della piattaforma (admin) devono avere lo stesso filtro o `@Consumes` | — |
| 27 | 12/10 | 011 | ratifica | Eccezione a «`Clock` ovunque»: la tolleranza di 300 s della firma del webhook usa l'ora di sistema (`Webhook.constructEvent` a 3 argomenti) | `Clock` iniettato | Stripe firma con l'ora reale; con il `Clock` spostato nei test la firma vecchia sarebbe accettata | — |
| 28 | 12/10 | 011 | registro | Il webhook legge la sessione con `deserializeUnsafe()` (Gson di stripe-java), non `getObject()` né Gson/Jackson a mano; il filtro 415 lascia passare `/webhooks/` per dare 404 su un Host di negozio | `getObject()` vuoto con altra `api_version`; Gson non è visibile al compilatore (nessuna dipendenza nuova) | niente dipendenze; un campo di tipo sbagliato dà eccezione e 500 | — |
| 29 | 12/10 | 012 | registro | L'account Stripe del demo si applica in `DemoSeed.seed()` con un `update` separato (account e `stripe_charges_enabled=true`); conflitto `unique` = log ERROR e il valore precedente resta; valore tolto = account conservato | osservatore `StartupEvent` separato | il riavvio rilancia comunque l'avvio; il demo non resta mai senza checkout per un errore | — |
| 30 | 12/10 | 012 | registro | Chiavi `sk_live_`/`rk_live_` rifiutate in `StripeCheckout.init()` (checkout spento, WARN senza la chiave); webhook controllato in `smoke.sh` sul dominio della piattaforma (400) e sul demo (404) | rifiuto nel checkout | un solo punto costruisce il client Stripe | — |
| 31 | 15/10 | 013 | senso unico | Segreti Stripe test in produzione: opzione A (l’umano ha fatto i passi 1–3 del runbook §4; il webhook ora risponde 400; paga lui con la 4242). Risposta: ⏱ 30', da Gerio: sì | B (solo strumenti), C (pagamento automatico) | verificato con curl il 15/10: 400 invece di 503 | risposta del 15/10 |
