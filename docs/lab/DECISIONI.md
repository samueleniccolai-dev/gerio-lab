# DECISIONI — tre livelli (METODO §3)

- **registro:** dettagli interni reversibili; nessuna ratifica.
- **ratifica:** cosa vede o paga il cliente, dati salvati, costi ricorrenti, scelte care da cambiare.
  Massimo 3 per giro. Per contestarne una, commenta la PR del giro: `contesto n. X: …`.
- **senso unico:** il giro si è fermato con una PR `domanda`; qui va la risposta dell'umano.

Stato: `—` (in vigore) · `contestata` · `cambiata (giro NNN)` · `risposta del GG/MM`.

| N. | Data | Giro | Livello | Decisione | Alternativa scartata | Motivo (una riga) | Stato |
|---|---|---|---|---|---|---|---|
| 1 | 11/10 | 001 | registro | `infra/lib.sh` condiviso da deploy.sh e rollback.sh | codice duplicato | evita di copiare chiave SSH e known_hosts | — |
| 2 | 11/10 | 001 | registro | Immagini fissate: caddy:2.11.7, postgres:17.11; utente DB fisso `platform` | tag larghi, DB_USER variabile | meno stato da ripristinare | — |
| 3 | 11/10 | 001 | registro | Rollback ripristina solo se l'ultimo deploy era riuscito (`last-good`) | salvare sempre lo stato precedente | non salvare come «precedente» uno stato rotto | — |
| 4 | 11/10 | 001 | registro | Token GHCR solo su stdin di ssh verso `remote.sh`; `docker logout` nel trap | token in riga di comando | il token non compare in `ps` | — |
| 5 | 11/10 | 001 | ratifica | Dopo ogni deploy 503 per alcuni secondi (health check di Caddy, fino a ~30 s) | health_interval corto | accettabile per la demo; nel runbook | — |
| 6 | 11/10 | 002 | registro | `passato_il` di T1 = fine del passo «Deploy and smoke test» (15:25:09Z) | ora di esecuzione di traguardo.py | il criterio chiede lo smoke verde | — |
