---
giro: 010
traguardo: T5
esito: pr-aperta
inizio: 2026-10-12T06:53:38Z
fine: 2026-10-12T07:31:41Z
pr: 13
modello_orchestratore: sonnet
modello_costruttore: sonnet
modello_revisore: opus
ricerca_fonti: 7
critico_da_correggere: 5
critico_accolti: 5
revisore_bloccanti: 0
revisore_dubbi: 4
dubbi_corretti: 0
fuori_perimetro: 1
mutazioni_provate: 4
mutazioni_prese: 3
verify_rossi_locali: 1
test_totali: 271
test_delta: 24
decisioni_registro: 4
decisioni_ratifica: 2
porte_senso_unico: 1
riparazioni: 0
difetto_di:
---
# Rapporto del giro 010 — dal carrello alla sessione Stripe Checkout (T5, parte 1)

> **PR:** #13 · **Branch:** `claude/giro-010-t5` · **Traguardo:** T5 — parziale
> **Domande che bloccano:** nessuna (la dipendenza `stripe-java` è stata approvata: «Procedi con Opzione A»)

## Per te, in 10 righe
- Dal carrello, «Ordina e paga» crea l'ordine `PENDING_PAYMENT` (numero `2026-0001`) e manda il cliente alla sessione Stripe Checkout dell'account del negozio.
- Doppio clic: un solo ordine e una sola sessione. Carrello cambiato: ordine nuovo. Stripe in errore: pagina con «Riprova» (502).
- Senza configurazione Stripe l'app parte lo stesso e il checkout resta spento: il deploy non si rompe.
- La PR va in **revisione umana**: `pom.xml` (stripe-java 34.0.0) e migrazione V5.
- Ratifiche: n. 22 (carrello non svuotato al checkout; cambia qualcosa → ordine nuovo); n. 23 (checkout spento: riepilogo senza pulsante, con telefono/email del negozio).
- Da te, per il giro 012: Stripe in modalità test, account collegato di prova, webhook `/webhooks/stripe/connect`, variabili sul server.
- Le chiavi di idempotenza sono verificate solo con lo Stripe finto: da controllare sulla documentazione (giro 012).

## Cosa funziona ora — con la prova
- Checkout, ordine, parametri a Stripe, numerazione, isolamento: 18 test di accettazione verdi (`Giro010*`).
- 271 test in `verify` (24 in più). Revisore: 8 POST in parallelo → 1 ordine, 1 sessione; 6 carrelli in parallelo → numeri 0001–0006 senza buchi.

## Cosa non funziona ancora
- Webhook firmato e idempotente, stati `PAID`/`EXPIRED`, pagina di esito (`/ordine/esito` dà 404): giro 011.
- Prova in produzione con Stripe test: giro 012. In produzione il demo ha il checkout spento finché mancano le variabili.
- Sessione Stripe abbandonata non scaduta (`expire`) e timeout di rete senza risposta: giro 011.
- `verify` locale: 2 test Playwright rossi per Chromium 1243 mancante nella sandbox (ambientale); da confermare in CI.

## Verdetto del revisore
- **BLOCCANTI:** nessuno.
- **DUBBI:** 4, nessuno corretto — (1) nessun test copre il cambio di prezzo tra due tentativi (mutazione sul confronto del prezzo in `sameOrder` non presa); (2) corsa rara `markFailed`/`saveSession` (rientra nell'`expire` del giro 011); (3) variante disattivata tra riepilogo e POST: la riga sparisce senza avviso; (4) i due test visuali dipendono dal Chromium dell'ambiente.
- **PERIMETRO:** `app.stripe.connect-webhook-secret` e `StripeConfig.connectWebhookSecret()` servono solo al webhook; innocui.
- **CONCORRENZA:** sonde verdi (vedi sopra).
- **MUTAZIONI:** provate 4, prese 3.

## Ricerca e critica
Ricerca: 7 fonti; presi `RequestOptions`, `expires_at` ≥ 30 min, `setApiBase`; domanda sui fiorai italiani non verificata. Critica: 5 punti, tutti accolti (idempotenza con tentativi, ordine non riusato se il carrello cambia, contatore con upsert, margine 31 min, configurazione Stripe opzionale).

## Prossima fetta
Giro 011: webhook `/webhooks/stripe/connect` firmato e idempotente, `PAID`/`EXPIRED`, esclusione CSRF, pagina di esito; più il test del dubbio 1.
