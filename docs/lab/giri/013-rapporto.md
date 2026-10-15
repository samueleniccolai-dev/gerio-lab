---
giro: 013
traguardo: T5
esito: pr-aperta
inizio: 2026-10-15T09:10:56Z
fine: 2026-10-15T11:07:00Z
pr: 17
modello_orchestratore: sonnet
modello_costruttore: sonnet
modello_revisore: opus
ricerca_fonti: 0
critico_da_correggere: 3
critico_accolti: 3
revisore_bloccanti: 1
revisore_dubbi: 1
dubbi_corretti: 2
fuori_perimetro: 0
mutazioni_provate: 1
mutazioni_prese: 1
verify_rossi_locali: 1
test_totali: 355
test_delta: 26
decisioni_registro: 3
decisioni_ratifica: 0
porte_senso_unico: 1
riparazioni: 0
difetto_di:
---
# Rapporto del giro 013 — strumenti per la prova Stripe in produzione (T5, parte 4)

> **PR:** #17 · **Branch:** `claude/giro-013-t5` · **Traguardo:** T5 — passato
> **Domande che bloccano:** nessuna

## Per te, in 10 righe
- Il giro era fermo sulla domanda dei segreti Stripe: hai fatto i passi del runbook §4 (il webhook ora risponde 400, non 503) → opzione A, decisione n. 31.
- `infra/smoke.sh`: se `STRIPE_CONNECT_WEBHOOK_SECRET` c'è, il webhook deve dare 400 (503/404/500 = smoke rosso, il segreto non compare mai).
- Nuovo `infra/prova-stripe.sh <dominio-demo>`: riempie il carrello dei dati inventati, apre il checkout e stampa l'URL di Stripe Checkout; `esito <url>` aspetta fino a 30 s «Pagamento ricevuto» e stampa il numero d'ordine.
- Il revisore ha trovato un difetto vero: l'app scrive i prezzi con spazio indivisibile, lo script avrebbe fallito in produzione. Corretto.
- Runbook §4 completo (4242, link Checkout pubblico in PR e valido 24 h, tabella 400/503/404, rinvio evento).
- Tocca `infra/` (percorso protetto): **PR in revisione umana**.
- **Da te:** lancia `infra/prova-stripe.sh demo.gerio-lab.samueleniccolai.com`, paga con la 4242, poi `infra/prova-stripe.sh esito <url>` (o dimmelo e lo verifico al giro 014). Il guard mi impedisce di lanciare lo script da qui.

## Cosa funziona ora — con la prova
- Smoke e script: 22 test di accettazione `Giro013*` verdi; 355 test totali (prova: revisore, `verify` in copia isolata).
- Webhook di produzione: `curl -X POST https://gerio-lab.samueleniccolai.com/webhooks/stripe/connect` → 400 (15/10).

## Prova vera (15/10, a mano dall'umano)
- `prova-stripe.sh` → URL di Stripe Checkout; pagamento con 4242; `esito` → ordine 2026-0002, «Pagamento ricevuto», totale 104,80 €. T5 passato. Trovato e corretto un difetto: `-o /dev/null` falliva con il curl di Windows (2b0f312).

## Cosa non funziona ancora
- Non verificato a mano il 200 dell'evento nella dashboard Stripe (l'ordine PAID lo implica).
- Il curl finto dei test usa lo spazio normale nei prezzi; il caso NBSP è stato provato solo con sonde del revisore, non committate.
- Localmente 2 test di Giro006 (Caddy) rossi per limite Docker Hub 429; in copia isolata il `verify` è verde.

## Verdetto del revisore
- **BLOCCANTI:** 1 (NBSP nel totale), risolto in 18fda95 e riverificato.
- **DUBBI:** 3; corretti 2 (esito senza numero ordine, codice HTTP di aggiungi-al-carrello); il limite Docker è ambientale.
- **PERIMETRO:** nessuna aggiunta.
- **CONCORRENZA:** 8 lanci paralleli, cookie jar distinti e cancellati.
- **MUTAZIONI:** provate 4 (3+1), prese 4.
- VERDETTO finale: {"sha": "18fda955cd3317d1a19adcc7a9618ed8292fcafb", "bloccanti": 0, "dubbi": 1, "fuori_perimetro": 0, "mutazioni_provate": 1, "mutazioni_prese": 1, "test_totali": 355}

## Ricerca e critica
Nessuna ricerca. Critica: 3 punti, tutti accolti (session_id da URL, ripetizioni di `esito`, variante dalla riga giusta), più i suggerimenti. Scartato solo `shellcheck` (dipendenza nuova).

## Prossima fetta
Prova vera con pagamento (se non fatta da te), poi T6.
