# Statistiche — laboratorio (metodo v3) contro Gerio (metodo v2)

> Generato da `scripts/metrics/statistiche.py` il 2026-10-12 06:48 UTC. Dati: `metrics/giri.csv`, `metrics/baseline-gerio.csv`. Definizioni e cautele: `docs/ESPERIMENTO.md`.
>
> T0 di Gerio: 2026-10-04 14:06 UTC · T0 del laboratorio: 2026-10-11T09:43:32Z · traguardi passati dal laboratorio: 4 su 8.

## Traguardo per traguardo

Gerio ↔ laboratorio. Ore dal T0 al merge che chiude il traguardo; «decisioni all'umano» = in Gerio ogni
decisione registrata più le domande bloccanti, nel laboratorio ratifiche più porte a senso unico.

| Traguardo | Ore dal T0 | Cicli ↔ giri | Minuti umani | Decisioni all'umano | Rilavorazioni | Minuti di CI | Righe in src/main |
|---|---|---|---|---|---|---|---|
| T1 Binari, parte 1: deploy automatico ✅ | 6,7 ↔ 5,8 | 1 ↔ 2 | 30 ↔ 0 | 6 ↔ 1 | 3 ↔ 0 | 6,9 ↔ 3,6 | 0 ↔ 0 |
| T2 Binari, parte 2: verifica visiva ✅ | 19,4 ↔ 8,5 | 1 ↔ 2 | 25 ↔ 2 | 4 ↔ 1 | 0 ↔ 0 | 8,6 ↔ 4,5 | 0 ↔ 0 |
| T3 Catalogo + pagina prodotto, con dati finti da fioraia ✅ | 24,7 ↔ 12,0 | 1 ↔ 3 | 30 ↔ 0 | 8 ↔ 2 | 1 ↔ 0 | 12,7 ↔ 7,2 | 971 ↔ 581 |
| T4 Carrello ✅ | 32,3 ↔ 20,9 | 1 ↔ 2 | 35 ↔ 2 | 11 ↔ 3 | 0 ↔ 0 | 14,8 ↔ 0,5 | 672 ↔ 470 |
| T5 Checkout con Stripe Checkout in test + webhook idempotente | 73,2 ↔ — | 2 ↔ 0 | 25 ↔ — | 25 ↔ — | 0 ↔ — | 12,9 ↔ — | 2.237 ↔ — |
| T6 Email di conferma + pagina stato ordine | 102,3 ↔ — | 2 ↔ 0 | 40 ↔ — | 19 ↔ — | 1 ↔ — | 31,1 ↔ — | 1.201 ↔ — |
| T7 Zone di consegna e orario limite, versione semplice | 114,5 ↔ — | 1 ↔ 0 | 20 ↔ — | 16 ↔ — | 1 ↔ — | 24,6 ↔ — | 1.100 ↔ — |
| T8 Pagine legali minime + rifiniture demo | — ↔ — | 0 ↔ 0 | — ↔ — | — ↔ — | — ↔ — | — ↔ — | — ↔ — |

Gerio fuori dal piano del 4/10: 5 cicli (5, 11, 12, 13, 14), 4.077 righe in src/main, 110 minuti umani stimati, 55 decisioni.

Gerio, cicli senza stima dei minuti umani nel rapporto: 6. I minuti umani dei loro traguardi sono un minimo.

## Ipotesi (solo sui traguardi già passati dal laboratorio)

| # | Ipotesi | Laboratorio | Gerio | Esito |
|---|---|---|---|---|
| H1 | minuti umani ≤ 50% di Gerio | 4 | 120 | in linea (3%) |
| H2 | ≤ 5 decisioni all'umano per traguardo | 1,8 | 7,2 | in linea |
| H3 | difetti dopo il merge ≤ Gerio | 0 giri di correzione (`difetto_di`) | non misurato in Gerio | da valutare alla fine |
| H4 | deriva < 10% delle righe di produzione | 0,0% | 31,1% | in linea |
| H5 | ore fino a T7 ≤ Gerio (secondaria) | — | 114,5 | dati insufficienti |

## Giri del laboratorio

| Giro | Traguardo | Esito | PR | Minuti | Merge automatico | Bloccanti | Dubbi | Mutazioni prese/provate | Minuti umani |
|---|---|---|---|---|---|---|---|---|---|
| 001 | T1 | mergiata | #2 | 26 | no | 0 | 4 | 2/3 | 0 |
| 002 | T1 | mergiata | #3 | 7 | sì | 0 | 1 | 0/0 | 0 |
| 003 | T2 | mergiata | #5 | 129 | no | 0 | 3 | 3/3 | 2 |
| 004 | T2 | mergiata | #7 | 3 | no | 0 | 0 | 0/0 | 0 |
| 005 | T3 | mergiata | #8 | 21 | sì | 0 | 2 | 3/3 | 0 |
| 006 | T3 | mergiata | #9 | 18 | no | 0 | 1 | 3/3 | 0 |
| 007 | T3 | mergiata | #10 | 7 | sì | 0 | 2 | 0/0 | 0 |
| 008 | T4 | mergiata | #11 | 27 | no | 0 | 3 | 4/4 | 2 |
| 009 | T4 | aperta | #12 | 6 | — | 0 | 1 | 0/0 | 0 |

Merge fuori catena (PR di un giro entrate in main senza i controlli né la revisione umana): 1 (008).


## Cautele

- I minuti umani di Gerio sono stime del costruttore nei rapporti; quelli del laboratorio sono dichiarati (`⏱`).
- Il laboratorio parte sapendo come è andata Gerio: le risposte marcate `da Gerio: sì` vanno lette con sconto.
- Le ore di calendario dipendono dalla vita più che dal metodo.
