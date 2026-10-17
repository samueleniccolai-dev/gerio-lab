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

---

## Appendice dell'umano — 17/10/2026

Le conclusioni qui sopra le ha scritte la catena, nel giro 020. Il 16/10 una revisione indipendente
le ha verificate e il 17/10 ho ricostruito a mano il tempo umano. Dove questa appendice differisce
dalla tabella delle ipotesi, vale l'appendice.

### Il tempo umano, ricostruito

Metodo: ho elencato ogni evento con una traccia (commit, merge, PR, configurazioni di servizi
esterni, prove in produzione) e ho stimato i minuti a memoria, da uno a sei giorni dopo. Il registro è
`metrics/tempo-umano.csv` (24 righe); i ⏱ dichiarati sulle PR (40') restano in `metrics/giri.csv`.
Precisione: stime a memoria, con un margine che stimo del 50%.

| Traguardo | Laboratorio, minuti | Gerio, minuti stimati dal costruttore |
|---|---|---|
| T1 deploy automatico | 10 | 30 |
| T2 verifica visiva | 12 | 25 |
| T3 catalogo | 5 | 30 |
| T4 carrello | 7 | 35 |
| T5 Stripe | 117 | 25 (minimo: il ciclo 6 non ha stima) |
| T6 email e stato ordine | 139 | 40 |
| T7 zone di consegna | 0 | 20 |
| T8 pagine legali | 0 | — |
| **T1–T7** | **290** | **205** |

Fuori dai traguardi: setup 65, correzioni del meccanismo 35, lettura quotidiana di PROGRESS e
STATISTICHE 30, giro di chiusura 5. **Totale: circa 425 minuti, 7 ore.**

Dove sono andati: configurazione di Stripe e Brevo 75; infrastruttura e CI 45, più una sessione
Claude guidata da me fuori dalla catena 30; prove in produzione 40; merge a mano di 12 PR 65 (più
una di Dependabot 5); risposte alle domande dei giri 40; setup 65; correzioni 30; lettura 30.

### Le ipotesi, riviste

| # | Ipotesi | Esito scritto dalla catena | Esito rivisto |
|---|---|---|---|
| H1 | minuti umani ≤ 50% di Gerio | confermata (40 contro ~205) | **non dimostrata**: 290 contro 205 su T1–T7, ma i due numeri non sono confrontabili alla pari (sotto). Nella forma «≤ 50%» resta improbabile: Gerio avrebbe dovuto spendere almeno 580 minuti |
| H2 | ≤ 5 decisioni per traguardo | confermata | vera sulla media (2,5), falsa su T5 e T6 (6). Le ratifiche non le ho lette: «portate all'umano» non vuol dire «decise dall'umano» |
| H3 | qualità almeno pari | non misurabile | non misurabile e non cercata; il revisore ha girato su Sonnet nei giri 014–018 senza che me ne accorgessi |
| H4 | deriva < 10% | confermata | vera per costruzione: il perimetro era bloccato agli agenti |
| H5 | ore fino a T7 ≤ Gerio | smentita di poco | non interpretabile: 104 delle 123 ore di calendario sono attese mie (revisione del 16/10) |

Su H1 vanno dette tre cose insieme. Sui traguardi interni al repository (T1–T4) il laboratorio ha
usato 34 minuti contro 120: lì il risparmio c'è ed è netto. Sulle integrazioni con servizi esterni
(T5 e T6) ne ha usati 256 contro 65, l'88% del totale: account, segreti, DNS, prove in produzione, una
sessione guidata. Ma su T5 e T6 i due numeri non misurano la stessa cosa: in Gerio quelle attività le
ho fatte assistito dall'IA, e i minuti sono stime del costruttore che forse non le contavano; nel
laboratorio le ho fatte da solo, a memoria, e le ho contate tutte. Le due distorsioni vanno in
direzioni opposte (la memoria accorcia, la mancanza di assistenza allunga) e non so quale pesi di più.
Quello che i dati dicono davvero: il metodo toglie tempo umano dove il lavoro sta dentro il repository,
e lo sposta su integrazioni, merge dei percorsi protetti e verifiche; quanto costino le integrazioni
dipende da come l'umano le fa e da che strumenti ha, non dal metodo.

Minacce alla validità da aggiungere a quelle di ESPERIMENTO: assistenza dell'IA asimmetrica tra i due
metodi; contaminazione (sapevo già cosa fare); conteggi fatti con metodi diversi (stima del costruttore
contro ricostruzione a memoria).

Nota tecnica: `STATISTICHE.md` segna H1 «dati insufficienti» perché T8 non ha un valore in Gerio; il
confronto qui sopra è fatto sui soli T1–T7, dove entrambi i valori esistono.

### Cosa resta vero

Il risultato operativo regge: 8 traguardi su 8 in 20 giri e 827 minuti di sessione agentica; nessuna
CI rossa; 2 bloccanti presi dal revisore prima del merge (giri 013 e 016); nessun difetto noto dopo il
merge, ma nessuno l'ha cercato; 8 merge automatici su 20 PR di giro; 1 merge fuori catena (008).

### Cosa ne faccio

La decisione per Gerio è nell'ADR dedicato. Per il metodo, le correzioni che porto nel v4: un
controllo che manda in `revisione-umana` un giro con un modello diverso da quello atteso; un modulo
fisso per i minuti umani e le ratifiche lette a ogni merge; i token per giro; un tipo di giro di sola
verifica; le conclusioni di un esperimento scritte dall'umano, con la catena che produce solo numeri.
Le integrazioni esterne diventano lavoro di prima classe: la catena prepara il runbook, l'umano lo
esegue assistito dall'IA, i minuti si contano a parte. Nel prossimo esperimento H1 si misura in due
numeri distinti, con soglie scritte prima: minuti umani per traguardo sul lavoro interno, minuti umani
per integrazione sul lavoro esterno. Il template pubblico ricavato dal metodo è in pausa finché non c'è
un secondo caso misurato.

Samuele Niccolai, 17/10/2026
