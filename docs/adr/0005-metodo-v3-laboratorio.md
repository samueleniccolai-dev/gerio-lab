# ADR-0005 — Metodo v3: catena di agenti con merge e deploy automatici (laboratorio)

- **Data:** 2026-10-10
- **Stato:** accettata (per la durata dell'esperimento)
- **Collegamenti:** `docs/METODO.md` · `docs/ESPERIMENTO.md` · ADR 0002–0004 (tecnici, da Gerio)

## Contesto

Gerio, col metodo v2 (ciclo autonomo, verificatore separato, accettazione umana a ogni PR), è
passato da 2 PR di prodotto in 11 giorni a 14 cicli in 6 giorni. I suoi numeri mostrano dove si è
spostato il collo di bottiglia:

- **attenzione umana:** 140 decisioni da ratificare in 7 giorni, 138 approvate senza modifiche;
  rapporti di 670–1.780 parole contro la regola di una pagina;
- **direzione:** 4 cicli su 14 fuori dal piano del 4/10, 31% delle righe di produzione aggiunte;
- **protezioni scritte come istruzioni:** un verificatore «in sola lettura» che in pratica esegue
  comandi, regole sui comandi aggirabili, nessuna protezione di `main` lato server;
- **metriche mai misurate:** uso dell'abbonamento «n.d.» in 14 rapporti su 14.

## Decisione

Un repository separato e pubblico, ripartito dal codice di Gerio del 4/10, con il metodo v3:

1. cinque agenti con ruoli distinti (pianificatore, ricercatore, critico, costruttore TDD, revisore),
   orchestrati da `/giro` in una sessione cloud nuova per ogni giro;
2. test di accettazione scritti prima e intoccabili dal costruttore;
3. merge automatico se e solo se la CI è verde e i controlli deterministici di `consegna` passano;
   deploy e avvio del giro successivo nello stesso workflow;
4. decisioni a tre livelli, massimo 3 ratifiche per giro; l'umano si ferma solo sulle porte a senso
   unico;
5. regole rese vincoli: hook `PreToolUse`, percorsi protetti, controlli in CI dalla versione su
   `main`, protezione di `main` con le regole di GitHub (gratuite su un repository pubblico);
6. metriche calcolate da script, confrontate con una base estratta dai 14 cicli di Gerio.

## Alternative considerate

| Alternativa | Pro | Contro | Perché scartata |
|---|---|---|---|
| Migliorare il v2 dentro Gerio | Niente secondo repository | Nessun confronto pulito; rischio sul prodotto vero | Si vuole misurare, non solo cambiare |
| Molti agenti in parallelo (stile Cursor) | Più lavoro per ora | Coordinamento difficile; costo che l'abbonamento non regge | WIP = 1 resta giusto per un solo umano |
| Loop semplice senza revisione (stile Ralph) | Minimo sforzo | Nessuna barriera su sicurezza e isolamento | Il prodotto tocca soldi e dati di più negozi |
| Revisione AI dentro la CI con il token dell'abbonamento | Verdetto non falsificabile dal giro | Minuti di Actions e configurazione in più | Variante da provare dopo, se i dati la giustificano |

## Conseguenze

- Più facile: l'umano interviene solo su domande vere e su percorsi protetti; i numeri escono da soli.
- Più difficile: errori del meccanismo si ripetono a catena finché qualcuno non li nota: per questo
  limite giornaliero, interruttore, riparazioni contate e rapporti con «Per te, in 10 righe».
- Le sessioni usano l'identità GitHub del proprietario: le regole che reggono davvero sono quelle
  lato GitHub (ruleset, environment ristretti a `main`, `consegna` dalla versione su `main`); gli
  hook sono una cintura di sicurezza. Rischio residuo e allarme `fuori-catena`: README.
- Da fare: impostazioni del repository, environment, Routine e segreti (`docs/lab/SETUP.md`).

## Da riportare in SPEC.md §17.2?

No: riguarda il metodo, non il prodotto. L'esito dell'esperimento andrà in un ADR di Gerio.
