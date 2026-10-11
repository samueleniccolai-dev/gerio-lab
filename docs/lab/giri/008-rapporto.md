---
giro: 008
traguardo: T4
esito: pr-aperta
inizio: 2026-10-11T21:46:27Z
fine: 2026-10-11T22:13:13Z
pr: 11
modello_orchestratore: sonnet
modello_costruttore: sonnet
modello_revisore: opus
ricerca_fonti: 4
critico_da_correggere: 5
critico_accolti: 5
revisore_bloccanti: 0
revisore_dubbi: 3
dubbi_corretti: 0
fuori_perimetro: 1
mutazioni_provate: 4
mutazioni_prese: 4
verify_rossi_locali: 2
test_totali: 251
test_delta: 16
decisioni_registro: 3
decisioni_ratifica: 2
porte_senso_unico: 1
riparazioni: 0
difetto_di:
---
# Rapporto del giro 008 — carrello del negozio demo (T4)

> **PR:** #11 · **Branch:** `claude/giro-008-t4` · **Traguardo:** T4 — in corso (manca la prova dopo il deploy)
> **Domande che bloccano:** nessuna (CSRF: risposta A del 11/10, da Gerio sì)

## Per te, in 10 righe
- Il cliente del negozio demo aggiunge, cambia e toglie prodotti da `/carrello`, con form classici.
- Dipendenza nuova `quarkus-rest-csrf` (tua risposta A): la PR va in revisione umana (`pom.xml`).
- Migrazione `V4__cart.sql`: solo aggiunte. Cookie `cart` tecnico, 7 giorni, senza banner.
- Ratifiche: token del carrello sempre generato dal server; il CSRF dà 400 ai POST non form (webhook Stripe in T5).
- La sonda `EndpointIsolationTest` passa a POST form con token (conseguenza della dipendenza).
- Da te: nulla. Dopo il deploy va fatta la prova a mano dei passi 1–6.

## Cosa funziona ora — con la prova
- Passi 1–6 del carrello, errori 404/400, isolamento tra negozi e tra browser, 10 aggiunte parallele (prova: `Giro008*AcceptanceTest`, 12 test verdi).
- `./mvnw verify` verde dal revisore: 251 test, 0 fallimenti.

## Cosa non funziona ancora
- Prova a mano sul demo in produzione non eseguita; per questo T4 è «in-corso».
- Localmente i 2 test Playwright falliscono per Chromium mancante nell'ambiente (la CI li esegue).
- Avviso «massimo 20», htmx, contatore nell'intestazione, pulizia carrelli vecchi: rimandati.
- Webhook Stripe e filtro CSRF: da risolvere in T5. `token-signature-key` non impostato.

## Verdetto del revisore
- **BLOCCANTI:** nessuno.
- **DUBBI:** 3, nel rapporto (400 ai POST non form; token CSRF senza firma; FK variante senza `on delete`).
- **PERIMETRO:** sonda di isolamento cambiata a POST form (conseguenza della dipendenza).
- **CONCORRENZA:** 10 rimuovi paralleli → 1×303 e 9×404; 15 richieste miste senza 500.
- **MUTAZIONI:** provate 4, prese 4.

## Ricerca e critica
Presi: campo CSRF nei form, `SameSite=Lax`, label visibile. Critica: 5 punti accolti (colonne di audit, niente SQL nativo, lock sul carrello, token solo server, quantità 0 → 400). Nessun concorrente consultato dal ricercatore.

## Prossima fetta
T5: checkout e ordine con Stripe in modalità test.
