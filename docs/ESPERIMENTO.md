# Esperimento — metodo v3 (laboratorio) contro metodo v2 (Gerio)

## Domanda

Una catena di agenti specializzati, con test di accettazione scritti prima, merge e deploy
automatici e decisioni a tre livelli, raggiunge gli stessi traguardi di Gerio **con molto meno
tempo umano**, senza peggiorare qualità, sicurezza e perimetro?

## Ipotesi

| # | Ipotesi | Si conferma se |
|---|---|---|
| H1 | Meno tempo umano | minuti umani per traguardo ≤ 50% di quelli stimati in Gerio |
| H2 | Meno decisioni portate all'umano | ≤ 5 per traguardo (ratifiche + porte a senso unico); Gerio: 140 decisioni in 14 cicli |
| H3 | Qualità almeno pari | difetti emersi dopo il merge ≤ Gerio; nessun bloccante di sicurezza trovato dopo il merge |
| H4 | Meno deriva | righe aggiunte in `src/main` fuori dai traguardi < 10% (Gerio: 31%, cicli 11–14) |
| H5 | Velocità non peggiore (secondaria) | ore di calendario fino a T7 ≤ Gerio |

## Disegno

- **Stesso punto di partenza:** codice di Gerio al commit `1c07722` (4/10/2026 22:45, `main` subito
  prima del merge del ciclo 1), senza storia, senza documenti personali o di business.
- **Stessi obiettivi:** il piano di Gerio del 4/10 (STATO di allora), riscritto come traguardi
  T1–T8 in `docs/lab/traguardi.json`, con lo stesso «fatto quando».
- **Stesse regole di prodotto:** stack, convenzioni, test di isolamento, porte a senso unico per
  le dipendenze nuove.
- **Stessi modelli dove si può:** costruttore Sonnet e revisore Opus, come `opusplan` più il
  revisore di Gerio. Ogni rapporto registra i modelli effettivi.
- **Cambia solo il metodo:** agenti separati, ricerca sui concorrenti, critica prima del codice,
  test di accettazione congelati, merge e deploy automatici, giri a catena, decisioni a tre livelli.

## Traguardi e base di confronto

T0 di Gerio: 2026-10-04 14:06 UTC (fine della preparazione del metodo v2, inizio del ciclo 1).
T0 del laboratorio: inizio del giro 001 (`inizio` nel suo piano e nel suo rapporto).

| Traguardo | Cicli di Gerio | PR di Gerio | Chiuso in Gerio |
|---|---|---|---|
| T1 Deploy automatico | 1 | #9 | 2026-10-04 20:45 UTC |
| T2 Verifica visiva (E2E + screenshot) | 2 | #13 | 2026-10-05 09:30 UTC |
| T3 Catalogo + pagina prodotto | 3 | #14 | 2026-10-05 14:46 UTC |
| T4 Carrello | 4 | #15 | 2026-10-05 22:22 UTC |
| T5 Checkout Stripe test + webhook | 6, 7 | #17, #19 | 2026-10-07 15:20 UTC |
| T6 Email di conferma + stato ordine | 8, 9 | #20, #23 | 2026-10-08 20:24 UTC |
| T7 Zone di consegna e orario limite | 10 | #24 | 2026-10-09 08:39 UTC |
| T8 Pagine legali + rifiniture | — | — | non ancora chiuso |

Cicli di Gerio fuori dal piano del 4/10: 5 (Tailwind e htmx, cioè stack: contato a parte),
11, 12, 13 e 14 (import CSV dei dati geografici, zone per comune: deriva). Dati per ciclo in
`metrics/baseline-gerio.csv`, estratti da rapporti, DECISIONI, PR e run di Actions di Gerio.

## Metriche

| Metrica | Laboratorio | Gerio |
|---|---|---|
| Minuti umani | commenti `⏱` dell'umano sulle PR + `metrics/tempo-umano.csv` | stima del costruttore nei rapporti (§5) |
| Interventi umani | risposte, merge manuali, revisioni (eventi delle PR) | un merge per ciclo + risposte nei rapporti |
| Decisioni portate all'umano | ratifiche + porte a senso unico (`DECISIONI.md`) | righe di DECISIONI per ciclo (tutte da ratificare) |
| Giri o cicli per traguardo | PR di prodotto mergiate | PR di prodotto mergiate |
| Ore di calendario | da T0 al merge che chiude il traguardo | idem |
| Rilavorazioni | CI rosse + bloccanti del revisore | rapporti §5 |
| Difetti emersi dopo il merge | giri che correggono un difetto sfuggito a un giro già mergiato (`difetto_di` nel rapporto) | non misurato: si valuta alla fine, a mano |
| Minuti di CI | durata dei run (API Actions) | idem |
| Righe di produzione | `src/main` aggiunte/tolte per PR | idem |
| Deriva | righe di produzione in PR non legate a un traguardo | cicli fuori piano |
| Uso dell'abbonamento | `metrics/abbonamento.csv`, lettura settimanale | non misurato (n.d. in 14 rapporti su 14) |
| Qualità dei test | mutazioni provate e prese dal revisore | mutazioni citate nei rapporti |
| Merge fuori catena | PR di un giro entrate in `main` senza controlli né revisione umana (`fuori-catena`) | — |

## Minacce alla validità (da dichiarare nei risultati)

- **Contaminazione:** l'umano conosce già le risposte di Gerio. Quando rispondi a una domanda,
  aggiungi `da Gerio: sì` se la risposta viene da lì. Gli agenti del laboratorio non hanno accesso
  a Gerio.
- **Vantaggio di chi arriva secondo:** il laboratorio parte dopo che Gerio ha già affrontato gli
  stessi problemi: il tempo umano risparmiato va letto con questo sconto.
- **Tempo umano di Gerio stimato, non misurato:** il confronto su H1 ha un margine ampio. Il
  ciclo 6 (T5) non ha stima: i minuti umani di T5 in Gerio sono un minimo.
- **Modelli:** possono cambiare durante l'esperimento; si registra quello effettivo.
- **Calendario:** dipende dalla vita più che dal metodo. Metrica secondaria.
- **Costo di preparazione:** il meccanismo del laboratorio (questo commit) è costruito a parte,
  come la preparazione del metodo v2 in Gerio (ADR-0005, STATO, skill). Si confronta a parte.

## Ipotesi di funzionamento da verificare al primo giro

Il meccanismo assume cose che solo un giro vero può confermare. Se una non vale, è una correzione
del meccanismo (tabella in fondo), non un risultato dell'esperimento.

1. Le sessioni cloud agiscono su GitHub come `LAB_AUTORE`: autore delle PR e dei commenti.
2. Il trigger API della Routine (`/fire`, header `anthropic-beta: experimental-cc-routine-2026-04-01`,
   corpo `{"text": …}`) avvia una sessione nuova e le passa il testo.
3. Gli `hooks:` nel frontmatter degli agenti (costruttore, revisore, critico) sono applicati.
4. La sessione può pushare i branch `claude/…` e gestire PR ed etichette via REST
   (`.claude/skills/giro/pr.py`): GraphQL dalle sessioni cloud non risponde (verificato il 10/10).
5. Il job `immagine` pubblica su GHCR e il job `deploy` legge l'immagine con il token del run.
6. Claude Code nella Routine è ≥ 2.1.295: gli hook hanno `"onFailure": "block"`. Con una versione
   più vecchia un guard che non parte non blocca (resta il controllo dopo il comando).

## Quando finisce

Al passaggio di T8, oppure 21 giorni dopo il T0 del laboratorio, oppure con l'interruttore spento.
Il giro di chiusura (skill `/giro` §13) scrive `docs/lab/CONCLUSIONI.md` e `docs/lab/STOP`. Poi:
un ADR con la decisione per Gerio: adottare il v3, prenderne alcune parti, o scartarlo.

## Correzioni del meccanismo durante l'esperimento

Solo bug del meccanismo, non migliorie. Ogni correzione è una PR umana e una riga qui.

| Data | Cosa | Perché |
|---|---|---|
| 2026-10-12 | Versione di Playwright in una proprietà del pom, letta dall'hook di avvio delle sessioni cloud | Senza, l'hook saltava Chromium: 2 test Playwright rossi nel verify locale di ogni giro dal 006 |
