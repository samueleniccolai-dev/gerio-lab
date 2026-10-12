---
giro: 012
traguardo: T5
fase: critica
inizio: 2026-10-12T10:27:00Z
ricerca: no
accettazione:
ripreso: no
---
# Piano del giro 012 — T5, binari Stripe verso la produzione (senza chiavi)

## Fetta e traguardo
T5, parte 3: il percorso delle variabili Stripe fino al server e il collegamento del negozio demo a
un account di prova, provati con valori finti e assenti. Senza questa fetta, anche con le chiavi
dell'umano, la prova in produzione non può partire: oggi `infra/docker-compose.yml` non passa
`STRIPE_*` al container `app`, `deploy.sh`/`remote.sh` non le portano sul server e il negozio demo
ha `stripe_account_id` nullo senza un modo per valorizzarlo (il back-office è fuori perimetro).
La prova vera con Stripe test (T5 → `passato`) resta al giro 013, dopo i passi dell'umano.

## Dati del negozio demo (inventati)
- Account collegato di prova (solo nei test): `acct_1GlicineDemoTest0`.
- Chiave finta di test: `sk_test_giro012_finta`; chiave finta live (da rifiutare): `sk_live_giro012_finta`.
- Segreto finto del webhook: `whsec_giro012_finto`.
- Prodotto per il carrello: «Mazzo di rose rosse» (`rose-rosse`), già nel demo, prezzo come da `demo-shop.sql`.

## Fatto quando — controlli eseguibili
- [ ] Con `app.demo.seed=true`, `app.demo.stripe-account-id=acct_1GlicineDemoTest0` e chiave
      `sk_test_…` (Stripe finto): il riepilogo del carrello del demo mostra «Ordina e paga» e il
      `POST /checkout` arriva al finto Stripe con header `Stripe-Account: acct_1GlicineDemoTest0` → test `Giro012*`.
- [ ] Con `app.demo.stripe-account-id` assente: il demo resta senza account, riepilogo senza
      «Ordina e paga», con telefono ed email (comportamento n. 23 invariato) → test `Giro012*`.
- [ ] Con chiave `sk_live_…` o `rk_live_…`: l'app parte, il checkout è spento (nessun «Ordina e paga»,
      `POST /checkout` → 409) e il log dice perché, senza stampare la chiave → test `Giro012*`.
- [ ] Riavvio con lo stesso valore: nessun cambiamento (seed idempotente); valore tolto: l'account
      del demo non viene cancellato (si cambia solo impostando un valore nuovo) → test `Giro012*`.
- [ ] Isolamento: l'account si applica solo al tenant demo `00000000-0000-0000-0000-0000000de000`;
      un evento webhook con `account=acct_1GlicineDemoTest0` non tocca ordini di un altro negozio → test `Giro012*`.
- [ ] `infra/docker-compose.yml`: `app` riceve `STRIPE_SECRET_KEY`, `STRIPE_CONNECT_WEBHOOK_SECRET`,
      `DEMO_STRIPE_ACCOUNT_ID` come `${VAR:-}` (opzionali) → `docker compose -f infra/docker-compose.yml --env-file infra/env.example config` riesce e le mostra vuote.
- [ ] `infra/deploy.sh` → `remote.sh`: i due segreti viaggiano solo su stdin (come `GHCR_TOKEN`),
      mai come argomenti; se presenti finiscono in `.env` (600), se assenti `.env` non cambia →
      `bash -n infra/*.sh` e `shellcheck` puliti; `grep` mostra che nessun segreto è passato in argv.
- [ ] `infra/smoke.sh`: `POST https://demo.$LAB_BASE_DOMAIN/webhooks/stripe/connect` senza firma →
      `400` o `503`, mai `404`/`500` (prova che la rotta esiste in produzione).
- [ ] `infra/README.md` e `docs/runbook.md`: elenco dei passi dell'umano (sotto) e delle variabili.
- [ ] `./mvnw -B -ntp verify` verde (esclusi i 2 test visivi se manca Chromium, come nel giro 011).

## Passi dell'umano (per il giro 013, non bloccano questo giro)
1. Stripe in modalità test, Connect attivo; un account collegato di prova per «Fioreria Il Glicine».
2. Endpoint webhook Connect `https://demo.<LAB_BASE_DOMAIN>/webhooks/stripe/connect` con gli eventi
   `checkout.session.completed` e `checkout.session.expired`.
3. Nell'environment `produzione` di GitHub: segreti `STRIPE_SECRET_KEY` (`sk_test_…` o `rk_test_…`),
   `STRIPE_CONNECT_WEBHOOK_SECRET`; variabile `DEMO_STRIPE_ACCOUNT_ID`.
4. In `.github/workflows/consegna.yml` (protetto, lo fa l'umano): passare le tre al passo di deploy.

## Fuori perimetro
- La prova in produzione con Stripe test vero e T5 → `passato` (giro 013).
- Modifiche a `.github/` (protetto): solo indicate all'umano.
- Collegamento Stripe da area admin, onboarding, `account.updated` (SPEC §8.2): back-office fuori perimetro.
- Corsa webhook/`POST /checkout` e test dedicati per idempotenza e `FormOnlyFilter` (dubbi del giro 011): in «Idee per dopo».
- Modalità live, rimborsi, `charge.refunded`, `charge.dispute.created`.
- Da `traguardi.json → fuori_perimetro`: back-office del negozio; consegna `CEMETERY`; capacità per
  fascia oraria; impianto consensi e GDPR oltre la pagina privacy; SPF e DKIM completi.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|
| `Giro012DemoStripeAccountAcceptanceTest` | dato il demo con account e chiave test finti, quando il carrello ha le rose e si apre il riepilogo, allora c'è «Ordina e paga» e il finto Stripe riceve `Stripe-Account: acct_1GlicineDemoTest0` |
| `Giro012DemoWithoutAccountAcceptanceTest` | dato il demo senza account, allora niente «Ordina e paga», contatti visibili, `POST /checkout` 409 |
| `Giro012LiveKeyRefusedAcceptanceTest` | data una chiave `sk_live_…`, allora app su, checkout spento, `POST /checkout` 409 |
| `Giro012DemoStripeAccountIsolationAcceptanceTest` | dato un secondo negozio, quando arriva un webhook con l'account del demo, allora gli ordini dell'altro negozio non cambiano |

## Porte
- Doppio senso previste: proprietà `app.demo.stripe-account-id` (si toglie con una riga); rifiuto
  delle chiavi live (si toglie con una riga); variabili opzionali in compose; check in `smoke.sh`.
- `infra/` è percorso protetto: PR in revisione umana (METODO §4), non auto-merge.
- Senso unico: nessuna in questo giro (nessuna dipendenza, nessuna migrazione, nessun valore reale;
  le chiavi live sono rifiutate dal codice). La porta a senso unico (account e chiavi Stripe dell'umano) è del giro 013.

## Rischi di concorrenza
- Il seed gira all'avvio, una volta, su una sola istanza: `update tenant set stripe_account_id = …
  where id = demo` idempotente. `stripe_account_id` è `unique`: se un altro negozio avesse lo stesso
  account l'avvio non deve rompersi: log di errore e checkout spento sul demo (provato da test).
- Nessun cambio a webhook e checkout: i test del giro 011 restano verdi.

## Budget
Righe di produzione ≤ 120 (Java ≤ 60, shell/compose ≤ 60); file ≤ 8; una sessione.

## Domande per il ricercatore (al massimo 3)
Nessuna (`ricerca: no`).

## Idee per dopo
- Chiudere la corsa webhook/`POST /checkout` (secondo ordine in attesa per merce già pagata).
- Test dedicati per l'idempotenza su `stripe_event` e per `FormOnlyFilter` (mutazioni M3, M4).
- Scadere via API la sessione Stripe vecchia quando nasce un ordine nuovo.

## Critica ricevuta e cosa cambia
(dopo il critico)
