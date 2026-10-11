---
giro: 005
traguardo: T3
fase: critica
inizio: 2026-10-11T18:17:10Z
ricerca: sì
accettazione:
ripreso: no
---
# Piano del giro 005 — catalogo e pagina prodotto del negozio demo (in locale e nei test)

## Fetta e traguardo
T3, parte 1: tabelle del catalogo, dati inventati del negozio demo, pagina catalogo, pagina per
categoria e scheda prodotto, servite sull'host del negozio. T2 è passato: T3 è il primo traguardo
non passato.

**Cosa resta di T3 dopo questo giro (giro 006):** rendere il negozio demo raggiungibile in
produzione su `demo.<LAB_BASE_DOMAIN>`. Serve un blocco nuovo in `infra/Caddyfile` (percorso in
revisione umana, METODO §4) e la verifica dall'esterno, con certificato TLS nuovo per il
sottodominio. Il seme dei dati va però in produzione già in questo giro (vedi sotto), così il
giro 006 tocca solo Caddy e lo smoke test.

## Dati del negozio demo (inventati; i test di accettazione li usano)

Tutti i prezzi sono IVA inclusa, in centesimi. IVA 10% (`vat_rate_bp = 1000`) su tutti i prodotti.

**Negozio** (tabella `tenant`)

| Campo | Valore |
|---|---|
| id | `00000000-0000-0000-0000-0000000de000` |
| slug | `demo` |
| status | `ACTIVE` |
| display_name | `Fioreria Il Glicine` |
| legal_name | `Il Glicine di Lucia Ferri` |
| email | `ciao@glicine.example` |
| phone | `+39 0583 000000` |
| address_line / postal_code / city / province | `Via dei Fossi 12` / `55100` / `Lucca` / `LU` |

**Categorie** (in quest'ordine)

| position | slug | name | visible |
|---|---|---|---|
| 1 | `bouquet` | Bouquet | sì |
| 2 | `piante` | Piante | sì |
| 3 | `composizioni` | Composizioni | sì |
| 4 | `natale` | Natale | **no** |

**Prodotti e varianti**

| Categoria | slug | name | lead_time_days | active | Varianti (nome: centesimi), in ordine |
|---|---|---|---|---|---|
| bouquet | `bouquet-di-stagione` | Bouquet di stagione | 0 | sì | Piccolo: 2500 · Medio: 3500 · Grande: 4900 · *Extra: 6900, variante non attiva* |
| bouquet | `rose-rosse` | Mazzo di rose rosse | 0 | sì | 12 rose: 4500 · 24 rose: 8500 |
| piante | `orchidea-bianca` | Orchidea Phalaenopsis bianca | 0 | sì | Vaso 12 cm: 2990 |
| composizioni | `composizione-in-cesto` | Composizione in cesto | 1 | sì | Media: 5500 · Grande: 7500 |
| composizioni | `corona-avvento` | Corona dell'Avvento | 0 | **no** | Unica: 3900 |
| natale | `stella-di-natale` | Stella di Natale | 0 | sì | Vaso 14 cm: 1800 |

Descrizioni (una riga ciascuna):
- Bouquet di stagione: «Fiori freschi scelti ogni mattina al mercato, legati a mano.»
- Mazzo di rose rosse: «Rose rosse a stelo lungo, con verde decorativo.»
- Orchidea Phalaenopsis bianca: «Pianta in vaso, fiorisce per settimane con poche cure.»
- Composizione in cesto: «Fiori e verde in un cesto di vimini, pronta da regalare.»
- Corona dell'Avvento: «Corona di abete con quattro candele.»
- Stella di Natale: «Pianta rossa della tradizione natalizia.»

**Testi della scheda prodotto** (esatti):
- prezzo: formato italiano con simbolo dopo, per esempio `25,00 €`, e la dicitura `IVA inclusa`;
- composizione di stagione (SPEC §2.3), su ogni scheda della categoria `bouquet` e `composizioni`:
  «Composizione realizzata con fiori di stagione: può differire dalla foto mantenendo stile e valore.»
- recesso, su ogni scheda con `withdrawal_excluded = true` (default; tutti i prodotti demo):
  «Fiori e piante sono deperibili: il diritto di recesso è escluso.»
- preparazione, solo se `lead_time_days > 0`: «Tempo di preparazione: 1 giorno» (con `n > 1`:
  «n giorni»).

## Indirizzi

| URL (sull'host del negozio) | Contenuto |
|---|---|
| `/catalogo` | `h1` «Catalogo»; le categorie visibili in ordine, ognuna con i suoi prodotti attivi (nome, link alla scheda, prezzo «da X €» = variante attiva più economica) |
| `/catalogo/{slugCategoria}` | `h1` = nome della categoria; i suoi prodotti attivi. Categoria inesistente o non visibile → 404 |
| `/prodotti/{slugProdotto}` | `h1` = nome; descrizione; varianti attive in ordine, ognuna con prezzo; testi della sezione sopra. Prodotto inesistente, non attivo, di un altro negozio o in categoria non visibile → 404 |
| `/` | come oggi, più un link «Catalogo» verso `/catalogo` |

Sul dominio della piattaforma (`localhost` nei test) `/catalogo` e `/prodotti/…` → 404.

## Fatto quando — controlli eseguibili
- [ ] `./mvnw -q -Dtest='Giro005*AcceptanceTest' test` → verde. Copre:
  - il seme `src/main/resources/db/demo/R__demo_shop.sql` esiste, si esegue due volte di fila
    senza errori e senza duplicare righe (il catalogo mostra ogni prodotto una volta sola);
  - `GET /catalogo` con `Host: demo.localhost` → 200, `Bouquet`, `Piante`, `Composizioni` in
    quest'ordine; `Mazzo di rose rosse`, `Orchidea Phalaenopsis bianca`; `da 25,00 €`; nessuna
    traccia di `Natale`, `Stella di Natale`, `Corona dell'Avvento`;
  - `GET /catalogo/bouquet` → 200 con `Bouquet di stagione` e `Mazzo di rose rosse`, senza
    `Orchidea`; `GET /catalogo/natale` e `/catalogo/inesistente` → 404;
  - `GET /prodotti/bouquet-di-stagione` → 200; `h1` «Bouquet di stagione»; `Piccolo`, `Medio`,
    `Grande` in quest'ordine con `25,00 €`, `35,00 €`, `49,00 €`; `IVA inclusa`; nota di
    stagione; avviso di recesso; niente `69,00` né `Extra`; niente «Tempo di preparazione»;
  - `GET /prodotti/composizione-in-cesto` → 200 con «Tempo di preparazione: 1 giorno»;
  - `GET /prodotti/corona-avvento`, `/prodotti/stella-di-natale`, `/prodotti/inesistente` → 404;
  - isolamento: con un secondo negozio attivo `fiori-di-marco` («Fiori di Marco», senza
    prodotti), `GET /catalogo` sul suo host → 200 senza alcun prodotto demo e
    `GET /prodotti/bouquet-di-stagione` sul suo host → 404;
  - `GET /catalogo` e `/prodotti/bouquet-di-stagione` con `Host: localhost` → 404;
  - `GET /` sul demo → contiene un link a `/catalogo`;
  - verifica visiva: Chromium a 390×844 apre `/prodotti/bouquet-di-stagione` sul demo, legge
    l'`h1` e salva `target/e2e-screenshots/storefront-product.png` (PNG valido, 390 px di larghezza).
- [ ] `./mvnw -B -ntp verify` → verde (compresi i test di isolamento del costruttore, regola 1,
  per ogni repository/servizio nuovo del catalogo, e `SchemaRulesTest` sulle tabelle nuove).
- [ ] `grep -n 'flyway.locations' src/main/resources/application.properties` → in prod e dev le
  posizioni sono `db/migration,db/demo`; in test restano `db/migration,db/test-migration`.
- [ ] `./mvnw quarkus:dev`, poi `curl -s -H 'Host: demo.localhost' localhost:8080/prodotti/rose-rosse`
  → HTML con «Mazzo di rose rosse» e `45,00 €` (il seme gira anche in dev).

## Fuori perimetro
- Raggiungibilità in produzione di `demo.<LAB_BASE_DOMAIN>` (Caddyfile, smoke test): giro 006.
- Foto prodotto, `product_image`, imgproxy, caricamento immagini (§4.5): al posto della foto nessuna
  immagine o un segnaposto locale senza file binari nuovi.
- «Consegna da X €» sulla scheda: dipende dalle zone (T7).
- Extra (`addon`), `price_history`, campi SEO, dati strutturati, `allowed_fulfillment`: si
  aggiungono con migrazioni additive quando servono.
- Carrello e pulsante «Aggiungi al carrello» (T4); checkout e Stripe (T5).
- Piè di pagina legale e pagine legali (T8). Tema per negozio, Tailwind.
- Back-office e modifica del catalogo da parte del negozio.
- Da `traguardi.json → fuori_perimetro`: back-office del negozio (piano B: email e dashboard
  Stripe); tipo di consegna CEMETERY; capacità per fascia oraria; impianto consensi e GDPR oltre la
  pagina privacy; SPF e DKIM completi.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|
| `Giro005CatalogAcceptanceTest` | Dato il seme demo eseguito (due volte) e il negozio `fiori-di-marco`, quando si chiedono catalogo, categorie e schede con i diversi Host, allora contenuti, ordine, prezzi, testi e 404 come sopra |
| `Giro005CatalogVisualAcceptanceTest` | Dato il seme demo, quando Chromium apre la scheda del bouquet a 390×844, allora vede l'`h1` e salva `storefront-product.png` |

Il test esegue il file del seme via JDBC (lo legge dal classpath: se manca, fallisce con
un'asserzione) e dopo ogni test pulisce con `TestShops.deleteAll()`. Il costruttore estende
`TestShops.deleteAll()` perché cancelli prima le tabelle del catalogo.

## Porte
- Doppio senso previste: migrazione additiva `V3__catalog.sql` con `category`, `product`,
  `product_variant` (§6, più `created_at`, `updated_at`, `version` come da scostamento 8); seme
  come migrazione ripetibile Flyway in una posizione a parte `db/demo`, idempotente (upsert sulle
  chiavi naturali `tenant.id`, `(tenant_id, slug)`, variante per `(product, name)`); seme attivo in
  prod e dev, non nei test (che lo eseguono da sé); URL in italiano `/catalogo`, `/prodotti`;
  formato prezzi `25,00 €`; testi della scheda. Il seme scrive in produzione solo dati inventati,
  su un host che Caddy ancora non serve: si toglie con una migrazione successiva.
- Senso unico: nessuna. Nessuna dipendenza nuova (Qute, Panache, Flyway, Playwright ci sono già).

## Rischi di concorrenza
Solo letture. Unico punto: il seme ripetibile gira all'avvio dentro la migrazione Flyway (un solo
processo, lock di Flyway); l'idempotenza si prova eseguendolo due volte nel test. Dati condivisi
tra classi di test: ogni test pulisce tutto, il seme non resta tra una classe e l'altra.

## Budget
Righe di produzione ≤ 450 (SQL compreso); file ≤ 14; una sessione.

## Domande per il ricercatore (al massimo 3)
1. Come mostrano prezzo, varianti di misura (piccolo/medio/grande) e «IVA inclusa» le schede
   prodotto delle fioraie online italiane (3–5 esempi reali)? Il formato `25,00 €` e «da 25,00 €»
   nel catalogo sono quelli attesi?
2. Formula corretta e chiara per l'esclusione del recesso sui beni deperibili (Codice del Consumo
   art. 59, lett. d) da mostrare sulla scheda: la nostra frase basta o serve citare la norma?
3. In Quarkus 3.33 con Flyway: una migrazione ripetibile (`R__`) in una posizione aggiuntiva
   attiva solo in alcuni profili (`%prod`/`%dev.quarkus.flyway.locations`) ha controindicazioni
   (validazione, `validate-on-migrate`, ordine rispetto alle versionate)?

## Idee per dopo
- Cache del catalogo per negozio (richiede `quarkus-cache`, dipendenza nuova).
- Dati strutturati `Product` JSON-LD per la scheda (§4.3 `seo/`).

## Critica ricevuta e cosa cambia
(dopo il critico) Accolto: … · Scartato, con motivo: …
