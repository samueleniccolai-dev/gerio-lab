---
giro: 017
traguardo: T7
fase: critica
inizio: 2026-10-16T09:31:01Z
ricerca: sì
accettazione:
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
2990 (sotto il minimo di Capannori).

Orologio dei test (`TestClock`): martedì 20/10/2026, ora legale (UTC+2).
- `2026-10-20T09:59:00Z` = 11:59 a Roma → oggi si può ancora scegliere.
- `2026-10-20T10:00:00Z` = 12:00 a Roma → oggi non più (limite escluso: «prima dell'orario limite»).

## Regole (decise qui)
- Modulo: campo nuovo `fulfillmentType` = `PICKUP` | `DELIVERY`; **se manca vale `PICKUP`** (i test
  dei giri 010–016 e `infra/prova-stripe.sh` non cambiano). Il campo data resta `pickupDate` (nome
  storico, vale per entrambi i tipi), con l'attributo `min` sulla stessa riga dell'`input`.
- Per `DELIVERY` servono: `recipientName`, `recipientPhone`, `addressLine`, `postalCode` (5 cifre),
  `city`; `deliveryNotes` facoltativo (≤ 200). Ogni campo mancante → errore accanto al campo, 400.
- Zona: prima per CAP esatto, poi per comune (confronto senza maiuscole e spazi ai lati), solo tra le
  zone attive del negozio corrente. Il browser non manda mai zona né costo: parametri come
  `zoneId` o `deliveryFeeCents` nel POST si ignorano.
- Fuori zona → 400, «Non consegniamo a questo indirizzo.» e link `https://wa.me/393330000000`
  («Scrivici su WhatsApp»), se il negozio ha `settings.whatsapp`; altrimenti il telefono. Nessun ordine.
- Sotto il minimo → 400, «Per la zona Capannori e Porcari l'ordine minimo è 35,00 €.». Nessun ordine.
- Ordine `DELIVERY`: `delivery_fee_cents` = costo zona, `total_cents` = prodotti + costo,
  `zone_id`, destinatario e indirizzo salvati; a Stripe una riga in più «Consegna — <zona>» × 1.
- Orario limite (vale per ritiro e consegna, §7.3 p. 3): se `pickupDate` è oggi (fuso del negozio) e
  l'ora è ≥ `orderCutoff` → 400, «Gli ordini per oggi si chiudono alle 12:00: scegli un altro giorno.»
  Il `min` del campo data diventa domani. Negozio senza `orderCutoff`: nessun limite (come oggi).
- La riutilizzazione dell'ordine in attesa (`sameOrder`) confronta anche tipo, zona, costo e indirizzo.
- Pagina di stato `/ordine/{token}` ed email di conferma: «Consegna a domicilio: <data>» e
  «Consegna: 5,00 €» per `DELIVERY`; la pagina di stato **non** mostra destinatario né indirizzo
  (giro 014: niente dati personali). Il ritiro resta com'è («Ritiro in negozio»).
- Schema: `V8__delivery_zones.sql`, solo aggiunte: tabella `delivery_zone` (SPEC §6, importi
  `bigint`, regole ADR-0003 p. 8: `unique (tenant_id, id)`, `unique (tenant_id, name)`, indice che
  inizia con `tenant_id`); su `customer_order` colonne nullable `recipient_name`, `recipient_phone`,
  `address_line`, `postal_code`, `city`, `delivery_notes`, `zone_id` (FK composita con `tenant_id`).

## Fatto quando — controlli eseguibili
Host `demo.<dominio di test>` salvo dove detto; Stripe finto dei test (`Giro010FakeStripe` o simile).
- [ ] GET `/checkout` con carrello → contiene «Ritiro in negozio», «Consegna a domicilio»,
      «Lucca centro», «5,00 €», «Capannori e Porcari», «9,00 €», «35,00 €»; **non** «Versilia».
- [ ] POST `DELIVERY`, CAP `55100`, 12 rose → 303 verso Stripe; ordine `DELIVERY`, fee 500,
      totale 5000, `postal_code` 55100, `zone_id` = Lucca centro; a Stripe la riga
      «Consegna — Lucca centro» 500 × 1.
- [ ] POST `DELIVERY`, CAP `55010`, comune ` capannori ` → zona Capannori e Porcari, fee 900, totale 5400.
- [ ] POST `DELIVERY`, CAP `55049` (zona non attiva) → 400, «Non consegniamo a questo indirizzo.»,
      link `https://wa.me/393330000000`; nessuna riga in `customer_order`.
- [ ] POST `DELIVERY` Capannori con l'orchidea (2990) → 400 con il messaggio dell'ordine minimo; nessun ordine.
- [ ] POST `DELIVERY` senza destinatario, indirizzo, CAP, comune, telefono → 400, un errore per campo.
- [ ] POST con `zoneId`/`deliveryFeeCents` falsi nel modulo → fee e zona quelli calcolati dal server.
- [ ] Orario limite: alle 11:59 data di oggi → 303; alle 12:00 data di oggi (ritiro e consegna) → 400
      col messaggio; GET `/checkout` alle 12:00 → `min="2026-10-21"`, alle 11:59 → `min="2026-10-20"`.
- [ ] Isolamento: su demo CAP `20121` (zona di Marco) → fuori zona; su Marco CAP `55100` → fuori zona;
      Marco senza `orderCutoff` accetta oggi alle 12:00; GET `/checkout` di Marco non mostra zone del demo.
- [ ] Ritiro senza `fulfillmentType` → come prima (ordine `PICKUP`, fee 0): i test dei giri 010–016 restano verdi.
- [ ] `/ordine/{token}` di un ordine `DELIVERY` pagato → «Consegna a domicilio» e «Consegna: 5,00 €»,
      senza destinatario, indirizzo e CAP; email di conferma → «Consegna a domicilio» e «5,00 €».
- [ ] Seed eseguito due volte → 3 zone del demo, non 6; `settings->>'orderCutoff'` = `12:00`.
- [ ] `./mvnw -B -ntp verify` verde (compreso `SchemaRulesTest` su `delivery_zone`).
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
| File | Dato … quando … allora … |
|---|---|
| `Giro017DeliveryZonesAcceptanceTest` | carrello demo · GET/POST checkout con CAP/comune dentro, fuori, sotto il minimo, campi mancanti, valori falsi · zone, fee, totale, riga Stripe, 400 e messaggi |
| `Giro017CutoffAcceptanceTest` | `TestClock` alle 11:59 e alle 12:00 · ritiro e consegna per oggi · 303 / 400 e `min` del campo data |
| `Giro017DeliveryIsolationAcceptanceTest` | zone di Marco e del demo · CAP dell'altro negozio · fuori zona; Marco senza limite |
| `Giro017DeliveryStatusAcceptanceTest` | ordine `DELIVERY` pagato · pagina di stato ed email · tipo e costo, niente indirizzo |
| `Giro017DemoZonesSeedAcceptanceTest` | seed due volte · 3 zone, `orderCutoff` 12:00 |

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
  deve confrontare anche tipo, zona, fee e indirizzo, altrimenti si riusa un ordine con il costo
  sbagliato. Test: due POST con CAP diversi → il secondo crea un ordine nuovo con la sua fee.
- Zona cambiata o disattivata tra pagina e POST: il costo si ricalcola al POST (fonte: database),
  mai dal browser.
- Orario limite a cavallo: l'ora si legge una volta per richiesta da `Clock`, nel fuso del negozio.

## Budget
Righe di produzione ≤ 450; file toccati ≤ 14 (migrazione, entità/repository zona, servizio
`delivery`, `CheckoutService`, `CheckoutResource`, template checkout, stato ordine, email, seed,
`ShopProfile` o lettura settings). Una sessione.

## Domande per il ricercatore (al massimo 3)
1. Come i fiorai online italiani mostrano zone, costi di consegna, ordine minimo e il messaggio
   «fuori zona» al checkout (parole, ordine dei campi, dove compaiono)?
2. Formato e regole ufficiali del link WhatsApp click-to-chat (`wa.me/<numero>`, prefisso senza `+`,
   testo precompilato) [DA VERIFICARE su faq.whatsapp.com].
3. CAP italiani: un comune può avere più CAP e un CAP più comuni; conviene davvero «prima CAP, poi
   comune», e il CAP va validato solo come 5 cifre?

## Critica ricevuta e cosa cambia
(dopo il critico)

## Idee per dopo
- Pagina «Consegne e zone» generata dalle zone attive; «consegna da X €» nella scheda prodotto.
- Calendario completo: fasce, chiusure, giorni di picco, capacità e blocco (§7.3–7.4).
- Colonna `whatsapp` dedicata in `tenant` invece della chiave in `settings`.
