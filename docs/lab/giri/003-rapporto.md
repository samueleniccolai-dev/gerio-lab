---
giro: 003
traguardo: T2
esito: pr-aperta
inizio: 2026-10-11T15:36:45Z
fine: 2026-10-11T17:46:00Z
pr: 5
modello_orchestratore:
modello_costruttore: sonnet
modello_revisore: opus
ricerca_fonti: 0
critico_da_correggere: 2
critico_accolti: 2
revisore_bloccanti: 0
revisore_dubbi: 3
dubbi_corretti: 0
fuori_perimetro: 0
mutazioni_provate: 3
mutazioni_prese: 3
verify_rossi_locali: 0
test_totali: 214
test_delta: 5
decisioni_registro: 1
decisioni_ratifica: 0
porte_senso_unico: 1
riparazioni: 0
difetto_di:
---
# Rapporto del giro 003 — verifica visiva (T2): primo E2E con browser reale

> **PR:** #5 · **Branch:** `claude/giro-003-t2` · **Traguardo:** T2 — parziale (manca la prova in CI)
> **Domande che bloccano:** nessuna (porta sulla dipendenza: risposta A, 11/10)

## Per te, in 10 righe
- Nessun cambiamento visibile al cliente: entrano solo test e un binario di test.
- Dipendenza nuova, da te approvata (A): Playwright 1.63.0, scope `test`; non finisce nell'immagine.
- 5 test E2E con Chromium vero: vetrina `Le Rose di Lucia`, vetrina `Fiori di Marco` (isolamento nei due sensi), landing senza nomi di negozio.
- Screenshot 390×844 in `target/e2e-screenshots/` (`storefront-home.png`, `platform-landing.png`).
- La PR va in revisione umana: tocca `pom.xml`.
- Da fare dopo `ready_for_review`: controllare che il run `ci` sia verde e abbia l'artefatto `e2e-screenshots`.
- Rischio: Ubuntu 26 dal 19/10 e librerie di Chromium; Chromium si riscarica a ogni CI (~100 MB).
- Nessuna ratifica nuova.

## Cosa funziona ora — con la prova
- `./mvnw -B -ntp verify`: 214 test, 0 falliti (revisore, copia isolata).
- Browser assente = test rosso, mai saltato (sonda del revisore).
- 3 mutazioni su 3 prese (filtro negozio, riga verifiche, h1 landing).

## Cosa non funziona ancora
- Run `ci` verde con artefatto `e2e-screenshots`: non provabile finché la PR è bozza (`ci` salta). T2 resta `in-corso`.
- Nessun `scripts/ci/prepare.sh`: si aggiunge solo se Chromium non parte in CI.

## Verdetto del revisore
- **BLOCCANTI:** nessuno.
- **DUBBI:** 3 (CI aperta, librerie in CI, PROGRESS in ritardo — quest'ultimo corretto qui).
- **PERIMETRO:** nessuna aggiunta; nessun codice di produzione.
- **CONCORRENZA:** nessun percorso di scrittura nuovo; 3 esecuzioni consecutive verdi.
- **MUTAZIONI:** provate 3, prese 3.

## Ricerca e critica
Nessuna ricerca. Critica: 2 punti, accolti entrambi (test indipendenti dall'ordine; download del browser dietro proxy), più i suggerimenti sui test. Scartata la classe di supporto `e2e/` (rimandata a T3).

## Prossima fetta
Chiudere T2 con la prova della CI, poi T3.
