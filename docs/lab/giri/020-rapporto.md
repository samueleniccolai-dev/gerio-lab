---
giro: 020
traguardo: fine
esito: pr-aperta
inizio: 2026-10-16T12:26:06Z
fine: 2026-10-16T12:28:35Z
pr: 25
modello_orchestratore: sonnet
modello_costruttore:
modello_revisore:
ricerca_fonti: 0
critico_da_correggere: 0
critico_accolti: 0
revisore_bloccanti: 0
revisore_dubbi: 0
dubbi_corretti: 0
fuori_perimetro: 0
mutazioni_provate: 0
mutazioni_prese: 0
verify_rossi_locali: 0
test_totali: 430
test_delta: 0
decisioni_registro: 0
decisioni_ratifica: 0
porte_senso_unico: 0
riparazioni: 0
difetto_di:
---
# Rapporto del giro 020 — fine dell'esperimento

> **PR:** #25 · **Branch:** `claude/giro-020-fine` · **Traguardo:** nessuno (chiusura)
> **Domande che bloccano:** nessuna

## Per te, in 10 righe
- Tutti i traguardi T1–T8 sono passati: l'esperimento finisce.
- `docs/lab/CONCLUSIONI.md` confronta H1–H5: H1, H2, H4 confermate; H3 non misurabile; H5 smentita di poco (120,4 h contro 114,5).
- Aggiunto `docs/lab/STOP`: dopo il merge la catena non avvia altri giri.
- Nessun codice di prodotto toccato: la PR va in revisione umana (niente verdetto del revisore).
- Le metriche sono state ricalcolate; alcuni campi API erano vuoti (`gh` non rispondeva).
- Da leggere ancora: le voci «Per l'umano» di PROGRESS (testi legali, ratifiche, anonimizzazione entro 10/2027).

## Cosa funziona ora — con la prova
- Conclusioni scritte (`docs/lab/CONCLUSIONI.md`) con i numeri di `STATISTICHE.md`.

## Cosa non funziona ancora
- Metriche del giro 019/020 parziali per l'API GitHub non disponibile durante la raccolta.
- H3 non confrontabile: Gerio non registrava i difetti post-merge.

## Verdetto del revisore
Nessun codice: niente revisione.

## Ricerca e critica
Non previste in questo giro.

## Prossima fetta
Nessuna: esperimento concluso.
