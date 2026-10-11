---
giro: 005
traguardo: T3
esito: pr-aperta
inizio: 2026-10-11T18:17:10Z
fine: 2026-10-11T18:38:22Z
pr: 8
modello_orchestratore: sonnet
modello_costruttore: sonnet
modello_revisore: opus
ricerca_fonti: 6
critico_da_correggere: 5
critico_accolti: 5
revisore_bloccanti: 0
revisore_dubbi: 2
dubbi_corretti: 0
fuori_perimetro: 0
mutazioni_provate: 3
mutazioni_prese: 3
verify_rossi_locali: 1
test_totali: 230
test_delta: 16
decisioni_registro: 4
decisioni_ratifica: 2
porte_senso_unico: 0
riparazioni: 0
difetto_di:
---
# Rapporto del giro 005 — catalogo e pagina prodotto del negozio demo

> **PR:** #8 · **Branch:** `claude/giro-005-t3` · **Traguardo:** T3 — parziale
> **Domande che bloccano:** nessuna

## Per te, in 10 righe
1. Il negozio demo «Fioreria Il Glicine» ha catalogo (`/catalogo`), categorie (`/catalogo/{slug}`) e scheda prodotto (`/prodotti/{slug}`), con dati inventati.
2. Prezzi in centesimi, mostrati `25,00 €` o «da 25,00 €»; scheda con IVA inclusa, nota di stagione e recesso escluso.
3. Un negozio non vede i prodotti dell'altro, nemmeno con lo stesso slug (provato in due direzioni).
4. Il seme gira da solo all'avvio in dev e prod (`app.demo.seed`): ratifica n. 8.
5. Testi della scheda e formato prezzo: ratifica n. 9. L'articolo del Codice del consumo non è citato: la lettera non è verificata.
6. Nessuna porta a senso unico, nessuna riga `flyway.locations` nuova: `pr_guard` dice `auto-merge`.
7. Il test visivo (Chromium) non gira nel sandbox: Playwright vuole 1243, c'è solo 1194. Il revisore l'ha eseguito con il browser giusto e la build è verde (230 test). La CI lo rifà.
8. Manca ancora `demo.<LAB_BASE_DOMAIN>` in produzione (Caddy): giro 006, in revisione umana.
9. Dubbio: le FK di V3 usano solo `id`, non `(tenant_id, id)`; oggi non sfruttabile.
10. Nulla da fare per te.

## Cosa funziona ora — con la prova
- Catalogo, categoria e scheda sull'host del negozio (prova: `Giro005CatalogAcceptanceTest`, 11 test verdi).
- Seme idempotente, eseguito due volte (prova: test di accettazione; 12 esecuzioni parallele del revisore).
- Screenshot `storefront-product.png` 390×844 (prova: revisore).

## Cosa non funziona ancora
- `demo.<LAB_BASE_DOMAIN>` non è raggiungibile in produzione.
- Nessun test dedicato di isolamento su `CatalogService` (coperto dall'accettazione).
- FK senza `tenant_id`: da correggere con migrazione additiva prima che il negozio modifichi il catalogo.
- Test visivo non eseguito dal costruttore (Chromium 1243 assente).

## Verdetto del revisore
- **BLOCCANTI:** nessuno.
- **DUBBI:** 2 (FK senza tenant; manca test di servizio) — nel rapporto, non corretti.
- **PERIMETRO:** nulla fuori piano.
- **CONCORRENZA:** seme ×12 in parallelo ok; 1200 GET su 64 thread senza fughe.
- **MUTAZIONI:** provate 3, prese 3.

## Ricerca e critica
Presi: formato prezzo con «da», avviso di recesso in evidenza, seme senza toccare Flyway. Critica: 5 punti su 5 accolti (guard su flyway.locations, seme fuori dai controlli, spazio U+00A0, assenze cercate per slug, prodotto senza varianti → 404) più seme `@Startup`.

## Prossima fetta
Giro 006: `demo.<LAB_BASE_DOMAIN>` in Caddy e smoke test.
