# ADR-0002 — Scheletro R0: generazione, versione Quarkus, formattazione

- **Data:** 2026-09-27
- **Stato:** accettata
- **Collegamenti:** PROGETTO.md §3 (R0), §4.2, §4.3, §6, §11.5, §14, Appendice C · ADR-0000 (modello) ·
  `docs/verifiche.md` (27/09/2026) · `docs/scostamenti-4-10.md` (righe 5-7)

## Contesto
Primo compito di codice della milestone R0-scheletro: generare il progetto Quarkus, la prima
migrazione Flyway e decidere se introdurre già la formattazione automatica (decisione aperta n. 6
di `scostamenti-4-10.md`). Maven e la CLI di Quarkus non erano installati sul computer
di sviluppo.

## Decisione
- **Generazione:** zip ufficiale scaricato da `code.quarkus.io` (stream `3.33`, LTS), non scritto a
  mano né tramite CLI installata localmente. Estensioni: `quarkus-rest`, `quarkus-rest-qute`,
  `quarkus-hibernate-orm-panache`, `quarkus-jdbc-postgresql`, `quarkus-flyway`,
  `quarkus-smallrye-health`.
- **Versione:** `io.quarkus.platform:quarkus-bom` 3.33.3.3. Esistono due BOM Quarkus con nome
  simile e serie di versioni diverse: `io.quarkus:quarkus-bom` (arrivato a 3.33.4 il 27/9) e
  `io.quarkus.platform:quarkus-bom` (quello usato dal generatore ufficiale e da questo progetto,
  fermo a 3.33.3.3). Il primo controllo di verifica aveva guardato il BOM sbagliato; l'errore è
  emerso subito perché `./mvnw verify` non riusciva a scaricare la versione (`docs/verifiche.md`).
- **Package base:** `it.gerio.platform`, come richiesto da PROGETTO.md §4.3 (`it.<nome>.platform`).
  Il generatore non permette di impostarlo con un parametro diretto: i file d'esempio (package
  `it.gerio`) sono stati scartati e le classi vere sono scritte direttamente nel package giusto.
- **V1 minima:** solo `tenant` e `tenant_domain` (§6), non l'intero schema dell'Appendice C. Le
  altre tabelle arrivano con i loro moduli in R1, così Flyway non porta a `main` colonne senza
  codice né test che le usino.
- **Formattazione:** Spotless 3.10.3 con `google-java-format` 1.36.1, stile `GOOGLE`, legato al
  goal `check` in fase `verify`. Nessuna configurazione JVM aggiuntiva richiesta (versioni recenti
  del plugin la gestiscono da sole). L'hook `.claude/hooks/format.sh` (già presente dal setup
  iniziale) ora si attiva perché trova `spotless-maven-plugin` in `pom.xml`.
- **Docker:** solo `Dockerfile.jvm` (generato, poi da rivedere al passo 5 della milestone); scartati
  `Dockerfile.native`, `Dockerfile.native-micro` e `Dockerfile.legacy-jar`, non usati da questo
  progetto (niente native image, niente uber-jar).

## Alternative considerate
| Alternativa | Pro | Contro | Perché scartata |
|---|---|---|---|
| CLI di Quarkus installata localmente | Aggiornabile, genera anche via `quarkus create` | Nuovo strumento da installare e mantenere sul computer | Il generatore web basta per un progetto solo |
| Scrivere `pom.xml` e `mvnw` a mano | Nessuna dipendenza dal sito | Alto rischio di errori nelle versioni dei plugin Maven interni (surefire, compiler); il wrapper va comunque scaricato | Più lento, stesso risultato |
| Migrazione V1 completa (tutto §6) | Coerente con l'Appendice C | Colonne senza codice né test per settimane, rischio di doverle correggere dopo | V1 minima, coerente con "passi piccoli con i loro test" |
| Analisi statica (Error Prone/SpotBugs) insieme a Spotless | Un solo giro di decisione | Nuova dipendenza in più da valutare a parte | Decisione rimandata (voce 6 di `scostamenti-4-10.md` resta parzialmente aperta) |

## Conseguenze
- `V1__init.sql` è ora "congelata": dopo il merge su `main` ogni correzione richiede una V2
  (CLAUDE.md, §11.5). Per questo la revisione (`@agent-revisore`) e i controlli dello sviluppatore
  sono avvenuti *prima* del merge, non dopo.
- Il prossimo controllo di versione Quarkus dovrà guardare `io.quarkus.platform:quarkus-bom`, non
  `io.quarkus:quarkus-bom`.
- `./mvnw spotless:apply` va lanciato (o lo fa l'hook automatico) prima di ogni commit con file
  Java modificati.

## Da riportare in PROGETTO.md §17.2?
Sì: «Scheletro Quarkus 3.33 LTS generato da code.quarkus.io, V1 minima (solo tenant/tenant_domain),
Spotless con google-java-format».
