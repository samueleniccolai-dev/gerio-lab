---
giro: 015
traguardo: T6
fase: pronto
inizio: 2026-10-15T12:51:40Z
ricerca: sì
accettazione: 7945c4d
ripreso: no
---
# Piano del giro 015 — email di conferma (coda e invio)

## DOMANDA (porta a senso unico: dipendenza nuova)

**Contesto.** T6 chiede «dopo il pagamento parte l'email di conferma». Il giro 014 ha fatto la
pagina di stato. Per spedire un'email serve un client SMTP: oggi nel `pom.xml` non c'è. SPEC §4.2
e §9.1 indicano l'estensione Mailer di Quarkus. Il PDF delle condizioni (§9.2) e il provider SMTP
sono due decisioni distinte: le separo, così questa fetta parte senza aspettarle.

**Dipendenza proposta:** `io.quarkus:quarkus-mailer`, versione gestita dal BOM di Quarkus 3.33
(nessuna versione nel `pom.xml`). Motivo: SMTP con TLS, template Qute (`MailTemplate`),
`MockMailbox` nei test (nessun server SMTP in CI), `quarkus.mailer.mock=true` finché manca il
provider. Alternativa: `org.eclipse.angus:angus-mail` (Jakarta Mail) a mano: più codice, niente
mock pronto, niente integrazione Qute.

| Opzione | Pro | Contro |
|---|---|---|
| **A. `quarkus-mailer` ora, provider e PDF dopo** | La fetta si chiude in una sessione; i test provano l'email vera (destinatario, oggetto, corpo HTML + testo) con `MockMailbox` | In produzione l'email resta finta (log) finché l'umano non sceglie il provider; T6 resta `in-corso` |
| B. `quarkus-mailer` + `openhtmltopdf` + provider nello stesso giro | Un giro solo per chiudere T6 | Tre porte insieme, due richiedono azioni umane (account, DNS, segreti); la fetta non sta in una sessione |
| C. Nessuna dipendenza: coda `outbox_message` e template, invio rinviato | Nessuna porta | Nulla di visibile: «l'email parte» non si può provare; lavoro a metà |

**Raccomandazione: A.**

**Dopo la risposta:**
- **A:** costruisco la fetta qui sotto; PROGRESS annota due domande per i giri successivi:
  (1) PDF delle condizioni — `openhtmltopdf` (versione [DA VERIFICARE]) oppure PDF statico per
  versione di `terms_version`; (2) provider SMTP UE — Brevo, Scaleway Transactional Email,
  Mailjet (listini [DA VERIFICARE]); richiede account, DNS e segreti in mano all'umano.
- **B:** riscrivo il piano su due giri (016 PDF, 017 provider) e questo si limita ad A comunque.
- **C:** riduco la fetta a migrazione `outbox_message` + scrittura nel webhook, test sul solo
  conteggio delle righe; nessuna email.
- **No a tutto:** T6 si ferma alla pagina di stato; si passa a T7 e l'email torna a fine piano.

## Risposta umana
Opzione **A**: solo `io.quarkus:quarkus-mailer` (versione dal BOM 3.33). **Nessuna altra
dipendenza**: niente `quarkus-scheduler` né `quarkus-quartz` (sarebbe una seconda porta senza
risposta). Un test di accettazione lo controlla sul `pom.xml`.

## Fetta e traguardo
T6, seconda parte: coda di uscita scritta dal webhook nella stessa transazione del `PAID`, worker
che la svuota con ritentativi, email di conferma HTML + testo con i contenuti di §9.2 che esistono
già e il link alla pagina di stato. Resta per T6: PDF in allegato, provider SMTP reale.

## Comportamento deciso
- **Migrazione** `V7__outbox.sql` (solo aggiunte): tabella `outbox_message` come SPEC §6
  (`id`, `tenant_id text references tenant(id)`, `channel`, `payload jsonb`, `status`, `attempts`,
  `next_attempt_at`, `last_error`) più `created_at`. Indice unico parziale
  `on outbox_message ((payload->>'orderId')) where payload->>'kind' = 'ORDER_CONFIRMATION'`;
  indice `(status, next_attempt_at)`.
- **Entità** `OutboxMessage` in `notifications/`: tabella globale (SPEC §6), **senza** `@TenantId`;
  `tenant_id` copiato a mano da `order.getTenantId()`.
- **Scrittura (webhook):** in `StripeEventProcessor.completed`, **solo quando `markPaid` restituisce
  `true`**, nella stessa transazione: `insert … on conflict do nothing` nativo, `channel='EMAIL'`,
  payload `{"kind":"ORDER_CONFIRMATION","orderId":<id>}` (nessun dato personale),
  `status='PENDING'`, `attempts=0`, `next_attempt_at` e `created_at` = `clock.instant()` (mai
  `now()` del database: i test spostano il `Clock`).
- **Worker** `notifications.OutboxWorker` (`@ApplicationScoped`), metodo pubblico `drainOnce()`:
  1. *Presa* (transazione breve, contesto Platform): `select … where status='PENDING' and
     next_attempt_at <= :now order by next_attempt_at for update skip locked limit 20`; a ogni
     riga presa si sposta `next_attempt_at = now + 5 min` (lease) e si committa.
  2. *Per riga*, in un nuovo request context (`ManagedContext` di Arc, attivato e chiuso a mano):
     `TenantContext.set(Shop)` dal `tenant_id` della riga, poi un bean a parte con
     `@Transactional(REQUIRES_NEW)` legge ordine, righe e dati del negozio e prepara l'email.
     **Mai** `switchTo` da un negozio all'altro.
  3. *Invio* fuori transazione, poi transazione breve: `SENT`, oppure `attempts+1`,
     `last_error` (solo la classe e il messaggio dell'eccezione, mai l'indirizzo),
     `next_attempt_at = now + 2^(attempts-1) minuti` (1, 2, 4… con il `Clock`); all'ottavo errore
     `FAILED` e log ERROR con il solo id della riga.
  4. Nessuna esecuzione sovrapposta nello stesso processo: `AtomicBoolean` (una seconda chiamata
     mentre la prima gira esce subito); tra processi: `skip locked` + lease.
- **Timer (senza dipendenze nuove):** all'avvio (`@Observes StartupEvent`) `vertx.setPeriodic(30 s)`
  con `io.vertx.core.Vertx` (già nel core di Quarkus); il callback esegue `drainOnce()` su un
  thread worker (`vertx.executeBlocking(…, false)`, non ordinato), mai sull'event loop. Proprietà
  `app.outbox.worker-enabled` (default `true`, `%test` `false`: nei test lo chiamano a mano).
- **Invio acceso o spento:** `app.mail.enabled=${GERIO_MAIL_ENABLED:false}`, `%dev` e `%test`
  `true`. Spento: il worker non invia, lascia la riga `PENDING` senza contare tentativi e logga
  solo l'id. `quarkus.mailer.mock` resta ai default di Quarkus (acceso in dev e test, spento in
  prod) e **non** si configura per prod: il `MockMailer` logga destinatario e corpo (dati
  personali). Annotare in `docs/verifiche.md` (fonte: guida Mailer di Quarkus, data).
  Host, porta, utente, password solo da variabili d'ambiente (App. B), vuote finché manca il provider.
- **Dati del negozio:** `ShopProfile.Info` espone anche `legalName`, `vatNumber`, `addressLine`,
  `postalCode`, `city`. `demo-shop.sql` aggiunge `vat_number = 'IT00000000000'` (inventata).
  `TenantDirectory.findById(String)` restituisce `ResolvedShop` (fuso e `primaryHost`).
- **Email** (template Qute `templates/email/order-confirmation.html` e `.txt`, escaping di default):
  - Da: `"<display_name> tramite Gerio" <ordini@…>` (indirizzo da `app.mail.from-address`,
    `${GERIO_MAIL_FROM:ordini@${app.platform.base-domain}}`); `Reply-To`: email del negozio;
    A: email del cliente.
  - Oggetto: `Conferma ordine 2026-0001 — Fioreria Il Glicine`.
  - Corpo (blocchi della ricerca): numero e data (fuso del negozio), righe con prezzi IVA inclusa,
    totale, «Carta», «Ritiro in negozio» con la data, venditore (ragione sociale, indirizzo con
    CAP e città, P. IVA, email, telefono), formula D3 della ricerca su recesso escluso («art. 59»)
    e «garanzia legale» (senza «24 mesi»: [DA VERIFICARE]), link
    `https://<primaryHost>/ordine/<token>` (sempre `https`, senza porta; mai da `UriInfo`).
  - Niente avviso soft spam, niente PDF.
- **Test esistenti:** `TestShops.deleteAll()` (non di accettazione) svuota `outbox_message`, se
  esiste, prima di `tenant`: altrimenti i test dei giri 011–014 che pagano un ordine falliscono
  per la chiave esterna.

## Dati del negozio demo
Ordine standard dei giri 010–014 (`placeDemoOrder`): cliente `Giulia Bianchi`,
`giulia.bianchi@example.com` (il modulo di checkout esistente; sostituisce «Marta Rossi» della
bozza); 1 × Mazzo di rose rosse — 12 rose 45,00 €, 2 × Orchidea Phalaenopsis bianca 29,90 €
(59,80 €), totale 104,80 €; ritiro 13/10/2026. `Clock` a `2026-10-11T22:30:00Z` (data ordine
12/10/2026 a Roma). Demo: `Il Glicine di Lucia Ferri`, `Via dei Fossi 12`, `55100 Lucca`,
P. IVA `IT00000000000`, `ciao@glicine.example`, `+39 0583 000000`.
Negozio di Marco (scritto dai test): `Fiori di Marco Srl`, `Via del Fosso Rosso 99`, `50100
Firenze`, P. IVA `IT09876543210`, `marco@example.test`.

## Fatto quando — controlli eseguibili
- [ ] Webhook `checkout.session.completed` pagato → 1 riga `EMAIL`/`ORDER_CONFIRMATION` del
      negozio demo, payload senza dati personali; dopo `drainOnce()` 1 email a
      `giulia.bianchi@example.com`, oggetto e mittente come sopra, `Reply-To` del negozio, riga `SENT`.
- [ ] HTML e testo contengono numero, data 12/10/2026, righe, importi, totale, «Carta», ritiro,
      dati del venditore con P. IVA, «recesso», «art. 59», «garanzia legale», e un solo link
      `https://demo.localhost/ordine/<token>`; quel link risponde 200 sul host del negozio.
- [ ] Stesso evento due volte, due eventi della stessa sessione, due eventi in parallelo → 1 riga,
      1 email. Due `drainOnce()` in parallelo → 1 email.
- [ ] Ordine in attesa, sessione `complete` con `payment_status=unpaid`, ordine scaduto → 0 righe,
      0 email.
- [ ] Isolamento: righe di demo e di Marco nello stesso `drainOnce()` → ognuna con i soli dati del
      suo negozio; link di ciascuno 200 sul proprio host, 404 sull'altro.
- [ ] SMTP che rifiuta la connessione → `PENDING`, `attempts` 1…7 con `next_attempt_at` esatto
      (clock + 1, 2, 4… minuti); riga non ancora scaduta non presa; all'ottavo errore `FAILED`, poi
      più nessun tentativo.
- [ ] Invio spento → 0 email, riga `PENDING` con `attempts=0`, nessun dato personale nel log.
- [ ] `pom.xml`: `quarkus-mailer` sì, `quarkus-scheduler`/`quarkus-quartz` no;
      `application.properties`: `app.mail.enabled` spento per default, `quarkus.mailer.mock` mai
      acceso fuori da dev e test.
- [ ] `./mvnw -B -ntp verify` verde (compresi i test dei giri precedenti).

## Fuori perimetro
- PDF delle condizioni in allegato (giro dedicato, porta a senso unico).
- Provider SMTP reale, account, DNS; SPF e DKIM completi (`traguardi.json`).
- Avviso soft spam e link «Non voglio ricevere comunicazioni» (impianto consensi fuori perimetro).
- Avviso al negozio (§9.3), Telegram, segnalazione all'admin oltre il log.
- Costo di consegna (T7), back-office, tipo `CEMETERY`, capacità per fascia.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|
| `Giro015ConfirmationEmailAcceptanceTest` | ordine pagato → 1 riga senza dati personali → `drainOnce()` → 1 email con oggetto, mittente, Reply-To, `SENT`; seconda passata nulla |
| idem | email pagata → HTML e testo con ordine, venditore, recesso, garanzia; link estratto dal corpo → 200 sul host demo |
| idem | evento ripetuto / due eventi della sessione → 1 riga, 1 email |
| idem | due webhook in parallelo → 1 riga, 1 email |
| idem | due `drainOnce()` in parallelo → 1 email, riga `SENT` |
| idem | in attesa, `complete`+`unpaid`, scaduto → 0 righe, 0 email |
| `Giro015ConfirmationEmailIsolationAcceptanceTest` | righe di demo e Marco, un `drainOnce()` → ogni email solo coi dati del suo negozio; link 200 sul proprio host, 404 sull'altro |
| `Giro015EmailRetryAcceptanceTest` (profilo SMTP su porta chiusa) | errori → `next_attempt_at` = clock+1, +2, +4… min; riga futura non presa; ottavo → `FAILED`, poi niente |
| `Giro015MailDisabledAcceptanceTest` (profilo `app.mail.enabled=false`, log su file) | nessuna email, riga `PENDING`, `attempts=0`, log senza dati del cliente |
| `Giro015MailConfigAcceptanceTest` | `pom.xml` con mailer e senza scheduler; proprietà: invio spento per default, mock mai in prod |

Aiuti: `Giro015Mail` (worker e `MockMailbox` raggiunti per nome, così i test compilano prima del
codice), `Giro015Data` (righe di `outbox_message` via JDBC, dati legali di Marco).

## Porte
- Doppio senso: migrazione additiva `V7`; proprietà nuove con default; `drainOnce()`; timer Vert.x.
- Senso unico: `quarkus-mailer` — **risolta** (opzione A). Il `pom.xml` è percorso protetto: la PR
  va in revisione umana (METODO §4).

## Rischi di concorrenza
- Webhook doppio o concorrente: indice unico parziale + scrittura nella transazione del `PAID`.
- Due worker (o due repliche): `for update skip locked` + lease di 5 minuti su `next_attempt_at`;
  nello stesso processo `AtomicBoolean` (niente passate sovrapposte del timer).
- Email spedita ma `SENT` non salvato (crash): possibile doppio invio, accettato (al più una
  volta in più; scritto nel rapporto).

## Budget
Righe Java di produzione ≤ 400 (template e SQL esclusi); file toccati ≤ 16; una sessione.

## Domande per il ricercatore (al massimo 3)
1. Cosa mettono Shopify, Etsy e i fioristi online italiani nell'email di conferma (ordine dei
   blocchi, oggetto, cosa c'è sopra la piega)?
2. Mittente «Negozio tramite piattaforma» + `Reply-To`: come appare nei client (Gmail, Outlook,
   iOS) e cosa evitare per non finire in spam senza DKIM completo?
3. Testo minimo italiano per recesso escluso (art. 59 Codice del consumo, beni deperibili) e
   garanzia legale in un'email di conferma.

## Critica ricevuta e cosa cambia
| Punto | Esito | Cosa cambia |
|---|---|---|
| 2. `TenantContext` request-scoped, `switchTo` solo Platform→Shop | Accolto | Presa come Platform; per riga nuovo request context, `set(Shop)`, bean `REQUIRES_NEW`; test di isolamento con righe di A e B nello stesso `drainOnce()` |
| 3. Lease, invio fuori transazione | Accolto | `next_attempt_at` +5 min alla presa, invio fuori transazione, poi `SENT`/errore |
| 4. `kind` nel payload, indice parziale, solo se `markPaid` true, `on conflict do nothing`, niente `@TenantId` | Accolto | Vedi «Comportamento deciso»; indice su `payload->>'orderId'` (gli id ordine sono globali) |
| 5. `MockMailer` logga dati personali | Accolto con variante | Interruttore `app.mail.enabled` (spento in prod), mock mai configurato in prod; test su log e proprietà; nota in `verifiche.md` |
| Ritentativi in una riga `2^(attempts-1)` | Accolto | Test con istanti esatti |
| `ShopProfile.Info` con dati legali; link da `TenantDirectory.primaryHost` | Accolto | Anche CAP e città (indirizzo completo del venditore) |
| Test: link dal corpo, istanti esatti, `unpaid`, paralleli | Accolti | Tutti nella tabella dei test |
| Facoltativi: fuso del negozio, escaping Qute, indice `(status, next_attempt_at)` | Accolti | Costano poche righe; escaping è il default Qute (nessun test dedicato) |
| Orchestratore: niente `quarkus-scheduler` | Accolto | Timer `vertx.setPeriodic`, test sul `pom.xml` |
| Non accolto | — | Cliente «Marta Rossi» della bozza: si usa il modulo esistente (Giulia Bianchi), per non toccare gli aiuti dei giri precedenti |

## Idee per dopo
- Anteprima dell'email per il negozio dal back-office (R2).
