---
giro: 011
traguardo: T5
esito: pr-aperta
inizio: 2026-10-12T09:37:41Z
fine: 2026-10-12T10:20:08Z
pr: 14
modello_orchestratore: sonnet
modello_costruttore: sonnet
modello_revisore: opus
ricerca_fonti: 5
critico_da_correggere: 4
critico_accolti: 4
revisore_bloccanti: 0
revisore_dubbi: 4
dubbi_corretti: 2
fuori_perimetro: 0
mutazioni_provate: 5
mutazioni_prese: 3
verify_rossi_locali: 1
test_totali: 312
test_delta: 41
decisioni_registro: 2
decisioni_ratifica: 2
porte_senso_unico: 0
riparazioni: 0
difetto_di:
---
# Rapporto del giro 011 — webhook Stripe, stati PAID/EXPIRED, pagina di esito (T5, parte 2)

> **PR:** #14 · **Branch:** `claude/giro-011-t5` · **Traguardo:** T5 — parziale
> **Domande che bloccano:** nessuna

## Per te, in 10 righe
- Il webhook `POST /webhooks/stripe/connect` verifica la firma, è idempotente (per `event.id`) e porta l'ordine a `PAID` (carrello svuotato) o `EXPIRED`.
- L'ordine si trova da `client_reference_id` nel negozio dell'account; un evento di un altro negozio non tocca nulla. Senza segreto: 503.
- Pagina `/ordine/esito`: numero, stato, totale, nessun dato personale (ricarica con `meta refresh`, non htmx: scostamento n. 20).
- Migrazione V6 (solo aggiunte). `pr_guard` risponde `auto-merge`: nessuna revisione umana obbligatoria.
- **Correzione di rotta:** la ricerca sbagliava sul CSRF (un POST JSON prende 400 col default); la critica l'ha trovato sul bytecode. Ora `require-form-url-encoded=false` + `@Consumes(form)` + filtro 415 sulla vetrina.
- Ratifiche: n. 26 (CSRF come sopra, sostituisce la n. 18); n. 27 (firma sull'ora di sistema, eccezione a `Clock`).
- Da te per il giro 012: Stripe in modalità test, account collegato di prova, endpoint webhook e variabili sul server.
- Avviso: `verify` locale rosso solo per Chromium mancante nell'ambiente (2 test Playwright); verde senza.

## Cosa funziona ora — con la prova
- Webhook, esito, cambio prezzo, CSRF dei form: 27 test di accettazione `Giro011*` verdi (commit `cb7b385`, non modificati dopo).
- Isolamento tra negozi, macchina a stati, concorrenza webhook/carrello: `OrderLookupIsolationTest`, `OrderStateMachineTest`, `WebhookCartLockTest`, `CheckoutSessionSaveTest`, `StripeEventProcessorTest`.
- Revisore: 312 test, 310 verdi senza i 2 visivi; `pr_guard` → `auto-merge`.

## Cosa non funziona ancora
- Nessuna prova in produzione con Stripe test vero (giro 012).
- Webhook e nuovo `POST /checkout` quasi simultanei sullo stesso carrello: può nascere un secondo ordine in attesa per merce già pagata (6 su 8 nella sonda del revisore; finestra di pochi ms; nessun 500).
- Secondo pagamento su sessione vecchia: solo WARN, nessun rimborso. La sessione vecchia non viene scaduta via API.
- L'idempotenza su `stripe_event` e il `FormOnlyFilter` non sono coperti da test dedicati (mutazioni M3 e M4 non prese): la difesa è doppia.
- Sessione vecchia: la pagina di esito dà 404 finché non arriva il webhook.

## Verdetto del revisore
- **BLOCCANTI:** nessuno.
- **DUBBI:** 4 sull'ultimo SHA (corsa webhook/checkout; idempotenza senza test; filtro 415 difesa doppia; Chromium mancante nell'ambiente). Corretti 2 dalla prima revisione (`saveSession` solo su `PENDING_PAYMENT`; WARN su pagamento di ordine `EXPIRED`).
- **PERIMETRO:** nessuna aggiunta fuori piano.
- **CONCORRENZA:** completed/expired in parallelo e duplicati: un solo cambio di stato; nessuno stallo.
- **MUTAZIONI:** provate 5, prese 3.

VERDETTO {"sha": "286d95a7f2dc118bf4d94631c811726fc17a4c53", "bloccanti": 0, "dubbi": 4, "fuori_perimetro": 0, "mutazioni_provate": 5, "mutazioni_prese": 3, "test_totali": 312}

## Ricerca e critica
Presi: firma con `constructEvent` (300 s), idempotenza su `event.id`, `PAID` solo con `complete`+`paid`. Corretti dalla critica (4 su 4): CSRF, ricerca dell'ordine per `client_reference_id`, lettura da JSON grezzo per `api_version` diversa, niente blocco del carrello nel webhook. Scartata la rotta Vert.x.

## Prossima fetta
Giro 012: prova in produzione con Stripe test (richiede le tue variabili), poi T6.
