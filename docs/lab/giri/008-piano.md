---
giro: 008
traguardo: T4
fase: critica
inizio: 2026-10-11T21:46:27Z
ricerca: sì
accettazione:
ripreso: no
---
# Piano del giro 008 — Carrello del negozio demo, senza JavaScript

## RISPOSTA RICEVUTA: opzione A (`quarkus-rest-csrf`), 11/10, da Gerio: sì

## DOMANDA (porta a senso unico: dipendenza nuova) — risolta

**Contesto.** Il carrello porta i primi form `POST` della vetrina. SPEC §10.2 chiede «protezione
CSRF su tutti i form (estensione CSRF di Quarkus)». Nel `pom.xml` l'estensione non c'è: aggiungerla
è una dipendenza nuova (CLAUDE.md regola 2). Le tre strade:

| Opzione | Cosa | Pro | Contro |
|---|---|---|---|
| **A. `io.quarkus:quarkus-rest-csrf`** | Estensione ufficiale Quarkus, versione dal BOM `quarkus-bom` 3.33.4 (nessuna versione scritta nel `pom.xml`) | È quella che chiede SPEC §10.2; mantenuta dal team Quarkus, aggiornata col BOM; token in Qute con `{inject:csrf.token}`; serve comunque al checkout (T5) | Un'estensione in più nel build e nell'immagine; il comportamento sui `POST` non-form (webhook Stripe di T5) va verificato [DA VERIFICARE] |
| B. Nessuna dipendenza, per ora | Cookie del carrello `SameSite=Lax` + controllo di `Origin`/`Sec-Fetch-Site` sui `POST`; l'estensione si decide in T5 | Nessuna porta oggi; il carrello è anonimo e senza dati personali, il danno di un CSRF è basso | Scostamento da SPEC §10.2 da registrare; la stessa domanda torna in T5; difesa scritta a mano da mantenere |
| C. Token fatto a mano | Token sincronizzato in un cookie firmato, filtro nostro | Nessuna dipendenza | Codice di sicurezza scritto in casa: proprio ciò che §1.4 dice di affidare ad altri |

**Raccomandazione: A.** Lo prescrive la specifica, viene dal BOM già in uso, e T5 ne avrà bisogno
comunque. Alternativa ragionevole: B.

**Dopo ogni risposta.**
- **A:** il costruttore aggiunge la dipendenza (senza versione) al `pom.xml` (PR in revisione
  umana, METODO §4); i form del carrello portano il campo nascosto del token; i test di
  accettazione prendono cookie e token dalla pagina prima di ogni `POST`, e c'è un test in più:
  `POST` senza token → 400, carrello invariato. Registro in `DECISIONI.md`.
- **B:** nessuna modifica al `pom.xml`; un test in più: `POST` con `Origin` di un altro host → 403,
  carrello invariato. Riga nuova in `docs/scostamenti-4-10.md` (§10.2 rimandato a T5).
- **C:** sconsigliata; se scelta, come B ma con il token nel form e un test `POST` senza token → 403.

Il resto del piano vale per tutte e tre le risposte.

## Fetta e traguardo
T4 intero: il cliente aggiunge, cambia e toglie prodotti dal carrello del negozio demo. Primo
traguardo non passato; con form classici (SPEC §4.4, miglioramento progressivo) sta in una sessione.

## Cosa si costruisce
- **Migrazione `V4__cart.sql`** (solo aggiunte), regole ADR-0003 punto 8 (`SchemaRulesTest`):
  - `cart`: `id bigint` generato (come le altre tabelle, per `EntityBase`), `tenant_id`, `token text
    not null unique` (casuale), `created_at`, `updated_at`, `version`. Scostamento minore da §6 (lì
    l'id è il token): da annotare in `docs/scostamenti-4-10.md`.
  - `cart_item`: `id`, `tenant_id`, `cart_id` → `cart` (`on delete cascade`), `variant_id`,
    `quantity int not null check (quantity between 1 and 20)`, `unique (cart_id, variant_id)`.
    Le FK verso `cart` e `product_variant` sono **composte con `tenant_id`**
    (`(tenant_id, variant_id) references product_variant (tenant_id, id)`): una riga non può
    puntare a una variante di un altro negozio. Niente `addon_id` (la tabella `addon` non esiste).
- **Package `cart/`**: `Cart`, `CartItem`, `CartService` (§4.3). `CartService` chiede le varianti al
  catalogo tramite un metodo di `CatalogService` (nome, variante, prezzo corrente in centesimi,
  visibilità), non legge entità del catalogo.
- **Cookie tecnico** `cart`: token casuale (≥ 32 byte da `SecureRandom`, base64url), `HttpOnly`,
  `SameSite=Lax`, `Path=/`, `Max-Age` 7 giorni (§12.4), `Secure` fuori da dev e test. Il cookie vale
  per l'host, quindi per il negozio; il server comunque cerca il carrello per token **e** negozio:
  un token di un altro negozio è un carrello vuoto. Il cookie si crea solo alla prima aggiunta.
- **Pagine e azioni** (risorsa in `storefront/`, nessuna logica di business nella risorsa):

| Metodo e percorso | Parametri form | Effetto | Risposta |
|---|---|---|---|
| `GET /carrello` | — | righe e totale | 200 HTML |
| `POST /carrello/aggiungi` | `variantId`, `quantity` (default 1) | aggiunge; se la variante c'è già somma le quantità (massimo 20) | 303 → `/carrello` |
| `POST /carrello/righe/{itemId}` | `quantity` | imposta la quantità; `0` toglie la riga | 303 → `/carrello` |
| `POST /carrello/righe/{itemId}/rimuovi` | — | toglie la riga | 303 → `/carrello` |

  Errori: variante inesistente, inattiva, di prodotto inattivo o di un altro negozio → 404;
  `quantity` non numerica, negativa o oltre 20 → 400; riga di un altro carrello o di un altro
  negozio → 404. In tutti i casi il carrello resta com'era. Sul dominio della piattaforma tutte le
  rotte del carrello → 404 (come il catalogo).
- **Scheda prodotto**: per ogni variante un form «Aggiungi al carrello» (campo `variantId`
  nascosto). **Layout**: link «Carrello» nell'intestazione.
- **Pagina carrello**: titolo «Carrello»; per ogni riga nome del prodotto con link alla scheda,
  variante, prezzo unitario, campo quantità con etichetta (`<label>`) e pulsante «Aggiorna»,
  pulsante «Rimuovi», subtotale della riga; in fondo «Totale prodotti» con l'importo e la nota
  «IVA inclusa. Consegna calcolata al checkout.» Carrello vuoto: «Il tuo carrello è vuoto.» con
  link al catalogo. I prezzi sono quelli correnti della variante (la fotografia del prezzo si fa
  nell'ordine, T5). Importi `long` in centesimi, formattati solo con `Prices.format`.

## Dati del negozio demo (già nel seme, nessun cambio)
Host `demo.localhost` (test). Scenario dei test di accettazione:

| Passo | Azione | Righe attese | Totale prodotti |
|---|---|---|---|
| 1 | aggiungi «Bouquet di stagione» · Medio (3500) × 2 | Medio × 2 = 70,00 € | 70,00 € |
| 2 | aggiungi «Orchidea Phalaenopsis bianca» · Vaso 12 cm (2990) × 1 | + 29,90 € | 99,90 € |
| 3 | aggiungi di nuovo Medio × 1 | Medio × 3 = 105,00 € (una riga sola) | 134,90 € |
| 4 | cambia l'orchidea a 2 | 59,80 € | 164,80 € |
| 5 | rimuovi Medio | solo orchidea × 2 | 59,80 € |
| 6 | cambia l'orchidea a 0 | nessuna | carrello vuoto |

Varianti da rifiutare (404): «Bouquet di stagione» · Extra (6900, inattiva); «Corona dell'Avvento»
· Unica (prodotto inattivo); «Stella di Natale» (categoria nascosta); la variante di «Fiori di
Marco» (secondo negozio di `Giro005DemoData`).

## Fatto quando — controlli eseguibili
- [ ] `./mvnw -q -Dtest='Giro008*AcceptanceTest' test` → verde. Copre:
  - [ ] `GET /carrello` su `demo.localhost` senza cookie → 200, «Il tuo carrello è vuoto.», link `/catalogo`.
  - [ ] La scheda `/prodotti/bouquet-di-stagione` ha un form `POST /carrello/aggiungi` per ognuna delle 3 varianti attive (nessuno per Extra).
  - [ ] Passi 1–6 della tabella: ogni `POST` → 303 su `/carrello`; la pagina mostra righe, subtotali e «Totale prodotti» attesi.
  - [ ] La prima aggiunta imposta il cookie `cart` con `HttpOnly`, `SameSite=Lax`, `Path=/`, `Max-Age=604800`.
  - [ ] Variante inattiva, prodotto inattivo, categoria nascosta, variante di Marco → 404, carrello invariato.
  - [ ] `quantity` = `-1`, `21`, `abc` → 400, carrello invariato; aggiunte che superano 20 → quantità 20.
  - [ ] Isolamento: il cookie del carrello demo inviato a `marco.localhost` → carrello vuoto; `POST /carrello/righe/{id della riga demo}` da Marco → 404 e la riga demo resta; un token inventato → carrello vuoto, nessun errore 500.
  - [ ] Sul dominio della piattaforma (`localhost`) `GET /carrello` → 404.
  - [ ] Difesa CSRF secondo la risposta alla DOMANDA (A: senza token → 400; B: `Origin` estraneo → 403); carrello invariato.
  - [ ] Due `POST /carrello/aggiungi` concorrenti della stessa variante sullo stesso carrello → una riga sola, quantità 2.
- [ ] `./mvnw -q -Dtest=SchemaRulesTest,TenantIsolationTest,EndpointIsolationTest test` → verde con le tabelle nuove.
- [ ] `./mvnw -B -ntp verify` → verde (anche la verifica visiva esistente).
- [ ] Dopo il deploy: `curl -s https://demo.<LAB_BASE_DOMAIN>/carrello` → 200 con «Il tuo carrello è vuoto.»; prova a mano dei passi 1–6 nel browser, annotata nel rapporto.

## Fuori perimetro
- Da `traguardi.json → fuori_perimetro`: back-office del negozio; tipo di consegna `CEMETERY`;
  capacità per fascia; impianto consensi e GDPR oltre la pagina privacy; SPF e DKIM.
- htmx nel carrello (aggiornamenti senza ricaricare): il file htmx non è ancora nel repository ed
  è un asset nuovo; i form classici bastano al traguardo (§4.4).
- Tailwind e stile della pagina; contatore degli articoli nell'intestazione.
- Aggiunte/extra (`addon`), biglietto, messaggi: non esistono ancora nel catalogo.
- Costo di consegna, zone, date (T7); checkout e fotografia dei prezzi nell'ordine (T5).
- Job che cancella i carrelli abbandonati dopo 7 giorni (§12.4): il cookie scade, le righe restano.
- Limiti di frequenza (§10.2) sulle aggiunte.
- Sistemare le FK di V3 senza `tenant_id` (dubbio in PROGRESS): non si tocca una migrazione su `main`.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|
| `Giro008CartAcceptanceTest` | dato il seme demo, quando si eseguono i passi 1–6, allora righe e totali sono quelli della tabella |
| `Giro008CartAcceptanceTest` | dato il seme, quando si aggiungono varianti non vendibili o quantità non valide, allora 404/400 e carrello invariato |
| `Giro008CartIsolationAcceptanceTest` | dati demo e Marco, quando il cookie o l'id di riga di un negozio arrivano all'altro, allora carrello vuoto o 404 |
| `Giro008CartAcceptanceTest` | dato un `POST` senza difesa CSRF valida, allora rifiutato (codice secondo la risposta) |

## Porte
- Doppio senso previste: migrazione additiva `V4__cart.sql`; nomi di rotte e cookie; quantità
  massima 20; `cart.id` bigint con `token` a parte (scostamento minore da §6); FK composte con
  `tenant_id` sulle tabelle nuove.
- Senso unico: **DOMANDA** sopra (dipendenza `quarkus-rest-csrf`).

## Rischi di concorrenza
- Doppio clic su «Aggiungi»: due inserimenti della stessa variante. Difesa: `unique (cart_id,
  variant_id)` e un solo `insert … on conflict … do update set quantity = least(quantity + n, 20)`;
  test con due richieste parallele.
- Doppio invio di «Aggiorna» o «Rimuovi»: «Aggiorna» imposta, non somma, quindi è idempotente; il
  secondo «Rimuovi» trova una riga che non c'è più → 404 senza effetti. Accettato.
- Prima aggiunta in due schede insieme senza cookie: nascono due carrelli, vince l'ultimo cookie;
  l'altro resta orfano fino alla pulizia. Accettato.

## Budget
Righe di produzione ≤ 450 (Java + template + SQL); file ≤ 12; una sessione.

## Domande per il ricercatore (al massimo 3)
1. `quarkus-rest-csrf` 3.33: come si usa con Qute e form classici (nome del campo, cookie, codice
   d'errore) e come si comporta con `POST` non-form, come il futuro webhook Stripe (T5)?
2. Carrello di un piccolo e-commerce su telefono, senza JavaScript: campo quantità (numero,
   select, +/−), rimozione con o senza conferma, accessibilità WCAG 2.1 AA delle etichette.
3. Cookie del carrello: `SameSite=Lax` o `Strict`, tenendo conto del ritorno dal redirect di Stripe
   Checkout in T5; resta «tecnico» senza banner (§12.3)?

## Idee per dopo
- htmx per aggiornare il carrello senza ricaricare (§4.4), con il file servito in locale.
- Contatore «Carrello (n)» nell'intestazione.
- Job di pulizia dei carrelli oltre 7 giorni (§12.4).
- Fusione dei carrelli nati in due schede.

## Critica ricevuta e cosa cambia
(dopo il critico)
