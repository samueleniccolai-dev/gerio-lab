---
giro: 012
traguardo: T5
esito: pr-aperta
inizio: 2026-10-12T10:27:00Z
fine: 2026-10-12T10:57:31Z
pr: 15
modello_orchestratore: sonnet
modello_costruttore: sonnet
modello_revisore: opus
ricerca_fonti: 0
critico_da_correggere: 5
critico_accolti: 5
revisore_bloccanti: 0
revisore_dubbi: 3
dubbi_corretti: 0
fuori_perimetro: 0
mutazioni_provate: 3
mutazioni_prese: 3
verify_rossi_locali: 1
test_totali: 329
test_delta: 17
decisioni_registro: 2
decisioni_ratifica: 0
porte_senso_unico: 0
riparazioni: 0
difetto_di:
---
# Rapporto del giro 012 — Stripe test verso la produzione (T5, parte 3)

> **PR:** #15 · **Branch:** `claude/giro-012-t5` · **Traguardo:** T5 — parziale
> **Domande che bloccano:** nessuna

## Per te, in 10 righe
- Prima la prova in produzione non poteva partire nemmeno con le chiavi: compose e deploy non portavano le variabili Stripe sul server. Ora sì (opzionali, i segreti solo su stdin).
- Nuova `DEMO_STRIPE_ACCOUNT_ID` (validata `acct_…`): il seed la applica al negozio demo e accende `stripe_charges_enabled`.
- Le chiavi `sk_live_`/`rk_live_` sono rifiutate: il checkout resta spento.
- `smoke.sh` controlla il webhook: 400 sul dominio della piattaforma, 404 su quello del demo.
- **Revisione umana richiesta:** tocca `infra/` (percorso protetto) e un test di accettazione di un giro precedente (Giro006: curl finto con la rotta webhook, 3 righe, nessuna asserzione cambiata).
- **Da te per il giro 013** (runbook §4): account Stripe test e collegato per «Fioreria Il Glicine», endpoint webhook `https://<LAB_BASE_DOMAIN>/webhooks/stripe/connect` (non il dominio demo), segreti nell'environment `produzione`, tre variabili aggiunte a mano in `consegna.yml` (protetto).
- Nessuna ratifica nuova. Nessuna migrazione né dipendenza.

## Cosa funziona ora — con la prova
- Account demo applicato, idempotente al riavvio, conflitto `unique` registrato: test Giro012 (17 test, 14 prima rossi).
- Chiavi live rifiutate, log senza chiave: test Giro012.
- Variabili verso il server e formato stdin: `Giro012InfraAcceptanceTest`.

## Cosa non funziona ancora
- La prova vera con Stripe test in produzione (T5 «passato») manca: servono i tuoi passi.
- In locale 2 test visuali falliscono solo per Chromium assente; la CI li esegue. `shellcheck` non verificato qui.

## Verdetto del revisore
- **BLOCCANTI:** 0
- **DUBBI:** 3 (non corretti): ordine dei controlli in `deploy.sh`; `notLive` non toglie gli spazi; shellcheck non disponibile.
- **PERIMETRO:** nessuna aggiunta; correzione dichiarata di Giro006.
- **CONCORRENZA:** 8 riavvii simulati in parallelo, 0 eccezioni.
- **MUTAZIONI:** provate 3, prese 3

## Ricerca e critica
Nessuna ricerca (binari). Critica: 5 punti, tutti accolti (rotta webhook solo sul dominio piattaforma, flag charges, formato stdin, validazione account, conflitto unique).

## Prossima fetta
Giro 013: prova in produzione con Stripe test, dopo i tuoi passi.
