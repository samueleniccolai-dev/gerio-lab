# Conclusioni — laboratorio metodo v3 contro Gerio (metodo v2)

Dati: `docs/lab/STATISTICHE.md` (16/10/2026). Cautele: il laboratorio parte sapendo come è andata
Gerio; i minuti umani di Gerio sono stime; le ore di calendario dipendono anche dalla vita.

## Perché finisce
Tutti gli 8 traguardi (T1–T8) sono `passato` (giro 019, PR #24). Non serve la scadenza dei 21 giorni.
20 giri in circa 5 giorni di calendario dal T0 (11/10 → 16/10).

## Le ipotesi

| # | Ipotesi | Numeri | Esito |
|---|---|---|---|
| H1 | Meno tempo umano | Laboratorio 40 min su T1–T8 (100 con i 60 di setup). Gerio: ~205 min stimati su T1–T7 (6 cicli senza stima: è un minimo). | **Confermata** (anche col setup, ma con margine stretto) |
| H2 | ≤ 5 decisioni all'umano per traguardo | Media 2,5 (max 6 in T5 e T6). Gerio: 6–25 per traguardo. | **Confermata** (T5 e T6 oltre soglia, da leggere come eccezioni) |
| H3 | Qualità almeno pari | 0 giri di correzione dopo il merge; 2 bloccanti trovati dal revisore prima del merge (giri 013, 016); 1 merge fuori catena (giro 008). Gerio non misurava i difetti. | **Non misurabile** |
| H4 | Meno deriva | 0,0% di righe in `src/main` fuori dai traguardi; Gerio 31%. | **Confermata** (la catena segue il traguardo per costruzione) |
| H5 | Velocità non peggiore | 120,4 ore fino a T7 contro 114,5 di Gerio. | **Smentita**, di poco (+5%) |

## Cosa ha funzionato
- Lucchetto su branch, WIP = 1 e fasi pubblicate: nessun conflitto tra sessioni.
- Test di accettazione scritti prima e committati da soli: nessuna modifica successiva non dichiarata.
- Revisore indipendente con mutazioni: ha preso i bloccanti reali (013, 016).
- Porte a senso unico poche e chiare: le domande all'umano sono state poche.
- Merge e deploy automatici hanno tolto quasi tutto il lavoro umano di consegna.

## Cosa no
- Tempo di calendario: T5 (4 giri) e T6 (4 giri) sono costati più di tutto il resto; il giro 016 è durato 236 minuti.
- Un giro 019 «vuoto» (nessun codice, solo verifica in produzione): il metodo non distingue il lavoro di verifica.
- Le metriche dipendono da `gh`/API: in questa sessione non rispondeva e alcuni campi sono rimasti vuoti.
- Resta un debito: ratifiche non lette, testi legali senza consulente, job di anonimizzazione (scadenza 10/2027).

## Cosa cambierei
1. Chiedere i minuti umani con un modulo fisso, non nei commenti.
2. Una fetta per T5/T6 più piccola fin dal piano (Stripe e email vanno spezzati prima).
3. Un tipo di giro «verifica in produzione» con il suo rapporto corto.
4. Misurare i difetti post-merge anche in Gerio, se si vuole davvero H3.
