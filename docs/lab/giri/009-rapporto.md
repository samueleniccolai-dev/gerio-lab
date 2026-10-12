---
giro: 009
traguardo: T4
esito: pr-aperta
inizio: 2026-10-12T06:40:21Z
fine: 2026-10-12T06:46:00Z
pr: 12
modello_orchestratore: sonnet
modello_costruttore: nessuno
modello_revisore: opus
ricerca_fonti: 0
critico_da_correggere: 2
critico_accolti: 2
revisore_bloccanti: 0
revisore_dubbi: 0
dubbi_corretti: 0
fuori_perimetro: 0
mutazioni_provate: 0
mutazioni_prese: 0
verify_rossi_locali: 0
test_totali: 251
test_delta: 0
decisioni_registro: 0
decisioni_ratifica: 0
porte_senso_unico: 0
riparazioni: 0
difetto_di:
---
# Rapporto del giro 009 — prova del carrello in produzione (T4)

> **PR:** #12 · **Branch:** `claude/giro-009-t4` · **Traguardo:** T4 — passato
> **Domande che bloccano:** nessuna

## Per te, in 10 righe
- Giro di soli documenti: nessun codice, nessun test nuovo.
- `consegna` 38213443526 su 4343195 è verde, incluso il job `deploy`.
- Prova sul demo in produzione (`demo.gerio-lab.samueleniccolai.com`), 2026-10-12T06:42Z, con `curl`:
  tutti i passi 1–6 danno 303 e i totali attesi; `quantity=0` in aggiunta e POST senza token → 400.
- T4 segnato `passato` (giri 008–009, PR 11 e 12).
- Da te: nulla. Il carrello di prova è stato svuotato (la riga `cart` resta).
- Prossimo: T5 (Stripe Checkout in test); il filtro CSRF va escluso per il webhook (ratifica n. 18).

## Prova — passi eseguiti
Varianti lette dalle schede: Medio = 2 (bouquet-di-stagione), Vaso 12 cm = 8 (orchidea-bianca); id riga letti da `/carrello`.

| Passo | POST | Totale letto dopo |
|---|---|---|
| 0 | `GET /carrello` senza cookie → 200 | «Il tuo carrello è vuoto.» |
| 1 | Medio × 2 → 303 | 70,00 € |
| 2 | Vaso 12 cm × 1 → 303 | 99,90 € |
| 3 | Medio × 1 → 303 | 134,90 € (due righe) |
| 4 | orchidea a 2 → 303 | 164,80 € |
| 5 | rimuovi Medio → 303 | 59,80 € |
| 6 | orchidea a 0 → 303 | carrello vuoto |
| 7 | `/aggiungi` con `quantity=0` → 400; senza token CSRF → 400 | — |

Il cookie `csrf-token` ha `Secure; HttpOnly`. Il cookie `cart` non è stato ispezionato con `curl -i` (facoltativo della critica, non fatto; coperto da `Giro008CartAcceptanceTest`).

## Cosa non funziona ancora
- Webhook Stripe e filtro CSRF (T5); `token-signature-key` non impostato.
- FK di V3/V4 senza `tenant_id` / `on delete`; avviso Ubuntu 26 in CI dal 19/10 (`.github/` è tua).
- Nessuna pulizia dei carrelli vecchi.

## Critica
2 punti da correggere, 2 accolti (passo 7 su `/aggiungi`; id letti dalle pagine) più il facoltativo sul numero PR.

## Commit
Solo documenti: piano, rapporto, `traguardi.json`, PROGRESS, metriche.
