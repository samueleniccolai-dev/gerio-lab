# Metodo v3 — la catena dei giri

Regola che comanda: **il prodotto avanza da solo; l'umano decide solo le porte a senso unico e
legge i numeri.** Perché e come si valuta: `docs/ESPERIMENTO.md`, ADR-0005.

## 1. Un giro: cinque agenti in una sessione

Ogni giro è una **sessione cloud nuova** (una Routine di Claude Code) che esegue la skill `/giro`.
La sessione principale fa solo da orchestratore: passa a ogni agente solo ciò che gli serve e
raccoglie risposte brevi. Ogni agente lavora in un contesto pulito.

| Fase | Agente (modello) | Riceve | Produce |
|---|---|---|---|
| 1. Piano | `pianificatore` (opus) | PROGRESS, traguardi, sezioni della SPEC | piano della fetta, test di accettazione |
| 2. Concorrenti | `ricercatore` (sonnet) | fetta e domande del piano | 1 pagina con fonti: concorrenti e casi reali |
| 3. Critica | `critico` (opus) | piano + ricerca + codice | da correggere, semplificazioni, concorrenza, perimetro |
| 4. Costruzione | `costruttore-tdd` (sonnet) | piano finale + test di accettazione | codice in TDD, commit piccoli |
| 5. Revisione | `revisore` (opus) | piano + SHA, **mai** il ragionamento del costruttore | verdetto con prove: esegue, non scrive |

## 2. La catena

`/giro` → PR → `ci` → `consegna` (sempre la versione su `main`): controlli → merge → deploy →
smoke → avvio del giro successivo. Il giro successivo è una sessione nuova: è il «clear».

- **WIP = 1:** una sola PR `giro` aperta. Il giro apre subito una PR bozza `in-corso` come
  lucchetto e pusha a ogni fase; un lucchetto fermo da più di 6 ore si riprende dalla sua fase.
  La storia pubblicata non si riscrive: niente push forzati, li bloccano anche le regole di GitHub.
- **Si ferma** solo se: porta a senso unico (PR `domanda`), controllo negativo (`revisione-umana`),
  due riparazioni CI fallite (`bloccato`), limite di giri al giorno, interruttore spento
  (variabile `LAB_ATTIVO` diversa da `true`, o file `docs/lab/STOP`).
- **Riparte** da sola: dopo il deploy, dopo una CI rossa (riparazione), quando metti `risposto`,
  ogni notte se la catena si è interrotta.

## 3. Decisioni: tre livelli

| Livello | Esempi | Cosa succede |
|---|---|---|
| Registro | nomi, testi, layout, dettagli interni | riga in `DECISIONI.md`, nessuna ratifica |
| Ratifica | cosa vede o paga il cliente, dati salvati, costi ricorrenti, scelte care da cambiare | **massimo 3 per giro**, in testa al rapporto; si contestano commentando la PR con `contesto n. X: …` |
| Senso unico | CLAUDE.md regola 2 | il giro si ferma con una PR `domanda`; risposta + etichetta `risposto` → riparte |

## 4. Merge automatico: quando sì

`consegna` mergia solo se tutto è vero:
- CI verde sullo stesso SHA; PR non bozza, dal branch `claude/giro-…`, con etichetta `giro`;
- nel corpo il verdetto del revisore con 0 bloccanti;
- nessun percorso protetto: `.github/`, `.claude/`, `.mvn/`, `scripts/ci/`, `scripts/guard/`,
  `scripts/metrics/`, `scripts/lab/`, `infra/`, `src/main/docker/`, `CLAUDE.md`, `pom.xml`, `mvnw*`,
  `.dockerignore`, `.gitignore`, `.gitattributes`, `.gitmodules`, `docs/SPEC.md`, `docs/METODO.md`,
  `docs/ESPERIMENTO.md`, `docs/lab/SETUP.md`, `docs/lab/giri/_modello-*.md`,
  `metrics/baseline-gerio.csv`; nessun collegamento simbolico;
- migrazioni solo aggiunte, in sequenza, senza istruzioni distruttive;
- test di accettazione già su `main` intatti; quelli nuovi toccati solo da commit `test(acceptance)`;
- `traguardi.json` cambiato solo nei campi di stato;
- al massimo 1.500 righe aggiunte in `src/main`; nessun segreto nel diff.

Altrimenti etichetta `revisione-umana` con i motivi. L'agente non può cambiare le proprie regole e
approvarsele: le regole valide sono sempre quelle su `main`. Le regole della CLAUDE.md regola 3 un
giro non le tocca proprio (hook). Un merge a mano di una PR di un giro senza `revisione-umana` o
`bloccato` viene segnato `fuori-catena`: è il segnale che qualcuno ha saltato la catena.

## 5. Limiti contro la deriva

- Una fetta = un traguardo o una sua parte. Ciò che non serve al traguardo va in «idee per dopo».
- Revisore: bloccante solo per correttezza, requisiti, sicurezza, isolamento, perdita di dati.
  Dei dubbi se ne correggono al massimo 2 per giro; gli altri restano nel rapporto.
- Critico: al massimo 5 punti da correggere. Ricercatore: 1 pagina, al massimo 3 idee per dopo.
- 3 tentativi falliti sullo stesso errore → parcheggio con BLOCCO in PROGRESS.
- Riparazioni della CI: al massimo 2 per PR, poi `bloccato`.

## 6. Cosa fa l'umano

- Risponde alle PR `domanda`, mette `risposto` e scrive quanto tempo ha impiegato: `⏱ 5'`.
- Rivede le PR `revisione-umana`, le mergia o le chiude, e annota `⏱`.
- Ogni giorno, 5': PROGRESS e STATISTICHE; se serve contesta una ratifica.
- Ogni settimana: percentuale d'uso dell'abbonamento in `metrics/abbonamento.csv`.
- Tutto il resto del tempo: niente.

## 7. Metriche

Ogni giro aggiunge il suo rapporto con le metriche in testa; `scripts/metrics/` rigenera
`metrics/giri.csv` e `docs/lab/STATISTICHE.md` col confronto su Gerio. Definizioni in ESPERIMENTO.

## 8. Vincoli

1. Questo file resta ≤ 2 pagine. Le regole cambiano solo con una PR umana.
2. Durante l'esperimento il metodo non cambia, salvo correzioni di bug del meccanismo, registrate in
   `docs/ESPERIMENTO.md`.
