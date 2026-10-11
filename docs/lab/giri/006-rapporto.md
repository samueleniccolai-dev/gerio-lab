---
giro: 006
traguardo: T3
esito: pr-aperta
inizio: 2026-10-11T18:43:38Z
fine: 2026-10-11T19:01:08Z
pr: 9
modello_orchestratore: sonnet
modello_costruttore: sonnet
modello_revisore: opus
ricerca_fonti: 0
critico_da_correggere: 2
critico_accolti: 2
revisore_bloccanti: 0
revisore_dubbi: 1
dubbi_corretti: 0
fuori_perimetro: 0
mutazioni_provate: 3
mutazioni_prese: 3
verify_rossi_locali: 2
test_totali: 235
test_delta: 9
decisioni_registro: 1
decisioni_ratifica: 0
porte_senso_unico: 0
riparazioni: 0
difetto_di:
---
# Rapporto del giro 006 — negozio demo su `demo.<LAB_BASE_DOMAIN>`

> **PR:** #9 · **Branch:** `claude/giro-006-t3` · **Traguardo:** T3 — parziale (passa dopo deploy e prova in produzione)
> **Domande che bloccano:** nessuna

## Per te, in 10 righe
- Caddy serve ora anche `demo.<dominio>` (un blocco, due nomi): certificato per entrambi, stesse protezioni.
- Lo smoke post-deploy controlla dall'esterno catalogo e scheda «rose rosse» del demo, HSTS, 308 e 404 sui percorsi di gestione.
- Nessuna ratifica. La PR tocca `infra/`: va in revisione umana (normale).
- Scostamento da SPEC App. B.3: niente on-demand TLS (l'endpoint non esiste ancora). Registrato.
- T3 passerà quando `consegna` sarà verde dopo il merge e i testi saranno in produzione: lo segna un giro di soli documenti.

## Cosa funziona ora — con la prova
- Test di accettazione `Giro006DemoHostAcceptanceTest` 9/9 e `Giro001` 19/19 verdi; `caddy adapt` su `caddy:2.11.7` ok.

## Cosa non funziona ancora
- Il certificato e il demo online non sono provati: dipende dal deploy dopo il merge.
- `verify` locale rosso su 2 test Playwright (Chromium 1243 assente nell'ambiente); da confermare in CI.

## Verdetto del revisore
- **BLOCCANTI:** nessuno. **DUBBI:** 1 (verify in CI). **PERIMETRO:** pulito.
- **CONCORRENZA:** controlli demo nello stesso ciclo di tentativi (provato).
- **MUTAZIONI:** provate 3, prese 3

## Ricerca e critica
Nessuna ricerca. Critica: 2 punti, accolti; semplificato in un solo blocco Caddy.

## Prossima fetta
Verifica di T3 in produzione dopo il merge, poi T4.
