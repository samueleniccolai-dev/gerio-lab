---
giro: 014
traguardo: T6
fase: costruzione
inizio: 2026-10-15T12:18:01Z
ricerca: sì
accettazione: 46ec0dc
ripreso: no
---
# Piano del giro 014 — pagina di stato dell'ordine

## Fetta e traguardo
T6, prima metà: la pagina di stato dell'ordine raggiungibile dal link segreto (`public_token`,
SPEC §2.2 e §7.2), collegata dalla pagina di esito. L'email di conferma (SPEC §9) resta per il
giro dopo: richiede l'estensione Mailer, un provider SMTP e un generatore di PDF, cioè porte a
senso unico (dipendenze nuove, possibile spesa) che non devono bloccare la parte che non ne ha.

**Cosa resta di T6 dopo questo giro:** coda `outbox_message` scritta dal webhook nella stessa
transazione del `PAID`, job di invio con ritentativi, template Qute HTML + testo con i contenuti
di §9.2, link alla pagina di stato di questo giro, PDF delle condizioni in allegato. Il giro 015
si apre con la DOMANDA sulle dipendenze (`quarkus-mailer`; generatore PDF; provider SMTP UE).

## Comportamento deciso
- **URL:** `GET /ordine/{token}` solo su Host di negozio. `token` = `public_token` (43 caratteri
  base64url, `[A-Za-z0-9_-]{43}`). `/ordine/esito` resta com'è (percorso letterale, ha la
  precedenza). Token malformato, sconosciuto, di un altro negozio, o Host della piattaforma:
  **404** con la pagina `errors/not-found.html` (corpo identico nei quattro casi). Non c'è un
  mapper globale: la risorsa disegna lei la pagina, come `OutcomeResource`. Il formato del token
  si controlla **nel servizio** (`@Path("/ordine/{token}")` senza regex), così anche il token
  malformato riceve la stessa pagina e non il 404 di RESTEasy («Resource not found»).
- **Intestazioni della risposta 200:** `Cache-Control: no-store`, `Referrer-Policy: no-referrer`
  (il token sta nell'URL), `X-Robots-Tag: noindex`.
- **Contenuto** (nessun dato personale del cliente: niente nome, email, telefono):
  - titolo `Ordine 2026-0001` e nome del negozio (`display_name`, come nelle altre pagine);
  - stato in italiano con `label()` su `OrderStatus`, solo i 3 valori esistenti:
    `PENDING_PAYMENT` «In attesa di pagamento», `PAID` «Pagato», `EXPIRED` «Pagamento non
    completato». Nessuno stato nuovo;
  - data dell'ordine (`created_at`) nel fuso del negozio, `gg/mm/aaaa`;
  - «Ritiro in negozio» con la data di ritiro `gg/mm/aaaa`;
  - righe in ordine di id: nome (snapshot), quantità, prezzo unitario e totale riga IVA inclusa;
  - totale (il costo di consegna arriva con T7);
  - contatti del negozio: telefono ed email (per domande sull'ordine);
  - se `EXPIRED`: link «Torna al carrello» (`/carrello`) dentro `<main>` (il link «Carrello»
    dell'intestazione di `layout.html` c'è in ogni pagina e non conta).
- **Pagina di esito:** quando l'ordine è `PAID`, aggiunge il link «Segui il tuo ordine» verso
  `/ordine/{token}`. Negli altri stati nessun link (la pagina di esito resta senza dati personali).
- Logica in `OrderLookup.status(token)`: controlla il formato, cerca per `public_token` (filtrato
  dal negozio della richiesta) e restituisce un record vista (numero, etichetta, date già
  formattate, righe, totale, contatti). `Outcome` riceve anche il token, per il link. Risorsa
  REST in `storefront/` senza logica; etichette e formati fuori dal template.

## Dati del negozio demo (già esistenti, dai giri 005–011)
Negozio demo: nome mostrato (`display_name`) `Fioreria Il Glicine` (la ragione sociale «Il
Glicine di Lucia Ferri» non serve), telefono `+39 0583 000000`, email `ciao@glicine.example`.
Ordine dei test: l'ordine standard dei giri 010–011 (`placeDemoOrder`). Per l'ordine pagato il
`Clock` è a `2026-10-11T22:30:00Z` (00:30 del 12/10 a Roma, ancora 11/10 in UTC); per gli altri
a `2026-10-12T08:00:00Z`:

| Riga | Quantità | Unitario | Totale riga |
|---|---|---|---|
| Mazzo di rose rosse — 12 rose | 1 | 45,00 € | 45,00 € |
| Orchidea Phalaenopsis bianca — Vaso 12 cm | 2 | 29,90 € | 59,80 € |

Numero `2026-0001`, data ordine `12/10/2026`, ritiro `13/10/2026`, totale `104,80 €`; cliente
Giulia Bianchi, `giulia.bianchi@example.com`, `+39 333 000 1234` (non devono comparire).
Gli importi si confrontano con lo spazio indivisibile come nei test esistenti (`NBSP`).

Negozio B (`fiori-di-marco`, «Fiori di Marco»): un ordine scritto via JDBC dai test, numero
`2026-0001`, `PAID`, ritiro 13/10/2026, 25,00 €, cliente inventato Paolo Verdi, token
`Giro014MarcoToken_abcdefghijklmnopqrstuvwxy` (43 caratteri).

## Fatto quando — controlli eseguibili
- [ ] Ordine demo pagato (webhook `checkout.session.completed` finto) → `GET /ordine/esito?session_id=…`
      contiene un link «Segui il tuo ordine» con `href="/ordine/<token di 43 caratteri>"`.
- [ ] Seguendo quel link sull'Host demo → 200, contiene `Ordine 2026-0001`, `Fioreria Il
      Glicine`, `Pagato`, `12/10/2026` (e non `11/10/2026`: ordine fatto alle 22:30 UTC),
      `Ritiro in negozio`, `13/10/2026`, le due righe nell'ordine della tabella con quantità e
      importi, `104,80 €`, `+39 0583 000000`, `ciao@glicine.example`; non contiene `Giulia`,
      `giulia.bianchi@example.com`, `333 000 1234`, né «Torna al carrello».
- [ ] La stessa risposta ha `Cache-Control: no-store`, `Referrer-Policy: no-referrer`,
      `X-Robots-Tag: noindex`.
- [ ] Ordine demo appena creato (non pagato; token letto via JDBC) → 200 con
      `In attesa di pagamento`, senza «Torna al carrello» e senza `href="/carrello"` in `<main>`.
- [ ] Ordine demo scaduto (webhook `checkout.session.expired` finto) → 200 con
      `Pagamento non completato` e `<a href="/carrello">Torna al carrello</a>` dentro `<main>`.
- [ ] Isolamento nei due versi: token demo sull'Host `MARCO` → 404; token di Marco sull'Host demo
      → 404; token demo sull'Host della piattaforma → 404. Controlli positivi: ciascun token
      sul proprio Host → 200 (la pagina di Marco mostra «Fiori di Marco», non «Paolo»).
- [ ] Token sconosciuto ben formato (43 × `A`) → 404 con «Pagina non trovata»; token malformati
      (`abc`, 44 e 42 caratteri, 42 + `.`, 42 + `~`) → 404 con **lo stesso corpo**; anche i 404
      dell'isolamento hanno lo stesso corpo.
- [ ] Ordine pendente → la pagina di esito non contiene link a `/ordine/` diversi da
      `/ordine/esito`.
- [ ] `./mvnw -B -ntp verify` verde (tutti i test precedenti compresi); `./mvnw spotless:check`.
- [ ] Dopo il deploy: `curl -s -o /dev/null -w '%{http_code}'
      https://demo.<LAB_BASE_DOMAIN>/ordine/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` → `404`
      (non 500), corpo con «Pagina non trovata». Comando nel piano, non in JUnit.

## Fuori perimetro
- Email di conferma, `outbox_message`, job di invio, estensione Mailer, provider SMTP, PDF delle
  condizioni (giro 015, con domanda).
- Avvisi al negozio (email, Telegram, §9.3).
- Aggiornamento automatico della pagina di stato (polling htmx o meta refresh).
- Destinatario, indirizzo, fascia, messaggio del biglietto: non esistono ancora nell'ordine (T7).
- Nuovi stati o transizioni oltre `PAID` / `EXPIRED` (nemmeno le etichette degli stati futuri);
  annullo, rimborso, «riprova il pagamento».
- Costo di consegna sulla pagina (T7, con la consegna a domicilio).
- Mapper globale dei 404; modifiche a `infra/Caddyfile`.
- Scadenza del link e limite di frequenza (dalla ricerca: idee per dopo).
- Migrazioni: nessuna (il `public_token` esiste da V5).
- Da `traguardi.json → fuori_perimetro`: back-office del negozio (piano B: email e dashboard
  Stripe); tipo di consegna `CEMETERY`; capacità per fascia oraria; impianto consensi e GDPR oltre
  la pagina privacy; SPF e DKIM completi.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|
| `Giro014OrderStatusAcceptanceTest` | ordine demo pagato alle 22:30 UTC → esito con link → pagina di stato con contenuti, data nel fuso di Roma, righe in ordine, senza dati personali |
| `Giro014OrderStatusAcceptanceTest` | ordine pagato → `Cache-Control`, `Referrer-Policy`, `X-Robots-Tag` |
| `Giro014OrderStatusAcceptanceTest` | ordine pendente / scaduto → etichetta giusta; «Torna al carrello» in `<main>` solo se scaduto; esito pendente senza link a `/ordine/<token>` |
| `Giro014OrderStatusIsolationAcceptanceTest` | token demo su Host Marco o piattaforma, token di Marco su Host demo → 404 con lo stesso corpo; token sconosciuto o malformato → stesso 404 |

Aiutante: `Giro014Data` (token via JDBC, ordine di Marco via JDBC, testo della pagina, `<main>`).

## Porte
- Doppio senso previste: percorso `/ordine/{token}`, testi ed etichette, formato delle date,
  intestazioni di risposta, link dalla pagina di esito. Nessuna migrazione.
- Senso unico: nessuna in questa fetta. Anticipo per il giro 015: `quarkus-mailer` (dipendenza
  nuova), generatore PDF (per esempio OpenHTMLtoPDF), account presso un provider SMTP UE.

## Rischi di concorrenza
Pagina di sola lettura. Il webhook può segnare `PAID` mentre la pagina si carica: si vede lo
stato letto, il ricaricamento mostra quello nuovo (`no-store`). Nessuna scrittura, nessun blocco.
Ricerca per `public_token` con indice unico: tempo indipendente dal valore quanto basta, con 256
bit di entropia.

## Budget
Righe di produzione ≤ 200; file ≤ 6 (`OrderLookup.status` e record vista, `label()` in
`OrderStatus`, risorsa `OrderStatusResource`, template `order-status.html`, ritocco a
`outcome.html`). Deve stare in una sessione.

## Domande per il ricercatore (al massimo 3)
1. Cosa mostrano le pagine «stato ordine» via link segreto dei piccoli e-commerce italiani di
   fiori (o Shopify «order status page»): campi, etichette degli stati, contatti?
2. Su una pagina raggiunta solo da link segreto conviene mostrare dati del cliente o del
   destinatario (nome, indirizzo), o è meglio la minimizzazione (GDPR art. 5) come nel piano?
3. `Referrer-Policy: no-referrer` e `noindex` bastano per un URL con token, o servono altre
   cautele usuali (scadenza del link, `rel="noreferrer"` sui link in uscita)?

## Idee per dopo
- Pagina di stato con cronologia degli eventi (`order_event`) quando arriveranno gli stati del
  negozio (R2).
- Link «Segui il tuo ordine» anche nella pagina di esito in attesa, se la ricerca lo consiglia.
- Scadenza del link (SPEC §7.2) e limite di frequenza per IP su `/ordine/{token}` (ricerca).
- `infra/Caddyfile` imposta già `Referrer-Policy: strict-origin-when-cross-origin` su tutto: in
  produzione la risposta può avere due `Referrer-Policy`. Il costruttore lo annota nel rapporto;
  la correzione (per esempio `?Referrer-Policy` in Caddy, solo se assente) tocca `infra/` e va in
  revisione umana, in un giro suo.
- L'esito pagato promette «Riceverai un'email di conferma»: l'email arriva col giro 015.

## Critica ricevuta e cosa cambia
Accolti tutti e 4 i «DA CORREGGERE»:
1. Link al carrello: il test cerca «Torna al carrello» e l'`<a href="/carrello">` dentro `<main>`,
   perché `layout.html` ha già «Carrello» nell'intestazione.
2. Nome negozio: `Fioreria Il Glicine` (`display_name`), non la ragione sociale.
3. Controllo dopo il deploy su `https://demo.<LAB_BASE_DOMAIN>/…`.
4. 404 identico: formato del token controllato nel servizio, niente regex in `@Path`; la risorsa
   disegna `errors/not-found.html`; il test confronta i corpi (sconosciuto = malformato = altro
   negozio = piattaforma) e cerca «Pagina non trovata».

Accolte le semplificazioni: `OrderLookup.status(token)` con record vista, token in `Outcome`,
`label()` su `OrderStatus` con i 3 valori (via la mappa degli stati futuri), costo di consegna
rimandato a T7.

Accolti i suggerimenti sui test: ordine pagato con `Clock` a 22:30 UTC (data 12/10, mai 11/10);
`..%2F..` tolto (il comportamento dipende dalla normalizzazione di Vert.x, non dal nostro codice;
al suo posto 42 caratteri, 42 + `.`, 42 + `~`); righe controllate in ordine; isolamento anche nel
verso opposto con un ordine di Marco scritto via JDBC, più i controlli positivi.

Facoltativi: doppio `Referrer-Policy` e email del giro 015 messi in «Idee per dopo», da annotare
nel rapporto. Niente scartato.
