---
giro: 002
traguardo: T1
esito: pr-aperta
inizio: 2026-10-11T15:25:50Z
fine: 2026-10-11T15:33:00Z
pr: 3
modello_orchestratore:
modello_costruttore: sonnet
modello_revisore: opus
ricerca_fonti: 0
critico_da_correggere: 3
critico_accolti: 3
revisore_bloccanti: 0
revisore_dubbi: 1
dubbi_corretti: 0
fuori_perimetro: 0
mutazioni_provate: 0
mutazioni_prese: 0
verify_rossi_locali: 0
test_totali: 209
test_delta: 0
decisioni_registro: 1
decisioni_ratifica: 0
porte_senso_unico: 0
riparazioni: 0
difetto_di:
---
# Rapporto del giro 002 — chiusura di T1 (prima prova reale del deploy)

> **PR:** #3 · **Branch:** `claude/giro-002-t1` · **Traguardo:** T1 — passato
> **Domande che bloccano:** nessuna

## Per te, in 10 righe
- Il deploy del giro 001 è andato davvero in produzione: run `consegna` 38151028698, smoke ok al tentativo 5 (4 risposte 503 a vuoto, ~20 s).
- Il giro è solo documentale: runbook, verifiche e stato di T1 (`passato`, 15:25:09Z).
- `--remove-orphans` e `caddy reload` risultano «accettati senza errore; effetto non osservato», non confermati.
- Da te: l'avviso «`ubuntu-latest` passa a Ubuntu 26 dal 19/10» compare in tutti i job; riguarda `.github/` (protetto), valuta tu.
- Nessuna ratifica nuova. Prossimo: T2 (dipendenza nuova → porta a senso unico).

## Cosa funziona ora — con la prova
- Landing 200, http→308, `/q/health/ready` 404 (curl, rifatto dal revisore). Log del job 114504528632: `smoke ok (attempt 5)`.
- `./mvnw -B -ntp verify`: 209 test, 0 falliti.

## Cosa non funziona ancora
- Rollback non provato sul server reale. 503 dopo il deploy (ratifica n. 5).
- Idee per dopo: mkdir/scp di `incoming/` sotto flock; `health_interval` più corto; rollback che ricrea `last-good`.

## Verdetto del revisore
- **BLOCCANTI:** nessuno · **DUBBI:** 1 (rapporto e PROGRESS arrivano in chiusura: fatto) · **PERIMETRO:** ok · **CONCORRENZA:** n/a · **MUTAZIONI:** provate 0, prese 0

## Ricerca e critica
Nessuna ricerca. Critica: 3 punti, tutti accolti (valore di `pr`, definizione di `passato_il`, voci di verifiche separate).

## Prossima fetta
T2 — E2E con browser e screenshot (richiesta formale per la dipendenza).
