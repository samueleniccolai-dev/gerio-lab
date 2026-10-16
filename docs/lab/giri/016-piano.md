---
giro: 016
traguardo: T6
fase: chiusura
inizio: 2026-10-15T14:02:30Z
ricerca: sì
accettazione: 78cfb80
ripreso: no
---
# Piano del giro 016 — condizioni di vendita: pagina e PDF allegato alla conferma (T6, parte 3)

## DOMANDA — RISPOSTA: opzione A (15/10, ⏱ 2', da Gerio: sì)

**Contesto.** SPEC §9.2 vuole in allegato all'email di conferma «il PDF delle condizioni di vendita
nella versione accettata (`terms_version`)»: un link non basta come supporto durevole (anche §12.3,
§15). Oggi il PDF non c'è, e non c'è nemmeno il testo: il checkout rimanda a
`/condizioni-di-vendita`, che risponde 404; l'ordine salva `terms_version = "2026-10-12"`
(costante in `CheckoutService`). Generare un PDF richiede una libreria o un PDF già pronto.

| Opzione | Pro | Contro |
|---|---|---|
| **A. `io.github.openhtmltopdf:openhtmltopdf-pdfbox`** (fork mantenuto del progetto citato da §9.2; versione 1.1.x [DA VERIFICARE]; licenza LGPL-2.1 [DA VERIFICARE]; porta con sé Apache PDFBox, FontBox, XmpBox) | Lo stesso template Qute fa la pagina e il PDF: un solo testo, nessuna deriva. È lo strumento indicato dalla SPEC. Le versioni successive (giro consegne PDF di R2, conferma per WhatsApp) lo riusano | Dipendenza pesante (alcuni MB) in `pom.xml` → PR in revisione umana. Font standard Helvetica (accenti italiani sì, simboli no) salvo font incorporato |
| B. Scrittore PDF minimale fatto in casa (nessuna dipendenza) | Nessuna porta; poche centinaia di righe | Solo testo semplice; formato PDF scritto a mano, difficile da verificare nei test senza un lettore PDF; codice da mantenere che non è il nostro prodotto |
| C. PDF statico nel repository (generato fuori) | Nessuna libreria | È un binario (porta comunque); uno per negozio e per versione, da rigenerare a mano; si scolla dal testo della pagina |
| D. Rimandare il PDF e passare a T7 | Nessuna porta ora | T6 resta in-corso; la conferma non è supporto durevole completo |

**Raccomandazione: A.** È la scelta della SPEC, tiene pagina e PDF sullo stesso template e serve
anche a R2. La versione esatta la fissa il ricercatore su Maven Central e la registra in
`docs/verifiche.md`. Dipendenza: `io.github.openhtmltopdf:openhtmltopdf-pdfbox`, versione
[DA VERIFICARE], motivo §9.2, alternativa B.

**Dopo ogni risposta.**
- **A:** si procede con il piano qui sotto; la PR va in revisione umana (`pom.xml`).
- **B:** stesso piano, ma il PDF è solo testo e il test di accettazione verifica intestazione
  `%PDF-`, `%%EOF` e la presenza del testo (stringhe non compresse).
- **C:** riscrivo la fetta: solo pagina `/condizioni-di-vendita` + PDF statico del negozio demo.
- **D:** questo giro fa solo la pagina `/condizioni-di-vendita` (oggi 404, nessuna porta) e il
  giro 017 passa a T7.

## Fetta e traguardo
T6, parte 3: il testo delle condizioni di vendita esiste (pagina pubblica per negozio) e lo stesso
testo, in PDF, va in allegato a ogni email di conferma. Ultima parte di T6 che dipende da noi:
dopo resta solo il provider SMTP reale, che è dell'umano.

## Cosa resta di T6 dopo questo giro
Provider SMTP UE (account, DNS, segreti, poi `GERIO_MAIL_ENABLED=true`): azione umana. Finché manca,
in produzione l'email non parte e T6 non si può dichiarare `passato`.

## Dati del negozio demo (già nel seed, si riusano)
Negozio `demo` con i dati legali del seed (`legalName`, `vatNumber`, `addressLine`, email,
telefono). Versione delle condizioni: `2026-10-12` (quella già salvata sugli ordini). Testo
inventato, in italiano, con queste sezioni (titoli esatti, usati dai test):
1. «Venditore» (dati legali del negozio, dal profilo)
2. «Prezzi e pagamento» («I prezzi sono in euro, IVA inclusa. Il pagamento avviene con carta tramite Stripe.»)
3. «Consegna e ritiro»
4. «Diritto di recesso» («Il diritto di recesso è escluso per i fiori freschi e le composizioni,
   beni che rischiano di deteriorarsi o scadere rapidamente (art. 59, comma 1, lettera d, Codice
   del consumo).» — la «è» serve al test degli accenti nel PDF)
5. «Garanzia legale e reclami»
6. In fondo: «Versione 2026-10-12».

Dati di Marco (già nei dati dei test del giro 015): «Fiori di Marco Srl», P.IVA `IT09876543210`,
«Via del Fosso Rosso 99». Demo: «Il Glicine di Lucia Ferri», P.IVA `IT00000000000`.

Email di conferma, parte HTML e parte testo: una riga «Le condizioni di vendita (versione
2026-10-12) sono in allegato.».

## Come (vincoli per il costruttore, dalla critica)
- **Template `legal/terms.html`: documento XHTML autonomo e ben formato.** `<!DOCTYPE html>`
  maiuscolo, tutti i tag chiusi (`<meta ... />`, `<br />`), nessuna entità con nome (`&nbsp;`,
  `&egrave;`…: caratteri UTF-8 o entità numeriche), nessun `<link>`/`<script>` a risorse. **Non**
  estende `storefront/layout.html`: il parser XML di openhtmltopdf lancerebbe `SAXParseException` e
  la riga dell'outbox ritenterebbe fino a FAILED. La pagina HTML usa lo stesso template (stile in
  `<style>` interno, minimo).
- **Versione dell'ordine.** `OrderLookup.Confirmation` riceve `termsVersion`. La costante
  `TERMS_VERSION` passa da `CheckoutService` a `legal` (es. `TermsDocument.CURRENT_VERSION`), e
  `CheckoutService` la legge da lì. `ConfirmationComposer` chiede a `TermsDocument` il PDF della
  versione dell'ordine; versione ignota → eccezione → la riga resta `PENDING` con `last_error` e si
  ritenta (nessuna email con un PDF sbagliato).
- **Date del PDF.** Data di creazione e di modifica fissate alla data della versione (mezzanotte
  `Europe/Rome`): `renderer.createPDFWithoutClosing()` → `getPdfDocument().getDocumentInformation()`
  → `save` su `ByteArrayOutputStream`, senza riaprire i byte. Non si chiede l'uguaglianza
  byte per byte tra due generazioni (il trailer `/ID` può variare): l'uguaglianza tra email e pagina
  vale grazie alla cache.
- **Cache e concorrenza.** Chiave `(tenantId, versione)`, con `tenantId` da `CurrentTenant.Shop`,
  mai dall'host. Il profilo del negozio si legge dentro la transazione; il PDF si genera fuori;
  in cache con `putIfAbsent` (si restituisce il valore già presente) o `computeIfAbsent` su dati già
  letti. Nessuna query dentro la funzione della cache.
- **Log.** `quarkus.log.category."com.openhtmltopdf".level=WARN` in `application.properties`.
- `noShopNoTerms` è verde già oggi (404): resta come guardia, non come prova del lavoro.

## Fatto quando — controlli eseguibili
- [ ] `GET /condizioni-di-vendita` con Host del negozio demo → 200, `text/html`, contiene i 5 titoli,
      la ragione sociale e la partita IVA di demo, «Il diritto di recesso è escluso», «Versione
      2026-10-12», non la P.IVA di Marco.
- [ ] Stessa richiesta con Host di Marco → 200 con ragione sociale, P.IVA e indirizzo di Marco,
      nessun dato di demo («Glicine», P.IVA, indirizzo, email).
- [ ] Host sconosciuto (`sconosciuto.localhost`) o della piattaforma (`localhost`) → 404 sia per la
      pagina sia per il `.pdf` (mai negozio indefinito).
- [ ] `GET /condizioni-di-vendita.pdf` (Host demo) → 200, `application/pdf`, corpo che inizia con
      `%PDF-`, `Content-Disposition` `inline; filename="condizioni-di-vendita-2026-10-12.pdf"`;
      testo estratto con PDFBox: 5 titoli, ragione sociale e P.IVA di demo, «Il diritto di recesso
      è escluso» (accenti), «Versione 2026-10-12», non la P.IVA di Marco.
- [ ] `.pdf` sull'host di Marco: testo con i dati di Marco, nessun dato di demo.
- [ ] Due richieste contemporanee del `.pdf` (stesso negozio, due thread, `CountDownLatch`) → stessi
      byte, e una richiesta successiva ancora gli stessi byte (cache per `(tenantId, versione)`).
- [ ] Ordine pagato di demo → `drainOnce()` → l'email ha **un** allegato
      `condizioni-di-vendita-2026-10-12.pdf`, tipo `application/pdf`, `%PDF-`, il cui testo ha i 5
      titoli, la P.IVA di demo, «Versione 2026-10-12», la frase accentata, non la P.IVA di Marco.
- [ ] Parte HTML e parte testo dell'email: «condizioni di vendita» e «in allegato».
- [ ] Due ordini pagati di demo → due email con allegati identici byte per byte, uguali al `.pdf`
      della pagina di demo.
- [ ] Isolamento: un passaggio con un ordine di demo e uno di Marco → l'allegato di Marco ha
      ragione sociale e P.IVA di Marco e niente di demo (testo uguale a quello del `.pdf` di Marco);
      quello di demo niente di Marco; entrambe le righe `SENT`.
- [ ] Ordine pagato con `terms_version = '2019-01-01'` (versione ignota) → nessuna email; la riga
      resta `PENDING`, `attempts = 1`, `last_error` valorizzato.
- [ ] `./mvnw -B -ntp verify` verde; nessun test esistente rotto.

## Fuori perimetro
- Provider SMTP reale, account, DNS; SPF e DKIM completi (`traguardi.json → fuori_perimetro`).
- Tabella `legal_document`, versioni multiple modificabili, `pdf_storage_key`, archiviazione su disco
  o S3: la versione resta una costante, il PDF si tiene in memoria per (negozio, versione).
- Altre pagine legali (privacy, cookie, informazioni sul venditore, accessibilità) e piè di pagina legale.
- Modulo di recesso per prodotti non deperibili; PDF della conferma per WhatsApp (R2); giro consegne in PDF (R2).
- Avviso soft spam e link «Non voglio ricevere comunicazioni» (impianto consensi fuori perimetro).
- Font incorporati e stile curato del PDF.
- Back-office, tipo `CEMETERY`, capacità per fascia oraria, costo di consegna (T7).

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|
| `Giro016TermsAcceptanceTest` | Host demo / Marco / piattaforma / sconosciuto → pagina e PDF con i dati giusti o 404; testo del PDF (titoli, P.IVA, accenti, versione); due richieste contemporanee → stessi byte |
| `Giro016TermsAttachmentAcceptanceTest` | ordine pagato → un allegato PDF con il testo giusto; «in allegato» nelle due parti; due email stesso negozio → allegati identici e uguali alla pagina; Marco ≠ demo; versione ignota → nessuna email, riga ritentata |
| `Giro016Terms` (supporto) | PDFBox e `MockMailbox` raggiunti per nome: senza la dipendenza i test falliscono con un'asserzione, non con un errore di compilazione |

## Porte
- Doppio senso previste: nuova risorsa `storefront/TermsResource` e template `legal/terms.html`;
  servizio `legal/TermsDocument` (testo + PDF in cache); allegato in `ConfirmationComposer`; riga nel
  testo dell'email. Nessuna migrazione.
- Senso unico: **DOMANDA** sopra (dipendenza per il PDF).

## Rischi di concorrenza
Cache del PDF: due worker (o due richieste) possono generarlo insieme la prima volta. Profilo letto
nella transazione, generazione fuori; `putIfAbsent` restituisce il PDF già in cache a chi arriva
secondo, così tutti vedono gli stessi byte. Chiave `(tenantId da CurrentTenant, versione)`. Test a
due thread con `CountDownLatch`. Nessun nuovo stato in database.

## Budget
Righe di produzione ≤ 250; file ≤ 8; una sessione.

## Domande per il ricercatore (al massimo 3)
1. Versione corrente e licenza di `io.github.openhtmltopdf:openhtmltopdf-pdfbox` su Maven Central;
   funziona su Java 25 e in modalità JVM di Quarkus senza configurazione extra?
2. Come rendere il PDF deterministico (data di creazione/ID del documento fissi) con openhtmltopdf/PDFBox.
3. Testo vigente degli artt. 49 e 59 del Codice del consumo sull'esclusione del recesso per beni
   deperibili, per la frase della sezione «Diritto di recesso» (non parere legale).

## Idee per dopo
- Tabella `legal_document` con versioni e PDF archiviati, quando il negozio potrà modificare il testo.
- Piè di pagina legale su ogni pagina con link alle condizioni.
- Quando il profilo del negozio sarà modificabile, la chiave della cache dovrà includere una
  versione del profilo (o la cache si svuota alla modifica).
- Ordini al banco con `terms_version` null: nessun allegato (oggi la colonna è `not null`).

## Critica ricevuta e cosa cambia
Accolti tutti e 3 i «DA CORREGGERE» e i facoltativi:
1. `legal/terms.html` XHTML autonomo e ben formato, non estende il layout (sezione «Come»).
2. `termsVersion` in `OrderLookup.Confirmation`; PDF della versione dell'ordine; versione ignota →
   eccezione e nuovo tentativo; `TERMS_VERSION` spostata in `legal` (sezione «Come», test
   `anOrderWithAnUnknownTermsVersionGetsNoMailAndItsRowIsRetried`).
3. Date fissate alla data della versione via `createPDFWithoutClosing()`, senza riaprire i byte;
   tolto il requisito «byte identici tra due generazioni»; identità tra email e pagina grazie alla
   cache; confronto tra negozi sul testo estratto con PDFBox.
- Concorrenza: profilo letto nella transazione, generazione fuori, `putIfAbsent`, chiave dal
  `CurrentTenant`; test a due thread con `CountDownLatch`.
- Test aggiunti: testo estratto (titoli, P.IVA, versione, «è»), Marco ≠ demo nell'allegato,
  `.pdf` su host piattaforma/sconosciuto → 404, «in allegato» nelle due parti, versione ignota.
- Facoltativi: soglia del logger openhtmltopdf a `WARN`; due note in «Idee per dopo».
Scartato: nulla. Nota: la «versione diversa ma nota» non si prova perché esiste una sola versione;
si prova la versione ignota.
