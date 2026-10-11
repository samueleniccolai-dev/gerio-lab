---
giro: 001
traguardo: T1
esito: pr-aperta
inizio: 2026-10-11T09:43:32Z
fine: 2026-10-11T10:10:00Z
pr: 2
modello_orchestratore:
modello_costruttore: sonnet
modello_revisore: opus
ricerca_fonti: 0
critico_da_correggere: 5
critico_accolti: 5
revisore_bloccanti: 0
revisore_dubbi: 4
dubbi_corretti: 0
fuori_perimetro: 0
mutazioni_provate: 3
mutazioni_prese: 2
verify_rossi_locali: 0
test_totali: 209
test_delta: 19
decisioni_registro: 4
decisioni_ratifica: 1
porte_senso_unico: 0
riparazioni: 0
difetto_di:
---
# Rapporto del giro 001 — deploy automatico (compose, Caddy, script)

> **PR:** #2 · **Branch:** `claude/giro-001-t1` · **Traguardo:** T1 — parziale
> **Domande che bloccano:** nessuna

## Per te, in 10 righe
- Il giro scrive tutta l'infrastruttura di deploy in `infra/`: compose, Caddyfile, `deploy.sh`, `smoke.sh`, `rollback.sh`, `remote.sh`, `lib.sh`, `env.example`. Nessuna riga Java.
- La PR tocca `infra/`, quindi va in revisione umana.
- Il deploy reale non è provato: servono server, DNS e segreti (`docs/lab/SETUP.md` §5). Senza, `consegna` scrive `esito=saltato`, come nel payload di avvio.
- Dopo il merge: controlla che `consegna` dia `esito=ok` e che il dominio risponda 200 con la landing.
- Ratifica: dopo ogni deploy il sito può dare 503 per alcuni secondi (fino a ~30 s).
- T1 resta `in-corso` fino al primo deploy riuscito.
- Il nome del dominio finisce nei log di Certificate Transparency (irreversibile; il nome è già scelto).

## Cosa funziona ora — con la prova
- 19 test di accettazione verdi, `./mvnw verify` verde con 209 test (revisore, sha 1e6a9a0).
- Stack locale con l'immagine reale: app, db e caddy healthy; `/` 200; `/q` e `/internal` 404 anche con varianti codificate.

## Cosa non funziona ancora
- Deploy sul server, `caddy reload` su server vero e `remote.sh` completo non sono provati. Sono «non verificato» in `docs/verifiche.md`.
- Lo `flock` non è coperto da un test.

## Verdetto del revisore
- **BLOCCANTI:** nessuno.
- **DUBBI:** 4, nessuno corretto. 1) `mkdir`/`scp` in `incoming/` fuori dal `flock`; 2) `flock` non coperto da test (mutazione non presa); 3) 503 di Caddy fino a ~30 s; 4) il rollback non ricrea `last-good`.
- **PERIMETRO:** nessuna aggiunta non richiesta.
- **CONCORRENZA:** `flock` con tre istanze in parallelo, serializzate; deploy ripetuto idempotente.
- **MUTAZIONI:** provate 3, prese 2.

## Ricerca e critica
Ricerca non fatta (infrastruttura). Critico: 5 punti da correggere, tutti accolti (reload di Caddy, rollback della configurazione, domini nel `.env`, canale del token, controllo delle immagini).

## Prossima fetta
T1 dopo i prerequisiti umani; altrimenti T2 (richiede una dipendenza nuova: porta a senso unico).
