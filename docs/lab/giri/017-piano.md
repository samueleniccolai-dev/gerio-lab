---
giro: 017
traguardo: T7
fase: accettazione
inizio: 2026-10-16T09:31:01Z
ricerca: sì
accettazione: 4274ce0
ripreso: no
---
# Piano del giro 017 — zone di consegna e orario limite (T7)

## Fetta e traguardo
T7 intero: al checkout il cliente sceglie ritiro o consegna a domicilio; la consegna è possibile
solo nelle zone attive del negozio (per CAP o per comune), con il loro costo e ordine minimo, calcolati
sul server; per oggi si ordina solo prima dell'orario limite (SPEC §2.3, §7.3). T6 resta in-corso
solo per azioni umane; T7 è il primo traguardo con lavoro per la catena. Ci sta in una sessione
perché non c'è calendario a fasce: una tabella, colonne nuove sull'ordine, un servizio, il modulo.

## Dati del negozio demo (inventati, li usano i test)

Fioreria Il Glicine (`demo`, Lucca). In `demo/demo-shop.sql`, idempotente (chiave naturale
`(tenant_id, name)`):

| Zona | CAP | Comuni | Costo | Ordine minimo | Attiva |
|---|---|---|---|---|---|
| Lucca centro | `55100` | `Lucca` | 500 | 2500 | sì |
| Capannori e Porcari | `55012`, `55016` | `Capannori`, `Porcari` | 900 | 3500 | sì |
| Versilia | `55049` | `Viareggio` | 1500 | 5000 | **no** |

`tenant.settings` del demo: `{"orderCutoff": "12:00", "whatsapp": "+393330000000"}` (numero inventato).

Negozio di Marco (dati di test, inseriti dal test via JDBC, non dal seed): zona «Milano centro»,
CAP `20121`, comune `Milano`, costo 700, minimo 0, attiva; `settings` senza `orderCutoff`.

Carrelli dei test: «Mazzo di rose rosse — 12 rose» 4500; «Orchidea Phalaenopsis bianca — Vaso 12 cm»
2990 (sotto il minimo di Capannori); «Bouquet di stagione — Medio» 3500 (uguale al minimo: passa).

Indirizzi dei test (sempre CAP **e** comune, perché il CAP si cerca prima):
- dentro: `55100` + `Lucca` → Lucca centro; `55010` + ` capannori ` → Capannori (per comune);
  `55012` + `Lucca` → Capannori (vince il CAP).
- fuori: `55049` + `Viareggio` (zona non attiva), `56100` + `Pisa` (nessuna zona).
- isolamento: `20121` + `Milano` sul demo; `55100` + `Lucca` su Marco.
- destinatario inventato e unico: «Anna Verdi», `+39 333 999 0007`, «Via delle Prove 7», note
  «Citofono Verdi, secondo piano» (non si cercano 55100/Lucca sulla pagina di stato: sono anche
  l'indirizzo del negozio).

Orologio dei test (`TestClock`): martedì 20/10/2026, ora legale (UTC+2); casi normali alle
`2026-10-20T08:00:00Z` (10:00) con data 21/10.
- `2026-10-20T09:59:00Z` = 11:59 a Roma → oggi si può ancora scegliere.
- `2026-10-20T10:00:00Z` = 12:00 a Roma → oggi non più (limite escluso: «prima dell'orario limite»).
- dopo il cambio d'ora (25/10, UTC+1): `2026-10-26T10:59:00Z` = 11:59 → 303; `2026-10-26T11:00:00Z`
  = 12:00 → 400. Nessun offset fisso: si usa il fuso del negozio.

## Regole (decise qui)
- Modulo: campo nuovo `fulfillmentType` (radio) = `PICKUP` | `DELIVERY`; **se manca vale `PICKUP`** (i test
  dei giri 010–016 e `infra/prova-stripe.sh` non cambiano). Il campo data resta `pickupDate` (nome
  storico, vale per entrambi i tipi), con l'attributo `min` sulla stessa riga dell'`input`.
  Qualunque altro valore (`CEMETERY`, `xyz`) → 400, nessun ordine, Stripe non chiamato.
- Per `DELIVERY` servono: `recipientName`, `recipientPhone`, `addressLine`, `postalCode` (5 cifre),
  `city`; `deliveryNotes` facoltativo (`input` o `textarea`, ≤ 200). Ogni campo mancante → errore
  accanto al campo, 400. Testi fissi: «Scrivi il nome del destinatario.», «Scrivi il telefono del
  destinatario.», «Scrivi l'indirizzo di consegna.», «Scrivi un CAP di 5 cifre.» (anche per `5510`),
  «Scrivi il comune.».
- Pagina di checkout: per ogni zona attiva nome, costo e ordine minimo («Lucca centro 5,00 €, minimo
  25,00 €»); le zone non attive non compaiono. Tutti gli importi, anche nei messaggi, nel formato del
  negozio: `35,00` + NBSP + `€`.
- Zona: prima per CAP esatto, poi per comune (confronto senza maiuscole e spazi ai lati), solo tra le
  zone attive del negozio corrente, caricate e cercate in Java (niente JPQL su `text[]`). Se due zone
  hanno lo stesso CAP vince quella con id più basso (ordine fisso). Il browser non manda mai zona né costo: parametri come
  `zoneId` o `deliveryFeeCents` nel POST si ignorano.
- Fuori zona → 400, «Non consegniamo a questo indirizzo.» e link `https://wa.me/393330000000`
  («Scrivici su WhatsApp»), se il negozio ha `settings.whatsapp` (ridotto alle sole cifre, link senza
  dati del cliente); altrimenti il telefono del negozio. Nessun ordine.
- Sotto il minimo → 400, «Per la zona Capannori e Porcari l'ordine minimo è 35,00 €.». Nessun ordine.
  Il minimo vale sul **totale prodotti (`subtotal_cents`), consegna esclusa**: orchidea 2990 + 900 =
  3890 non passa; 3500 esatti passano.
- Ordine `DELIVERY`: `delivery_fee_cents` = costo zona, `total_cents` = prodotti + costo,
  `zone_id`, destinatario e indirizzo salvati; a Stripe una riga in più «Consegna — <zona>» × 1,
  costruita dall'ordine (fee e zona salvate), non dal modulo.
- IVA del costo di consegna: `vat_breakdown` (colonna `updatable=false`) riceve una
  `VatBreakdown.Line` in più col costo della zona, all'aliquota del bene principale (la riga prodotto
  di importo maggiore; a parità, la prima). Demo: tutto al 10%, quindi taxable + tax = 5000 per le
  12 rose a Lucca centro. Regola per aliquote miste [DA VERIFICARE] (prestazione accessoria, art. 12
  DPR 633/72): da registrare in `docs/verifiche.md` con fonte e data.
- Orario limite (vale per ritiro e consegna, §7.3 p. 3): se `pickupDate` è oggi (fuso del negozio) e
  l'ora è ≥ `orderCutoff` → 400, «Gli ordini per oggi si chiudono alle 12:00: scegli un altro giorno.»
  e **non** il messaggio generico; il generico («Scegli un giorno di ritiro valido, dal …») resta per
  date passate o dentro i giorni di preparazione. Il `min` del campo data diventa domani. Negozio senza
  `orderCutoff`, o con un valore che non è `HH:mm`: nessun limite (il valore malformato si ignora con
  un log `WARN`).
- Lettura: `orderCutoff` e `whatsapp` con una query `select settings->>'orderCutoff',
  settings->>'whatsapp' from tenant where id = ?`, aggiunti a `ShopProfile.Info`; niente mappatura
  del jsonb in `Tenant`.
- Un solo `clock.instant()` per richiesta: il limite usa lo stesso `now` di `prepare` (e di `page`);
  `earliestPickup` riceve la data, non rilegge il `Clock`.
- La riutilizzazione dell'ordine in attesa (`sameOrder`) confronta anche tipo, zona, costo e **tutti**
  i campi di consegna: `recipient_name`, `recipient_phone`, `address_line`, `postal_code`, `city`,
  `delivery_notes`. Un campo diverso → ordine e sessione Stripe nuovi.
- Pagina di stato `/ordine/{token}` ed email di conferma: «Consegna a domicilio: <data>» e
  «Consegna: 5,00 €» per `DELIVERY`; la pagina di stato **non** mostra destinatario né indirizzo
  (giro 014: niente dati personali). Il ritiro resta com'è («Ritiro in negozio»).
- Schema: `V8__delivery_zones.sql`, solo aggiunte: tabella `delivery_zone` (SPEC §6, importi
  `bigint`, regole ADR-0003 p. 8: `unique (tenant_id, id)`, `unique (tenant_id, name)`, indice che
  inizia con `tenant_id`, `check (fee_cents >= 0 and min_order_cents >= 0)`); su `customer_order`
  colonne nullable `recipient_name`, `recipient_phone`, `address_line`, `postal_code`, `city`,
  `delivery_notes`, `zone_id` (FK composita con `tenant_id`).
- Seed: le tre zone si inseriscono nell'ordine Lucca centro, Capannori e Porcari, Versilia (id crescenti).
- Test esistenti: `TestShops.deleteAll` (test, non accettazione) deve cancellare `delivery_zone`
  prima di `tenant`, altrimenti la pulizia dei giri 005–016 fallisce sulla FK.

## Fatto quando — controlli eseguibili
Host `demo.localhost` salvo dove detto; Stripe finto `Giro011FakeStripe`; orologio alle 10:00 del
20/10 e data 21/10 salvo dove detto. Comando: `./mvnw -q -Dtest='Giro017*AcceptanceTest' test` verde.
- [ ] GET `/checkout` con carrello → «Ritiro in negozio», «Consegna a domicilio», radio
      `fulfillmentType` PICKUP/DELIVERY, campi di consegna, «Lucca centro» 5,00 € e 25,00 €,
      «Capannori e Porcari» 9,00 € e 35,00 €; **non** «Versilia».
- [ ] POST `DELIVERY` `55100` + `Lucca`, 12 rose → 303; ordine `DELIVERY`, fee 500, totale 5000,
      `zone_id` = Lucca centro, destinatario/indirizzo/note salvati; `vat_breakdown` solo 10% con
      taxable + tax = 5000; a Stripe 2 righe, «Consegna — Lucca centro» 500 × 1.
- [ ] `55010` + ` capannori ` → Capannori, fee 900, totale 5400; `55012` + `Lucca` → Capannori.
- [ ] `55049` + `Viareggio` e `56100` + `Pisa` → 400, «Non consegniamo a questo indirizzo.»,
      `href="https://wa.me/393330000000"`, «Scrivici su WhatsApp»; nessun ordine, Stripe non chiamato.
- [ ] Orchidea a Capannori → 400 col messaggio del minimo; Bouquet Medio (3500) → 303, totale 4400.
- [ ] Campi di consegna vuoti → 400 coi cinque messaggi; CAP `5510` → 400; note di 201 caratteri → 400.
- [ ] `fulfillmentType` `CEMETERY` o `xyz` → 400, nessun ordine.
- [ ] Senza `fulfillmentType` → ordine `PICKUP`, fee 0, `zone_id` null, 1 riga Stripe (non regressione).
- [ ] `zoneId`/`deliveryFeeCents`/`total_cents` falsi nel modulo → zona e fee calcolate dal server.
- [ ] `fee_cents` cambiato via JDBC tra GET e POST → vale il nuovo (600, totale 5100); zona
      disattivata tra GET e POST → fuori zona.
- [ ] Stesso POST due volte → 1 ordine, 1 sessione; cambiando telefono, nome, indirizzo, note o CAP
      del destinatario → ogni volta un ordine e una sessione nuovi.
- [ ] Seconda zona sullo stesso CAP (id più alto) → vince Lucca centro.
- [ ] Orario limite: alle 11:59 oggi → 303 per ritiro e consegna, `min="2026-10-20"`; alle 12:00 oggi
      → 400 col messaggio del limite e senza il generico, `min="2026-10-21"`; alle 12:00 domani → 303
      per entrambi; data passata → messaggio generico, non quello del limite; 26/10 alle 10:59Z → 303,
      alle 11:00Z → 400 e `min="2026-10-27"`.
- [ ] Isolamento: checkout di Marco mostra solo «Milano centro» 7,00 €; demo `20121` + `Milano` →
      fuori zona; Marco `55100` + `Lucca` → fuori zona, col suo telefono e senza il WhatsApp del demo;
      `zoneId` di Marco sul demo → ignorato; consegna di Marco → fee 700, totale 3700, account di Marco,
      riga «Consegna — Milano centro»; demo alle 12:00 oggi → 400, Marco (senza limite o con limite
      malformato) → 303.
- [ ] `/ordine/{token}` di un ordine `DELIVERY` pagato → «Consegna a domicilio: 21/10/2026»,
      «Consegna: 5,00 €», «50,00 €», niente «Ritiro in negozio», niente Anna Verdi, telefono,
      «Via delle Prove», note; email (HTML e testo) → «Consegna a domicilio», 21/10/2026,
      «Consegna: 5,00 €», 50,00 €. Un ritiro resta «Ritiro in negozio» senza «Consegna: ».
- [ ] Seed eseguito due volte → 3 zone del demo coi valori della tabella, `orderCutoff` = `12:00`,
      `whatsapp` = `+393330000000`; fee o minimo negativi rifiutati dal database.
- [ ] `./mvnw -B -ntp verify` verde (compreso `SchemaRulesTest` su `delivery_zone` e i giri 005–016).
- [ ] `docs/runbook.md`: una riga accanto a `infra/prova-stripe.sh`: dopo le 12:00 il ritiro per oggi
      è chiuso, lo script deve usare una data da domani (o va lanciato prima delle 12:00).
- [ ] Dopo il deploy (umano): `infra/prova-stripe.sh` sul demo arriva ancora a Stripe col ritiro.

## Fuori perimetro
- Da `traguardi.json → fuori_perimetro`: back-office (zone e orario limite si cambiano solo nel
  seed/SQL), tipo `CEMETERY`, capacità per fascia, consensi/GDPR oltre la pagina privacy, SPF/DKIM.
- Fasce orarie (`delivery_slot`), giorni lavorativi e chiusure (`calendar_exception`), giorni di
  picco e supplementi, blocco `pg_advisory_xact_lock` (§7.4): niente capacità, quindi niente blocco.
- htmx che mostra date e fasce disponibili (§7.5 p. 1): il campo data resta un `input type=date`.
- Pagina «Consegne e zone» (§2.2 Informazioni), «consegna da X €» nella scheda prodotto,
  messaggio del biglietto, fattura, `place`, import di CAP/comuni (deriva di Gerio, cicli 11–14).
- Pulsante WhatsApp altrove che nel messaggio fuori zona.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
Commit `4274ce0`. Aiuti: `Giro017Data` (JDBC su zone, settings, IVA), `Giro017Checkout` (moduli,
testi fissi, importi con NBSP).

| File | Test | Dato … quando … allora … |
|---|---|---|
| `Giro017DeliveryZonesAcceptanceTest` | 12 | carrello demo · GET/POST con CAP/comune dentro, fuori, sotto il minimo, campi mancanti, tipo ignoto, valori falsi, fee cambiata, stessi dati e dati diversi, due zone sullo stesso CAP · zone, fee, totale, IVA, riga Stripe, 400 e messaggi |
| `Giro017CutoffAcceptanceTest` | 5 | `TestClock` alle 11:59, alle 12:00 e dopo il 25/10 · ritiro e consegna per oggi, domani, ieri · 303 / 400, messaggio giusto, `min` |
| `Giro017DeliveryIsolationAcceptanceTest` | 5 | zona di Marco e zone del demo · CAP e `zoneId` dell'altro negozio · fuori zona; Marco senza limite |
| `Giro017DeliveryStatusAcceptanceTest` | 3 | ordine `DELIVERY` pagato · pagina di stato ed email · tipo e costo, niente dati del destinatario; il ritiro resta com'è |
| `Giro017DemoZonesSeedAcceptanceTest` | 2 | seed due volte · 3 zone, settings; importi negativi rifiutati |

Rossi al commit: 25 su 27, tutti per asserzione (tabella `delivery_zone` o colonna mancante, ordine
`PICKUP` invece di `DELIVERY`, 303 invece di 400, testi assenti, `min` non spostato). Verdi già ora,
come non regressione: data passata → messaggio generico; ritiro pagato → «Ritiro in negozio».

Le fixture JDBC che toccano `delivery_zone` controllano prima `to_regclass` (come i giri 010–015),
così i test sono rossi per asserzione e non per errore SQL.

## Porte
- Doppio senso previste: migrazione `V8` solo additiva (tabella nuova, colonne nullable); chiavi
  nuove in `tenant.settings` (jsonb, nessuno schema); campi nuovi del modulo con valore di default;
  testi della pagina di checkout; dati del seed demo.
- Senso unico: nessuna. Nessuna dipendenza nuova. Gli indirizzi del destinatario sono dati
  personali, ma nel laboratorio solo inventati; la pagina di stato non li espone.

## Rischi di concorrenza
- Doppio clic con dati di consegna diversi: il carrello è già bloccato in transazione A; `sameOrder`
  confronta tipo, zona, fee e tutti i campi di consegna, altrimenti si riusa la sessione Stripe con
  dati vecchi. Test: stesso POST → riuso; telefono, nome, indirizzo, note o CAP diversi → ordine nuovo.
- Zona cambiata o disattivata tra pagina e POST: il costo si ricalcola al POST (fonte: database),
  mai dal browser. Test: `fee_cents` 500 → 600 via JDBC tra GET e POST → 600; zona spenta → fuori zona.
- Orario limite a cavallo: un solo `clock.instant()` per richiesta, nel fuso del negozio; il limite
  si valuta sullo stesso `now` della transazione A.
- `infra/prova-stripe.sh` lanciato verso le 12:00 può trovare il ritiro di oggi chiuso: riga nel runbook.

## Budget
Righe di produzione ≤ 450; file di produzione ≤ 14, contati (13 previsti):
1 `V8__delivery_zones.sql` · 2 `delivery/DeliveryZone.java` · 3 `delivery/DeliveryZones.java` ·
4 `order/CustomerOrder.java` · 5 `order/CheckoutService.java` · 6 `storefront/CheckoutResource.java` ·
7 `storefront/checkout.html` · 8 `order/OrderLookup.java` · 9 `storefront/order-status.html` ·
10 `email/order-confirmation.html` · 11 `email/order-confirmation.txt` · 12 `demo/demo-shop.sql` ·
13 `tenant/ShopProfile.java`. Riserva: 1 (per esempio `ConfirmationComposer` se serve).
Fuori conteggio: `TestShops.java` (test), `docs/runbook.md`, `docs/verifiche.md`. `VatBreakdown`
non cambia (la riga di consegna è una `Line` come le altre). Una sessione.

## Domande per il ricercatore (al massimo 3)
1. Come i fiorai online italiani mostrano zone, costi di consegna, ordine minimo e il messaggio
   «fuori zona» al checkout (parole, ordine dei campi, dove compaiono)?
2. Formato e regole ufficiali del link WhatsApp click-to-chat (`wa.me/<numero>`, prefisso senza `+`,
   testo precompilato) [DA VERIFICARE su faq.whatsapp.com].
3. CAP italiani: un comune può avere più CAP e un CAP più comuni; conviene davvero «prima CAP, poi
   comune», e il CAP va validato solo come 5 cifre?

## Critica ricevuta e cosa cambia
Accolti tutti e 5 i «DA CORREGGERE»:
1. Costo di consegna nell'IVA: riga `VatBreakdown.Line` col costo, aliquota del bene principale,
   aliquote miste [DA VERIFICARE]. Test: taxable + tax = 5000, sola aliquota 10%.
2. `sameOrder` su tutti i campi di consegna. Test: telefono, nome, indirizzo, note, CAP → ordine nuovo.
3. Minimo sul totale prodotti, consegna esclusa (orchidea 2990 + 900 non passa; 3500 esatti passano).
4. Precedenza dei messaggi: oggi oltre il limite → messaggio del limite; date passate → generico.
5. Test sempre con CAP e comune: `55049` + `Viareggio`, `20121` + `Milano`, più `55012` + `Lucca`.
Accolte anche le semplificazioni (zone in Java, settings via query in `ShopProfile.Info`, riga
Stripe dall'ordine), la concorrenza (fee via JDBC, un solo `now`, riga nel runbook), il conteggio
dei file (13) e tutti i test chiesti (dopo il 25/10, domani dopo le 12, tipo ignoto, NBSP,
non regressioni accanto a casi rossi, indirizzo unico sulla pagina di stato).
Facoltativi accolti: ordine fisso per id con due zone sullo stesso CAP; `orderCutoff` malformato
ignorato con log; WhatsApp ridotto alle cifre; `check` sugli importi in V8 (tutti con test, tranne il
log). Scartato: nulla. Aggiunte mie: `56100` + `Pisa` come zona inesistente; testi fissi dei cinque
errori di campo; `TestShops.deleteAll` da aggiornare per la FK di `delivery_zone`.

## Idee per dopo
- Pagina «Consegne e zone» generata dalle zone attive; «consegna da X €» nella scheda prodotto.
- Calendario completo: fasce, chiusure, giorni di picco, capacità e blocco (§7.3–7.4).
- Colonna `whatsapp` dedicata in `tenant` invece della chiave in `settings`.
