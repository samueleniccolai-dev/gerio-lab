---
giro: 015
traguardo: T6
fase: critica
inizio: 2026-10-15T12:51:40Z
ricerca: sì
accettazione:
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

## Fetta e traguardo
T6, seconda parte (con l'opzione A): coda di uscita scritta dal webhook nella stessa transazione
del `PAID`, job che la svuota con ritentativi, email di conferma HTML + testo con i contenuti di
§9.2 che esistono già e il link alla pagina di stato. Resta: PDF in allegato, provider reale.

## Comportamento deciso
- **Migrazione** `V7__outbox.sql`: tabella `outbox_message` come SPEC §6 (`tenant_id`, `channel`,
  `payload jsonb`, `status`, `attempts`, `next_attempt_at`, `last_error`) più `created_at` e un
  indice unico parziale su `(tenant_id, payload->>'orderId')` per `kind = ORDER_CONFIRMATION`
  (doppio webhook = una sola email). Solo aggiunte.
- **Webhook:** quando `StripeEventProcessor` porta l'ordine a `PAID`, nella stessa transazione
  scrive `EMAIL`/`ORDER_CONFIRMATION` con il solo id dell'ordine nel payload (niente dati
  personali duplicati nella coda). Evento ripetuto o ordine già `PAID`: nessuna riga nuova.
- **Job** `OutboxWorker` `@Scheduled(every = "30s")`, `@ActivateRequestContext`, imposta il
  `TenantContext` dalla riga prima di leggere l'ordine. Ritentativi: attesa 1, 2, 4… minuti
  (`Clock`), massimo 8 tentativi, poi `FAILED` e log ERROR. Metodo `drainOnce()` pubblico per i
  test (job spento nei test con `quarkus.scheduler.enabled=false` nel profilo test).
- **Email** (template Qute in `templates/email/`, HTML + testo):
  - Da: `"Fioreria Il Glicine tramite Gerio" <ordini@${LAB_BASE_DOMAIN}>` (variabile
    `GERIO_MAIL_FROM`); `Reply-To`: `ciao@glicine.example`; A: email del cliente.
  - Oggetto: `Conferma ordine 2026-0001 — Fioreria Il Glicine`.
  - Corpo: venditore (ragione sociale `Il Glicine di Lucia Ferri`, indirizzo, email, telefono,
    P. IVA dal negozio demo), numero e data, righe con prezzi IVA inclusa, totale, «Carta (Stripe)»,
    «Ritiro in negozio» con la data, recesso escluso per i fiori freschi, garanzia legale e
    reclamo con i contatti, link `https://<host del negozio>/ordine/<token>`.
  - Niente avviso soft spam (consensi fuori perimetro); niente PDF.
- **Produzione:** `quarkus.mailer.mock=${GERIO_MAIL_MOCK:true}`; host, porta, utente e password
  solo da variabili d'ambiente, vuote finché non c'è il provider.

## Dati del negozio demo
Ordine standard dei giri 010–014 (`placeDemoOrder`), cliente inventato `Marta Rossi`,
`marta.rossi@example.com`; `Clock` a `2026-10-11T22:30:00Z` (data ordine 12/10/2026 a Roma).
Se il demo non ha indirizzo o P. IVA, si aggiungono in `DemoSeed`: `Via dei Fossi 12, 55100
Lucca`, P. IVA `IT00000000000` (inventata).

## Fatto quando — controlli eseguibili
- [ ] Webhook `checkout.session.completed` firmato → `MockMailbox` contiene 1 email per
      `marta.rossi@example.com` dopo `drainOnce()`, oggetto come sopra, `Reply-To` del negozio.
- [ ] Corpo HTML e testo contengono `2026-0001`, `12/10/2026`, le righe e il totale in euro,
      `Il Glicine di Lucia Ferri`, la P. IVA, «recesso», e il link `/ordine/<token>` che risponde 200.
- [ ] Stesso evento inviato due volte → ancora 1 email.
- [ ] Ordine `PENDING_PAYMENT` o `EXPIRED` → 0 email.
- [ ] Isolamento: ordine del negozio A pagato → nessuna email con dati di B; la riga di coda
      ha `tenant_id` di A.
- [ ] SMTP che fallisce (mailer che lancia) → riga `PENDING`, `attempts=1`, `next_attempt_at`
      +1 minuto; all'ottavo errore `FAILED`.
- [ ] `./mvnw -B -ntp verify` verde.

## Fuori perimetro
- PDF delle condizioni in allegato (giro dedicato, porta a senso unico).
- Provider SMTP reale, account, DNS; SPF e DKIM completi (`traguardi.json`).
- Avviso soft spam e link «Non voglio ricevere comunicazioni» (impianto consensi fuori perimetro).
- Avviso al negozio (§9.3), Telegram, segnalazione all'admin oltre il log.
- Costo di consegna (T7), back-office, tipo `CEMETERY`, capacità per fascia.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|
| `Giro015ConfirmationEmailAcceptanceTest` | (dopo la risposta, in modalità FINALE) |

## Porte
- Doppio senso previste: migrazione additiva `V7`; nuove proprietà con default; `drainOnce()`.
- Senso unico: **DOMANDA** sopra (`quarkus-mailer`).

## Rischi di concorrenza
- Webhook doppio o concorrente: indice unico parziale + scrittura nella transazione del `PAID`.
- Due worker (o due repliche): `select … for update skip locked` sulla riga.
- Email spedita ma `SENT` non salvato (crash): possibile doppio invio, accettato (al più una
  volta in più; scritto nel rapporto).

## Budget
Righe di produzione ≤ 350; file ≤ 12; una sessione.

## Domande per il ricercatore (al massimo 3)
1. Cosa mettono Shopify, Etsy e i fioristi online italiani nell'email di conferma (ordine dei
   blocchi, oggetto, cosa c'è sopra la piega)?
2. Mittente «Negozio tramite piattaforma» + `Reply-To`: come appare nei client (Gmail, Outlook,
   iOS) e cosa evitare per non finire in spam senza DKIM completo?
3. Testo minimo italiano per recesso escluso (art. 59 Codice del consumo, beni deperibili) e
   garanzia legale in un'email di conferma.

## Critica ricevuta e cosa cambia
(dopo il critico)

## Idee per dopo
- Anteprima dell'email per il negozio dal back-office (R2).
