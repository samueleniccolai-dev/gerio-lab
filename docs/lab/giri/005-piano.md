---
giro: 005
traguardo: T3
fase: critica
inizio: 2026-10-11T18:17:10Z
ricerca: sì
accettazione: 5a04cc7
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

**Prodotti e varianti** (`position` del prodotto = ordine nella tabella, dentro la categoria)

| Categoria | slug | name | lead_time_days | active | Varianti (nome: centesimi), in ordine |
|---|---|---|---|---|---|
| bouquet | `bouquet-di-stagione` | Bouquet di stagione | 0 | sì | Piccolo: 2500 · Medio: 3500 · Grande: 4900 · *Extra: 6900, variante non attiva* |
| bouquet | `rose-rosse` | Mazzo di rose rosse | 0 | sì | 12 rose: 4500 · 24 rose: 8500 |
| bouquet | `bouquet-sposa` | Bouquet da sposa | 0 | sì | *Unico: 12000, variante non attiva* (nessuna variante attiva) |
| piante | `orchidea-bianca` | Orchidea Phalaenopsis bianca | 0 | sì | Vaso 12 cm: 2990 |
| composizioni | `composizione-in-cesto` | Composizione in cesto | 1 | sì | Media: 5500 · Grande: 7500 |
| composizioni | `corona-avvento` | Corona dell'Avvento | 0 | **no** | Unica: 3900 |
| natale | `stella-di-natale` | Stella di Natale | 0 | sì | Vaso 14 cm: 1800 |

Totali nel database dopo il seme: 4 categorie, 7 prodotti, 12 varianti (tenant demo).

Descrizioni (una riga ciascuna):
- Bouquet di stagione: «Fiori freschi scelti ogni mattina al mercato, legati a mano.»
- Mazzo di rose rosse: «Rose rosse a stelo lungo, con verde decorativo.»
- Bouquet da sposa: «Su appuntamento, da concordare in negozio.»
- Orchidea Phalaenopsis bianca: «Pianta in vaso, fiorisce per settimane con poche cure.»
- Composizione in cesto: «Fiori e verde in un cesto di vimini, pronta da regalare.»
- Corona dell'Avvento: «Corona di abete con quattro candele.»
- Stella di Natale: «Pianta rossa della tradizione natalizia.»

**Regola di visibilità.** Un prodotto si mostra (catalogo, categoria, scheda) solo se: è del negozio
dell'host, `active = true`, la sua categoria è visibile, e ha **almeno una variante attiva**.
Altrimenti non compare negli elenchi e la scheda dà 404.

**Formato dei prezzi (carattere esatto).** Cifra con virgola e due decimali, poi **U+00A0
(spazio indivisibile)**, poi `€`: in Java `"25,00 €"`. È ciò che produce
`NumberFormat.getCurrencyInstance(Locale.ITALY)` su JDK 25; il costruttore può usarlo o scrivere il
formato a mano, ma l'uscita deve essere questa, ovunque, da una funzione sola. In catalogo:
`da` + spazio normale (U+0020) + prezzo, cioè `"da 25,00 €"` (variante attiva più economica).

**Testi della scheda prodotto** (esatti):
- prezzo di ogni variante nel formato sopra, e la dicitura `IVA inclusa`;
- composizione di stagione (SPEC §2.3), su ogni scheda della categoria `bouquet` e `composizioni`:
  «Composizione realizzata con fiori di stagione: può differire dalla foto mantenendo stile e valore.»
- recesso, su ogni scheda con `withdrawal_excluded = true` (default; tutti i prodotti demo):
  «Fiori e piante sono deperibili: il diritto di recesso è escluso.» Nessun riferimento
  all'articolo di legge finché la lettera non è verificata su Normattiva (vedi «Idee per dopo»).
- preparazione, solo se `lead_time_days > 0`: «Tempo di preparazione: 1 giorno» (con `n > 1`:
  «n giorni»).

**Secondo negozio per l'isolamento** (inserito dal test via JDBC, non dal seme)

| Cosa | Valore |
|---|---|
| tenant | id `00000000-0000-0000-0000-0000000de001`, slug `fiori-di-marco`, «Fiori di Marco», `ACTIVE` |
| categoria | `fiori` «Fiori», position 1, visibile |
| prodotto | **stesso slug** `bouquet-di-stagione`, «Bouquet di stagione», «Bouquet di Marco.», IVA 1000, attivo |
| variante | `Unico`: 3000 (attiva) |

## Indirizzi e markup minimo

| URL (sull'host del negozio) | Contenuto |
|---|---|
| `/catalogo` | `h1` «Catalogo»; le categorie visibili in ordine, ognuna con un link `href="/catalogo/{slug}"` e i suoi prodotti visibili (nome, link `href="/prodotti/{slug}"`, prezzo «da X €») |
| `/catalogo/{slugCategoria}` | `h1` = nome della categoria; i suoi prodotti visibili con link `href="/prodotti/{slug}"`. Categoria inesistente, non visibile o di un altro negozio → 404 |
| `/prodotti/{slugProdotto}` | `h1` = nome; descrizione; varianti attive in ordine, ognuna con prezzo; testi della sezione sopra. Prodotto non visibile (regola sopra) o di un altro negozio → 404 |
| `/` | come oggi, più un link `href="/catalogo"` |

I link sono relativi alla radice, esattamente in quella forma. Sul dominio della piattaforma
(`localhost` nei test) `/catalogo` e `/prodotti/…` → 404.

## Seme dei dati (come gira)
- File: `src/main/resources/demo/demo-shop.sql` (fuori da `db/`, non è una migrazione Flyway).
  SQL idempotente: `insert … on conflict (…) do update` sulle chiavi naturali — `tenant(id)`,
  `category(tenant_id, slug)`, `product(tenant_id, slug)`, `product_variant(product_id, name)`.
- `V3__catalog.sql` crea `category`, `product`, `product_variant` (§6, più `created_at`,
  `updated_at`, `version` con default come in V1, scostamento 8) e il vincolo unico
  `(product_id, name)` su `product_variant`, che serve all'`on conflict`.
- Un bean `@Startup` legge il file dal classpath e lo esegue via JDBC, solo se
  `app.demo.seed=true`. In `application.properties`: `app.demo.seed=false`,
  `%dev.app.demo.seed=true`, `%prod.app.demo.seed=true`. Nessuna riga `flyway.*` cambia.
- I test di accettazione eseguono lo stesso file da sé, due volte, via JDBC.
- Il costruttore estende `TestShops.deleteAll()` perché cancelli prima `product_variant`,
  `product`, `category`.

## Fatto quando — controlli eseguibili
- [ ] `./mvnw -q -Dtest='Giro005*AcceptanceTest' test` → verde. Copre:
  - il seme `demo/demo-shop.sql` è nel classpath, si esegue due volte di fila senza errori e
    lascia 4 categorie, 7 prodotti, 12 varianti per il negozio demo;
  - `application.properties`: `app.demo.seed=false`, `%dev.app.demo.seed=true`,
    `%prod.app.demo.seed=true`; l'unica riga `flyway.locations` resta
    `%test.quarkus.flyway.locations=db/migration,db/test-migration`;
  - `GET /catalogo` con `Host: demo.localhost` → 200, `h1` «Catalogo», link a `/catalogo/bouquet`,
    `/catalogo/piante`, `/catalogo/composizioni` in quest'ordine; un solo link a
    `/prodotti/rose-rosse`; `Orchidea Phalaenopsis bianca`; `da 25,00 €`, `da 29,90 €`
    (con U+00A0); nessuna traccia di `/catalogo/natale`, `stella-di-natale`, `Stella di Natale`,
    `corona-avvento`, `Avvento`, `bouquet-sposa`, `Bouquet da sposa`, `Bouquet di Marco`;
  - `GET /catalogo/bouquet` → 200, `h1` «Bouquet», con `Bouquet di stagione` e `Mazzo di rose
    rosse`, senza `Orchidea` né `bouquet-sposa`; `/catalogo/natale`, `/catalogo/inesistente`,
    `/catalogo/fiori` (categoria di Marco) → 404;
  - `GET /prodotti/bouquet-di-stagione` → 200; `h1` «Bouquet di stagione»; descrizione;
    `Piccolo`, `Medio`, `Grande` in quest'ordine con `25,00 €`, `35,00 €`, `49,00 €`;
    `IVA inclusa`; nota di stagione; avviso di recesso; niente `69,00` né `Extra` né `Unico` né
    `30,00`; niente «Tempo di preparazione»;
  - `GET /prodotti/orchidea-bianca` → 200 con avviso di recesso e **senza** nota di stagione;
  - `GET /prodotti/composizione-in-cesto` → 200 con «Tempo di preparazione: 1 giorno»;
  - `GET /prodotti/corona-avvento`, `/prodotti/stella-di-natale`, `/prodotti/bouquet-sposa`,
    `/prodotti/inesistente` → 404;
  - isolamento con `fiori-di-marco` (stesso slug prodotto): sul suo host `/catalogo` → 200 con
    link a `/prodotti/bouquet-di-stagione` e `da 30,00 €`, senza
    `rose-rosse` né `25,00`; `/prodotti/bouquet-di-stagione` → 200 con `Unico`, `30,00 €`, senza
    `Piccolo` né `25,00`; `/prodotti/rose-rosse` e `/catalogo/bouquet` → 404;
  - `GET /catalogo` e `/prodotti/bouquet-di-stagione` con `Host: localhost` → 404;
  - `GET /` sul demo → contiene `href="/catalogo"`;
  - verifica visiva: Chromium a 390×844 apre `/prodotti/bouquet-di-stagione` sul demo, legge
    l'`h1` e salva `target/e2e-screenshots/storefront-product.png` (PNG valido, 390×844).
- [ ] `./mvnw -B -ntp verify` → verde (compresi i test di isolamento del costruttore, regola 1,
  per ogni repository/servizio nuovo del catalogo, e `SchemaRulesTest` sulle tabelle nuove).
- [ ] `python3 -I scripts/guard/pr_guard.py --base origin/main --head HEAD` → nessun motivo di
  blocco legato a migrazioni o configurazione (nessuna riga `flyway.*` aggiunta; V3 solo
  `create table`/`create index`/`alter table … add`).
- [ ] `./mvnw quarkus:dev`, poi `curl -s -H 'Host: demo.localhost' localhost:8080/prodotti/rose-rosse`
  → HTML con «Mazzo di rose rosse» e `45,00 €` (il seme gira anche in dev, dopo Flyway).

## Fuori perimetro
- Raggiungibilità in produzione di `demo.<LAB_BASE_DOMAIN>` (Caddyfile, smoke test): giro 006.
- Rimozione del seme dalla produzione (spegnere `%prod.app.demo.seed` e cancellare le righe):
  giro futuro, in revisione umana.
- Foto prodotto, `product_image`, imgproxy, caricamento immagini (§4.5): al posto della foto nessuna
  immagine o un segnaposto locale senza file binari nuovi.
- «Consegna da X €» sulla scheda: dipende dalle zone (T7).
- Extra (`addon`), `price_history`, campi SEO, dati strutturati, `allowed_fulfillment`: si
  aggiungono con migrazioni additive quando servono.
- Carrello e pulsante «Aggiungi al carrello» (T4); checkout e Stripe (T5).
- Piè di pagina legale, pagine legali, citazione dell'articolo del Codice del consumo (T8).
  Tema per negozio, Tailwind.
- Back-office e modifica del catalogo da parte del negozio.
- Da `traguardi.json → fuori_perimetro`: back-office del negozio (piano B: email e dashboard
  Stripe); tipo di consegna CEMETERY; capacità per fascia oraria; impianto consensi e GDPR oltre la
  pagina privacy; SPF e DKIM completi.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|
| `Giro005CatalogAcceptanceTest` | Dato il seme demo eseguito due volte e il negozio `fiori-di-marco` con lo stesso slug prodotto, quando si chiedono catalogo, categorie e schede con i diversi Host, allora conteggi, contenuti, ordine, prezzi (U+00A0), testi e 404 come sopra |
| `Giro005DemoSeedConfigAcceptanceTest` | Dato `application.properties`, allora il seme è acceso solo in dev e prod e `flyway.locations` non cambia |
| `Giro005CatalogVisualAcceptanceTest` | Dato il seme demo, quando Chromium apre la scheda del bouquet a 390×844, allora vede l'`h1` e salva `storefront-product.png` |
| `Giro005DemoData` (aiuto) | Esegue il seme dal classpath (se manca: fallisce con un'asserzione) e inserisce il negozio di Marco via JDBC |

## Porte
- Doppio senso previste: migrazione additiva `V3__catalog.sql` (solo `create table`, `create
  index`, vincoli unici); seme come file SQL idempotente eseguito da un bean `@Startup` acceso da
  `app.demo.seed` (si spegne con una riga di configurazione); URL in italiano `/catalogo`,
  `/prodotti`; formato prezzi `25,00 €` con U+00A0; testi della scheda; regola «prodotto senza
  varianti attive = invisibile». Il seme scrive in produzione solo dati inventati, su un host che
  Caddy ancora non serve.
- Senso unico: nessuna. Nessuna dipendenza nuova (Qute, Panache, Flyway, Playwright ci sono già).
  Nessuna riga `flyway.*` toccata: `pr_guard` non chiede revisione umana per la configurazione.

## Rischi di concorrenza
Solo letture nelle pagine. Il seme gira all'avvio, un'istanza sola; l'`on conflict` lo rende
idempotente anche se rieseguito (il test lo esegue due volte e conta le righe). Il bean deve
partire dopo Flyway (in Quarkus la migrazione all'avvio precede `@Startup`; lo prova il `curl` in
dev). Dati condivisi tra classi di test: ogni test pulisce tutto in `@AfterEach`.

## Budget
Righe di produzione ≤ 450 (SQL compreso); file ≤ 14; una sessione.

## Domande per il ricercatore (al massimo 3)
1. Come mostrano prezzo, varianti di misura e «IVA inclusa» le schede prodotto delle fioraie
   online italiane? Il formato `25,00 €` e «da 25,00 €» sono quelli attesi?
2. Formula per l'esclusione del recesso sui beni deperibili da mostrare sulla scheda.
3. Flyway `R__` in una posizione attiva solo in alcuni profili: controindicazioni?
   (Superata: il seme non è più una migrazione Flyway.)

## Idee per dopo
- Cache del catalogo per negozio (richiede `quarkus-cache`, dipendenza nuova).
- Dati strutturati `Product` JSON-LD per la scheda (§4.3 `seo/`).
- Verificare su Normattiva la lettera dell'art. 59 del Codice del consumo (d o e) per i beni
  deperibili, registrarla in `docs/verifiche.md` e poi citarla nell'avviso di recesso (T8).
- Con una sola variante, prezzo semplice invece di «da …» (ricerca, idee per dopo).

## Critica ricevuta e cosa cambia
Accolto (5 su 5, più il «più semplice» e un facoltativo):
1. Le righe `%prod./%dev.quarkus.flyway.locations` facevano scattare `DANGEROUS_CONFIG` in
   `pr_guard` (revisione umana) contro «Senso unico: nessuna». Tolte: nessuna riga `flyway.*`.
2. Il seme `R__` in `db/demo` usciva dai controlli sulle migrazioni e la sua rimozione richiedeva
   un `DELETE` non ammesso. Ora è un file SQL normale in `demo/`, eseguito da un bean `@Startup`
   con `app.demo.seed` (proposta «più semplice» del critico); la rimozione è fuori perimetro.
3. Formato prezzo: scritto il carattere esatto, U+00A0 tra cifra e `€`; i test lo usano.
4. Assenze verificate con `Avvento`, `corona-avvento`, `stella-di-natale`, `/catalogo/natale`, non
   col nome con l'apostrofo (Qute lo scrive `&#39;`).
5. Prodotto attivo senza varianti attive: invisibile e scheda 404. Aggiunto `bouquet-sposa` ai dati
   per provarlo.
- Vincolo unico `(product_id, name)` su `product_variant` in V3 per l'`on conflict`: accolto.
- Facoltativo: `fiori-di-marco` ha un prodotto con lo stesso slug `bouquet-di-stagione`: accolto,
  rende l'isolamento più stringente.
- Facoltativo: non citare l'art. 59 finché non è verificato: accolto (già così); la verifica va in
  «Idee per dopo», non in questo giro, perché non serve alla fetta.
Scartato: nulla.
