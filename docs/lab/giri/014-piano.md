---
giro: 014
traguardo: T6
fase: critica
inizio: 2026-10-15T12:18:01Z
ricerca: sì
accettazione: -
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
  **404** con la pagina «non trovato» esistente, identica nei quattro casi.
- **Intestazioni della risposta 200:** `Cache-Control: no-store`, `Referrer-Policy: no-referrer`
  (il token sta nell'URL), `X-Robots-Tag: noindex`.
- **Contenuto** (nessun dato personale del cliente: niente nome, email, telefono):
  - titolo `Ordine 2026-0001` e nome del negozio;
  - stato in italiano: `PENDING_PAYMENT` «In attesa di pagamento», `PAID` «Pagato»,
    `EXPIRED` «Pagamento non completato» (gli altri stati di §7.1 hanno già l'etichetta in una
    mappa: `CONFIRMED` «Confermato», `IN_PREPARATION` «In preparazione», `READY_FOR_PICKUP`
    «Pronto per il ritiro», `OUT_FOR_DELIVERY` «In consegna», `DELIVERED` «Consegnato»,
    `CANCELED` «Annullato»);
  - data dell'ordine nel fuso del negozio, `gg/mm/aaaa`;
  - «Ritiro in negozio» con la data di ritiro `gg/mm/aaaa`;
  - righe: nome (snapshot), quantità, prezzo unitario e totale riga IVA inclusa;
  - costo di consegna solo se > 0 (oggi sempre 0: solo ritiro), totale;
  - contatti del negozio: telefono ed email (per domande sull'ordine);
  - se `EXPIRED`: link «Torna al carrello» (`/carrello`).
- **Pagina di esito:** quando l'ordine è `PAID`, aggiunge il link «Segui il tuo ordine» verso
  `/ordine/{token}`. Negli altri stati nessun link (la pagina di esito resta senza dati personali).
- Logica nel servizio dell'ordine (`OrderLookup` o un servizio nuovo in `order/`), risorsa REST
  in `storefront/` senza logica, etichette e formati fuori dal template.

## Dati del negozio demo (già esistenti, dai giri 005–011)
Negozio `Il Glicine di Lucia Ferri`, telefono `+39 0583 000000`, email `ciao@glicine.example`.
Ordine dei test: l'ordine standard dei giri 010–011 (`placeDemoOrder`), con `Clock` a
`2026-10-12T08:00:00Z`:

| Riga | Quantità | Unitario | Totale riga |
|---|---|---|---|
| Mazzo di rose rosse — 12 rose | 1 | 45,00 € | 45,00 € |
| Orchidea Phalaenopsis bianca — Vaso 12 cm | 2 | 29,90 € | 59,80 € |

Numero `2026-0001`, data ordine `12/10/2026`, ritiro `13/10/2026`, totale `104,80 €`; cliente
Giulia Bianchi, `giulia.bianchi@example.com`, `+39 333 000 1234` (non devono comparire).
Gli importi si confrontano con lo spazio indivisibile come nei test esistenti (`NBSP`).

## Fatto quando — controlli eseguibili
- [ ] Ordine demo pagato (webhook `checkout.session.completed` finto) → `GET /ordine/esito?session_id=…`
      contiene un link «Segui il tuo ordine» con `href="/ordine/<token di 43 caratteri>"`.
- [ ] Seguendo quel link sull'Host demo → 200, contiene `Ordine 2026-0001`, `Pagato`,
      `12/10/2026`, `Ritiro in negozio`, `13/10/2026`, le due righe con quantità e importi della
      tabella, `104,80 €`, `+39 0583 000000`, `ciao@glicine.example`; non contiene `Giulia`,
      `giulia.bianchi@example.com`, `333 000 1234`.
- [ ] La stessa risposta ha `Cache-Control: no-store`, `Referrer-Policy: no-referrer`,
      `X-Robots-Tag: noindex`.
- [ ] Ordine demo appena creato (non pagato; token letto via JDBC) → 200 con
      `In attesa di pagamento`, senza link al carrello.
- [ ] Ordine demo scaduto (webhook `checkout.session.expired` finto) → 200 con
      `Pagamento non completato` e link a `/carrello`.
- [ ] Isolamento: il token di un ordine del demo sull'Host del negozio B dei test (`MARCO`) → 404;
      sull'Host della piattaforma → 404.
- [ ] Token sconosciuto ben formato (43 × `A`), token malformato (`abc`, 44 caratteri,
      `..%2F..`) → 404 sull'Host demo.
- [ ] Ordine pendente → la pagina di esito non contiene link a `/ordine/` diversi da
      `/ordine/esito`.
- [ ] `./mvnw -B -ntp verify` verde (tutti i test precedenti compresi); `./mvnw spotless:check`.
- [ ] Dopo il deploy: `https://glicine.<LAB_BASE_DOMAIN>/ordine/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA`
      → 404 (non 500). Comando nel piano, non in JUnit.

## Fuori perimetro
- Email di conferma, `outbox_message`, job di invio, estensione Mailer, provider SMTP, PDF delle
  condizioni (giro 015, con domanda).
- Avvisi al negozio (email, Telegram, §9.3).
- Aggiornamento automatico della pagina di stato (polling htmx o meta refresh).
- Destinatario, indirizzo, fascia, messaggio del biglietto: non esistono ancora nell'ordine (T7).
- Nuovi stati o transizioni oltre `PAID` / `EXPIRED`; annullo, rimborso, «riprova il pagamento».
- Migrazioni: nessuna (il `public_token` esiste da V5).
- Da `traguardi.json → fuori_perimetro`: back-office del negozio (piano B: email e dashboard
  Stripe); tipo di consegna `CEMETERY`; capacità per fascia oraria; impianto consensi e GDPR oltre
  la pagina privacy; SPF e DKIM completi.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|
| `Giro014OrderStatusAcceptanceTest` | ordine demo pagato → esito con link → pagina di stato con contenuti, intestazioni, senza dati personali |
| `Giro014OrderStatusAcceptanceTest` | ordine pendente / scaduto → etichetta giusta; link al carrello solo se scaduto; esito pendente senza link |
| `Giro014OrderStatusIsolationAcceptanceTest` | token demo su Host B o piattaforma → 404; token sconosciuto o malformato → 404 |

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
Righe di produzione ≤ 200; file ≤ 6 (servizio o metodo in `order/`, risorsa, template, ritocco a
`outcome.html` e a `OutcomeResource`/`OrderLookup`). Deve stare in una sessione.

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

## Critica ricevuta e cosa cambia
(dopo il critico)
