# DECISIONI — tre livelli (METODO §3)

- **registro:** dettagli interni reversibili; nessuna ratifica.
- **ratifica:** cosa vede o paga il cliente, dati salvati, costi ricorrenti, scelte care da cambiare.
  Massimo 3 per giro. Per contestarne una, commenta la PR del giro: `contesto n. X: …`.
- **senso unico:** il giro si è fermato con una PR `domanda`; qui va la risposta dell'umano.

Stato: `—` (in vigore) · `contestata` · `cambiata (giro NNN)` · `risposta del GG/MM`.

| N. | Data | Giro | Livello | Decisione | Alternativa scartata | Motivo (una riga) | Stato |
|---|---|---|---|---|---|---|---|
| 1 | 11/10 | 001 | registro | `infra/lib.sh` condiviso da deploy.sh e rollback.sh | codice duplicato | evita di copiare chiave SSH e known_hosts | — |
| 2 | 11/10 | 001 | registro | Immagini fissate: caddy:2.11.7, postgres:17.11; utente DB fisso `platform` | tag larghi, DB_USER variabile | meno stato da ripristinare | — |
| 3 | 11/10 | 001 | registro | Rollback ripristina solo se l'ultimo deploy era riuscito (`last-good`) | salvare sempre lo stato precedente | non salvare come «precedente» uno stato rotto | — |
| 4 | 11/10 | 001 | registro | Token GHCR solo su stdin di ssh verso `remote.sh`; `docker logout` nel trap | token in riga di comando | il token non compare in `ps` | — |
| 5 | 11/10 | 001 | ratifica | Dopo ogni deploy 503 per alcuni secondi (health check di Caddy, fino a ~30 s) | health_interval corto | accettabile per la demo; nel runbook | — |
| 6 | 11/10 | 002 | registro | `passato_il` di T1 = fine del passo «Deploy and smoke test» (15:25:09Z) | ora di esecuzione di traguardo.py | il criterio chiede lo smoke verde | — |
| 7 | 11/10 | 003 | senso unico | Dipendenza E2E: Playwright Java 1.63.0, scope `test`, solo Chromium | Quarkiverse; Selenium + Testcontainers | risposta umana: «Procedi con A» (da Gerio: sì) | risposta del 11/10 |
| 8 | 11/10 | 005 | ratifica | Il negozio demo «Fioreria Il Glicine» (dati inventati) si carica da solo all'avvio in dev e prod (`app.demo.seed`) | migrazione ripetibile `R__` in `db/demo` | non tocca `flyway.locations` né i controlli sulle migrazioni; si toglie senza «missing migration» | — |
| 9 | 11/10 | 005 | ratifica | Scheda prodotto: «IVA inclusa», avviso di stagione, recesso escluso per i deperibili (senza citare l'articolo), prezzi `25,00 €` e «da 25,00 €» | citare art. 59 lett. d | la lettera non è verificata su Normattiva; formula completa in T8 | — |
| 10 | 11/10 | 005 | registro | `product_variant.price_cents` è `bigint` (SPEC §6 dice `int`) | `int` | CLAUDE.md chiede `long`; Hibernate rifiuta long su int4 | — |
| 11 | 11/10 | 005 | registro | Entità e `CatalogService` nel package `catalog`, `CatalogResource` in `storefront`, seme in `demo` | tutto in `storefront` | separa dati e vetrina (§4.3) | — |
| 12 | 11/10 | 005 | registro | Visibilità (prodotto attivo, categoria visibile, ≥1 variante attiva) nel servizio; altrimenti 404 | logica nella risorsa | niente logica nelle risorse | — |
| 13 | 11/10 | 005 | registro | `Prices.format` scritto a mano con U+00A0 | `NumberFormat` | l'uscita non dipende dai dati locale del JDK | — |
| 14 | 11/10 | 006 | registro | Il Caddyfile serve due nomi espliciti (`base, demo.base`) in un solo blocco, senza on-demand TLS | on-demand TLS di App. B.3; snippet | un solo negozio pubblico; si toglie con un commit | — |
