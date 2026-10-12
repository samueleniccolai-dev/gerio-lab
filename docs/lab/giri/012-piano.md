---
giro: 012
traguardo: T5
fase: costruzione
inizio: 2026-10-12T10:27:00Z
ricerca: no
accettazione: 898a6b2
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
- Account collegato di prova (solo nei test): `acct_1GlicineDemoTest0`; account «vecchio» dei test: `acct_1GlicineVecchio`.
- Chiave finta di test: `sk_test_giro012_finta`; chiavi finte live (da rifiutare): `sk_live_giro012_finta`, `rk_live_giro012_finta`.
- Segreto finto del webhook: `whsec_giro012_finto`.
- Carrello: 1 × «Mazzo di rose rosse» 12 rose + 2 × orchidea bianca (10480 centesimi), come nei giri 010–011.
- Secondo negozio: «Fiori di Marco» con `acct_1MarcoDemoTest` (dati del giro 010).

## Comportamento deciso (dopo la critica)
- `application.properties`: `app.demo.stripe-account-id=${DEMO_STRIPE_ACCOUNT_ID:}`, letto come `Optional<String>`.
- Applicato **solo se `app.demo.seed=true`**, all'avvio, in un passo `@Startup` (o osservatore di
  `StartupEvent`): dopo `demo-shop.sql`, **in un'istruzione separata**
  `update tenant set stripe_account_id = ?, stripe_charges_enabled = true where id = '00000000-0000-0000-0000-0000000de000'`.
- Valore presente: scrive account **e** `stripe_charges_enabled = true` (senza il flag `canCharge()` resta
  falso). Stesso valore al riavvio: nessun cambiamento, nessun errore.
- Valore assente o vuoto: non si tocca nulla, né l'account né il flag (l'account si cambia solo
  impostandone uno nuovo).
- Conflitto `unique` (account già di un altro negozio): l'eccezione è presa, **resta il valore
  precedente** del demo (flag compreso), riga di log `ERROR` che nomina Stripe e l'account (non è un
  segreto), l'avvio prosegue e il resto del seed resta applicato.
- Chiavi live: in `StripeCheckout.init()` una chiave che inizia con `sk_live_` o `rk_live_` è rifiutata:
  client nullo, quindi `configured() = false` (checkout spento: niente «Ordina e paga», `POST /checkout`
  409, nessuna chiamata a Stripe); una riga `WARN` (o `ERROR`) che contiene la parola «live», **mai la chiave**.
- I test simulano il riavvio rilanciando l'evento CDI `StartupEvent`: l'applicazione del valore deve
  stare in un passo d'avvio, non in un costruttore o in un `@PostConstruct` pigro.

## Fatto quando — controlli eseguibili
- [ ] Con seed, `app.demo.stripe-account-id=acct_1GlicineDemoTest0` e chiave di test (Stripe finto): demo
      con account e `stripe_charges_enabled=true`; il riepilogo mostra «Ordina e paga»; `POST /checkout` → 303
      e il finto Stripe riceve `Stripe-Account: acct_1GlicineDemoTest0` e la chiave di test; il log non
      contiene la chiave → `Giro012DemoStripeAccountAcceptanceTest`.
- [ ] Riavvio con lo stesso valore: nessun cambiamento, nessun `ERROR`; un valore nuovo sostituisce quello
      vecchio e accende il flag → `Giro012DemoStripeAccountAcceptanceTest`.
- [ ] Conflitto `unique` (Marco ha già `acct_1GlicineDemoTest0`): il demo resta su `acct_1GlicineVecchio`,
      Marco invariato, riga `ERROR` con «stripe», catalogo del demo 200 → `Giro012DemoStripeAccountAcceptanceTest`.
- [ ] Senza valore: un demo nuovo resta senza account (riepilogo con telefono ed email, niente «Ordina e
      paga», `POST /checkout` 409, nessuna chiamata a Stripe); un demo già collegato tiene account e flag;
      `application.properties` ha la riga con `${DEMO_STRIPE_ACCOUNT_ID:}` → `Giro012DemoWithoutAccountAcceptanceTest`.
- [ ] Chiave `sk_live_…` e `rk_live_…` (demo collegato): app su, niente «Ordina e paga», `POST /checkout`
      409, nessun ordine, nessuna chiamata al finto Stripe, log `WARN`/`ERROR` con «live», nessuna chiave
      nel log → `Giro012LiveSecretKeyRefusedAcceptanceTest`, `Giro012LiveRestrictedKeyRefusedAcceptanceTest`.
- [ ] Isolamento: dopo il seed il demo ha `acct_1GlicineDemoTest0` e Marco il suo; con due ordini con lo
      **stesso numero** (uno per negozio), un evento con l'account del demo e i dati dell'ordine di Marco
      non cambia nulla; l'evento del demo paga solo l'ordine del demo → `Giro012DemoStripeAccountIsolationAcceptanceTest`.
- [ ] `infra/docker-compose.yml`: `app` riceve `STRIPE_SECRET_KEY`, `STRIPE_CONNECT_WEBHOOK_SECRET`,
      `DEMO_STRIPE_ACCOUNT_ID` come `${VAR:-}`: vuote con `env.example`, valorizzate dall'ambiente →
      `Giro012InfraAcceptanceTest`.
- [ ] `infra/deploy.sh`: `DEMO_STRIPE_ACCOUNT_ID` opzionale; se non vuoto deve rispettare
      `^acct_[A-Za-z0-9]+$`, altrimenti exit 2 con il nome della variabile, **prima** di ogni altro
      controllo; valido o vuoto → si prosegue (exit 3 senza configurazione). Va a `remote.sh` come quinto
      argomento (non è un segreto ed è validato; `remote.sh` ricontrolla la stessa regex) → `Giro012InfraAcceptanceTest`.
- [ ] Segreti solo su stdin, formato fisso: `deploy.sh` scrive tre righe (`printf '%s\n%s\n%s\n'`):
      1 `GHCR_TOKEN`, 2 `STRIPE_SECRET_KEY`, 3 `STRIPE_CONNECT_WEBHOOK_SECRET`; `remote.sh` le legge con tre
      `IFS= read -r` e passa a `docker login --password-stdin` **solo** la prima (con `printf '%s'`); riga
      vuota = assente: la voce di `.env` non si tocca; presente: `set_env` (600). Nessun segreto nel comando
      ssh → `Giro012InfraAcceptanceTest`; `bash -n infra/*.sh` e `shellcheck infra/*.sh` puliti.
- [ ] `infra/smoke.sh`: `POST https://$LAB_BASE_DOMAIN/webhooks/stripe/connect` senza firma → `400` o `503`
      (mai `404`/`500`); `POST https://demo.$LAB_BASE_DOMAIN/webhooks/stripe/connect` → `404` (la rotta vive
      solo sul dominio della piattaforma) → `Giro012InfraAcceptanceTest` (presenza) + revisione.
- [ ] `infra/README.md` e `docs/runbook.md`: passi dell'umano e variabili. In `infra/` non scrivere i
      prefissi letterali delle chiavi: `Giro001DeployAcceptanceTest.noSecretLooksCommittedInInfra` vieta
      `sk_test_` e `sk_live_`; scrivere «chiave segreta di test di Stripe».
- [ ] Test dei giri precedenti verdi; `./mvnw -B -ntp verify` verde (esclusi i 2 test visivi se manca Chromium).

## Passi dell'umano (per il giro 013, non bloccano questo giro)
1. Stripe in modalità test, Connect attivo; un account collegato di prova per «Fioreria Il Glicine».
2. Endpoint webhook Connect sul **dominio della piattaforma** `https://<LAB_BASE_DOMAIN>/webhooks/stripe/connect`
   (sul dominio del demo risponde 404) con gli eventi `checkout.session.completed` e `checkout.session.expired`.
3. Nell'environment `produzione` di GitHub: segreti `STRIPE_SECRET_KEY` (chiave segreta o limitata **di
   test**), `STRIPE_CONNECT_WEBHOOK_SECRET`; variabile `DEMO_STRIPE_ACCOUNT_ID`.
4. In `.github/workflows/consegna.yml` (protetto, lo fa l'umano): passare le tre al passo di deploy.

## Fuori perimetro
- La prova in produzione con Stripe test vero e T5 → `passato` (giro 013).
- Modifiche a `.github/` (protetto): solo indicate all'umano.
- Collegamento Stripe da area admin, onboarding, `account.updated` (SPEC §8.2): back-office fuori perimetro.
- Validazione del formato dell'account nell'app (la fanno `deploy.sh` e `remote.sh`).
- Corsa webhook/`POST /checkout` e test dedicati per idempotenza e `FormOnlyFilter` (dubbi del giro 011): in «Idee per dopo».
- Modalità live, rimborsi, `charge.refunded`, `charge.dispute.created`.
- Da `traguardi.json → fuori_perimetro`: back-office del negozio; consegna `CEMETERY`; capacità per
  fascia oraria; impianto consensi e GDPR oltre la pagina privacy; SPF e DKIM completi.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
Supporto: `Giro012Profiles` (profili con seed acceso, chiave finta, log su file in `target/`),
`Giro012FakeStripe`, `Giro012Data` (riavvio simulato con `StartupEvent`, con controllo che il seed
ripassi davvero), `Giro012Log`, `Giro012LiveKeyRefused` (base astratta).

| File | Dato … quando … allora … |
|---|---|
| `Giro012DemoStripeAccountAcceptanceTest` | seed con account: demo collegato, checkout su `acct_1GlicineDemoTest0`; riavvio idempotente; valore nuovo sostituisce; conflitto `unique` → valore precedente + `ERROR` |
| `Giro012DemoWithoutAccountAcceptanceTest` | senza valore: demo nuovo senza pagamento (contatti, 409); demo collegato invariato; riga in `application.properties` |
| `Giro012LiveSecretKeyRefusedAcceptanceTest`, `Giro012LiveRestrictedKeyRefusedAcceptanceTest` | `sk_live_`/`rk_live_`: checkout spento, nessuna chiamata a Stripe, log «live» senza chiave |
| `Giro012DemoStripeAccountIsolationAcceptanceTest` | ordini con lo stesso numero in due negozi: l'evento dell'account del demo cambia solo l'ordine del demo |
| `Giro012InfraAcceptanceTest` | compose, validazione di `DEMO_STRIPE_ACCOUNT_ID`, segreti fuori dal comando ssh, `IFS= read -r`, smoke sulla rotta webhook |

**Accettazione corretta durante la costruzione (da guardare in revisione umana).** Il curl finto
di `Giro006DemoHostAcceptanceTest` non conosceva `POST /webhooks/stripe/connect` e rispondeva
«unexpected URL» alle chiamate che questo piano impone a `smoke.sh`
(`Giro012InfraAcceptanceTest.smokeChecksTheWebhookRoute`). Con un commit `test(acceptance)` nuovo
(nessuna storia riscritta) il finto ora risponde 400 sull'host base e 404 su `demo.<BASE>`, come il
sito vero. Nessuna asserzione di Giro006 è cambiata; restano rossi per ambiente 2 test Giro006
(immagine Caddy non scaricata) e 2 visuali (Chromium mancante).

## Porte
- Doppio senso: proprietà `app.demo.stripe-account-id` (si toglie con una riga); rifiuto delle chiavi
  live (una riga); variabili opzionali in compose; formato stdin di `remote.sh`; check in `smoke.sh`.
- `infra/` è percorso protetto: PR in revisione umana (METODO §4), non auto-merge.
- Senso unico: nessuna in questo giro (nessuna dipendenza, nessuna migrazione, nessun valore reale;
  le chiavi live sono rifiutate dal codice). La porta a senso unico (account e chiavi Stripe dell'umano) è del giro 013.

## Rischi di concorrenza
- Il seed gira all'avvio, una volta, su una sola istanza; l'`update` è idempotente.
- `stripe_account_id` è `unique`: in conflitto l'avvio non si rompe (eccezione presa nell'istruzione
  separata), il demo tiene il valore precedente, `ERROR` nel log (provato da test).
- Nessun cambio a webhook e checkout: i test dei giri 010–011 restano verdi.

## Budget
Righe di produzione ≤ 140 (Java ≤ 60, shell/compose ≤ 80); file ≤ 9; una sessione.

## Domande per il ricercatore (al massimo 3)
Nessuna (`ricerca: no`).

## Idee per dopo
- Chiudere la corsa webhook/`POST /checkout` (secondo ordine in attesa per merce già pagata).
- Test dedicati per l'idempotenza su `stripe_event` e per `FormOnlyFilter` (mutazioni M3, M4).
- Scadere via API la sessione Stripe vecchia quando nasce un ordine nuovo.
- Prova di comportamento di `remote.sh` con una radice configurabile (oggi `/opt/gerio-lab` fisso).

## Critica ricevuta e cosa cambia
| Punto | Esito | Cosa cambia |
|---|---|---|
| 1. Webhook 404 sul dominio del demo | **Accolto** | `smoke.sh` e passo 2 dell'umano sul dominio della piattaforma; controllo che sul demo sia 404. Test: presenza della rotta in `smoke.sh` (lo smoke non si prova senza server: il resto è revisione). |
| 2. `canCharge()` vuole anche `stripe_charges_enabled` | **Accolto** | Il seed scrive anche il flag a `true`; valore tolto → né account né flag cambiano. Test su entrambi. |
| 3. Formato di stdin di `remote.sh` | **Accolto** | Tre righe con `IFS= read -r`, solo la prima a `docker login`, riga vuota = assente e `.env` non si tocca. Test: segreti fuori dal comando ssh, `IFS= read -r` in `remote.sh`. |
| 4. `DEMO_STRIPE_ACCOUNT_ID` nel comando ssh | **Accolto** | Regex `^acct_[A-Za-z0-9]+$` in `deploy.sh` (exit 2, prima di tutto) e di nuovo in `remote.sh`; proprietà `${DEMO_STRIPE_ACCOUNT_ID:}` letta come `Optional`. Test su 5 valori malformati, su uno valido e sul vuoto. |
| 5. Conflitto `unique` | **Accolto** | Istruzione separata con try/catch, resta il valore precedente + `ERROR`; tolta la frase «checkout spento sul demo» (il demo tiene il suo account). Test di accettazione. |
| Rifiuto chiavi live in `StripeCheckout.init()` | **Accolto** | Filtro sui prefissi `sk_live_` e `rk_live_`, `configured()=false`. Due test, uno per prefisso. |
| Isolamento con lo stesso `order_number` | **Accolto** | Ordini di demo e Marco con lo stesso numero; cambia solo quello del demo. |
| Riavvio idempotente, valore tolto, log senza chiave | **Accolto** | Riavvio simulato con `StartupEvent` (con controllo che il seed ripassi davvero); log su file letto dal test. |

Scartato: nulla. Aggiunto da me: test dei valori dall'ambiente in compose; avviso sui prefissi delle
chiavi vietati in `infra/` dal test del giro 001; budget alzato di 20 righe di shell per il formato di stdin.
