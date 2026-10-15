---
giro: 016
traguardo: T6
fase: domanda (da: critica)
inizio: 2026-10-15T14:02:30Z
ricerca: sì
accettazione:
ripreso: no
---
# Piano del giro 016 — condizioni di vendita: pagina e PDF allegato alla conferma (T6, parte 3)

## DOMANDA (porta a senso unico: dipendenza nuova)

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
4. «Diritto di recesso» («escluso per i fiori freschi e le composizioni, beni deperibili (art. 59, comma 1, lettera d, Codice del consumo)»)
5. «Garanzia legale e reclami»
6. In fondo: «Versione 2026-10-12».

## Fatto quando — controlli eseguibili
- [ ] `GET /condizioni-di-vendita` con Host del negozio demo → 200, `text/html`, contiene i 5 titoli,
      la ragione sociale e la partita IVA di demo, «Versione 2026-10-12».
- [ ] Stessa richiesta con Host del secondo negozio (Marco) → 200 con i dati di Marco, nessun dato di demo.
- [ ] Host sconosciuto o della piattaforma → 404 (mai negozio indefinito).
- [ ] `GET /condizioni-di-vendita.pdf` (Host demo) → 200, `application/pdf`, corpo che inizia con
      `%PDF-`, con `Content-Disposition` `inline; filename="condizioni-di-vendita-2026-10-12.pdf"`.
- [ ] Ordine pagato di demo → `drainOnce()` → l'email in `MockMailbox` ha **un** allegato
      `condizioni-di-vendita-2026-10-12.pdf`, tipo `application/pdf`, che inizia con `%PDF-`.
- [ ] Isolamento: email di un ordine di Marco → allegato con i dati di Marco (testo estratto dal PDF
      o confronto byte con `GET /condizioni-di-vendita.pdf` sull'host di Marco), diverso da demo.
- [ ] Il PDF di una versione si genera una sola volta per negozio: due email dello stesso negozio →
      allegati identici byte per byte (nessuna data di generazione variabile nel PDF).
- [ ] Il testo dell'email dice che le condizioni sono in allegato.
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
| `Giro016TermsAcceptanceTest` | Host demo / Marco / sconosciuto → pagina con dati giusti / 404; PDF `%PDF-` con nome file della versione |
| `Giro016TermsAttachmentAcceptanceTest` | ordine pagato → email con un allegato PDF; due email stesso negozio → allegati identici; Marco ≠ demo |

## Porte
- Doppio senso previste: nuova risorsa `storefront/TermsResource` e template `legal/terms.html`;
  servizio `legal/TermsDocument` (testo + PDF in cache); allegato in `ConfirmationComposer`; riga nel
  testo dell'email. Nessuna migrazione.
- Senso unico: **DOMANDA** sopra (dipendenza per il PDF).

## Rischi di concorrenza
Cache del PDF: due worker possono generarlo insieme la prima volta; innocuo se la generazione è
deterministica (stesso contenuto) e la cache è `ConcurrentHashMap.computeIfAbsent`. Generazione
fuori dalla transazione del worker, per non tenerla aperta. Nessun nuovo stato in database.

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

## Critica ricevuta e cosa cambia
(dopo il critico)
