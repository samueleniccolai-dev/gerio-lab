# Ricerca giro 010 (T5, Stripe Checkout) — 12/10/2026

Risposta del ricercatore, riportata così com'è nei punti essenziali. Fonti consultate: 7 aperte, 1 non raggiungibile (503).

## Come lo fanno gli altri
- Shopify: link alle policy nel footer del checkout; policy resi anche nel riepilogo ordine (https://help.shopify.com/en/manual/checkout-settings/refund-privacy-tos). Nulla su campi obbligatori o data di consegna.
- Fiorai online italiani: NON verificato (Fioritalia condizioni di vendita: 503).

## Casi reali e documentazione
- Domanda 1: `RequestOptions.builder().setIdempotencyKey(..).setStripeAccount("acct_..").build()` come ultimo argomento (README stripe-java). L'overload per `sessions().create` è dedotto, da controllare nel Javadoc. `expires_at` in secondi epoch, tra 30 min e 24 h (https://docs.stripe.com/api/checkout/sessions/create?lang=java). `StripeClient.builder().setApiBase(url)` esiste (https://stripe.dev/stripe-java/com/stripe/StripeClient.StripeClientBuilder.html). Porta di stripe-mock: non verificata.
- Domanda 3: dashboard completa = `controller.stripe_dashboard.type=full`, `fees.payer=account`, `losses.payments=stripe`, `requirement_collection=stripe`; sono i valori predefiniti (https://docs.stripe.com/connect/migrate-to-controller-properties). Account di test creabili da API o dashboard (https://docs.stripe.com/connect/testing); non verificato se dalla dashboard si scelgano le proprietà `controller`. Dati di test: SMS `000-000`, nascita `1901-01-01`, indirizzo `address_full_match`.

## Cosa prendiamo
1. Account di test con `controller` predefiniti; `acct_...` in configurazione, non nel repository.
2. `Stripe-Account` e chiave di idempotenza via `RequestOptions`, chiave deterministica per ordine.
3. `expires_at` con `Clock` iniettato, ≥ 30 min.
4. Nei test `StripeClient` con `setApiBase` verso uno Stripe finto (server HTTP di test).
5. Link a condizioni di vendita e resi accanto al pulsante di pagamento.

## Cosa evitiamo
- API Accounts v2 (anteprima). Affermazioni non verificate sui fiorai italiani. Porta di stripe-mock data per certa.

## Idee per dopo
- Rifare la domanda 2 con due fiorai italiani e il Codice del consumo sul recesso.
- Verificare nel Javadoc gli overload di `SessionCreateParams`/`RequestOptions`.
