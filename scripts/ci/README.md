# scripts/ci — punti di estensione della CI

`.github/workflows/ci.yml` è protetto: un giro non lo modifica. Lo estende così (anche questa
cartella è protetta: una PR che la tocca va in revisione umana, perché questi script girano prima
dei test e potrebbero saltarli):

| File | Quando lo esegue la CI | Esempio |
|---|---|---|
| `scripts/ci/prepare.sh` | prima di `./mvnw verify`, se è eseguibile | installare il browser per gli E2E |
| `scripts/ci/check.sh` | dopo `./mvnw verify`, se è eseguibile | controllare che CSS e JS committati siano aggiornati |
| `target/e2e-screenshots/` | caricata come artefatto `e2e-screenshots` | screenshot dei test nel browser |

Gli script girano senza segreti, su `ubuntu-latest`, con Java 25 e Docker. Una dipendenza nuova
che passi da qui (un binario, un'immagine) resta una porta a senso unico.
