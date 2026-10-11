---
giro: 007
traguardo: T3
esito: pr-aperta
inizio: 2026-10-11T21:35:29Z
fine: 2026-10-11T21:42:00Z
pr: 10
modello_orchestratore: sonnet
modello_costruttore: nessuno
modello_revisore: opus
ricerca_fonti: 0
critico_da_correggere: 2
critico_accolti: 2
revisore_bloccanti: 0
revisore_dubbi: 2
dubbi_corretti: 0
fuori_perimetro: 0
mutazioni_provate: 0
mutazioni_prese: 0
verify_rossi_locali: 0
test_totali: 235
test_delta: 0
decisioni_registro: 0
decisioni_ratifica: 0
porte_senso_unico: 0
riparazioni: 0
difetto_di:
---
# Rapporto del giro 007 — prova del demo in produzione (T3)

> **PR:** #10 · **Branch:** `claude/giro-007-t3` · **Traguardo:** T3 — passato
> **Domande che bloccano:** nessuna

## Per te, in 10 righe
- Giro di soli documenti: nessun codice, nessun test nuovo.
- `consegna` 38166479287 su 26d5e92 è verde, incluso «Deploy and smoke test».
- Prova a mano sul demo (dominio `demo.gerio-lab.samueleniccolai.com`), 2026-10-11T21:37:54Z:
  - `/catalogo` → 200, «Fioreria Il Glicine» ×2, «Mazzo di rose rosse» ×1.
  - `/prodotti/rose-rosse` → 200, «Mazzo di rose rosse» ×2.
- T3 segnato `passato` (giri 005–007, PR 8, 9, 10).
- Revisore indipendente: 0 bloccanti, 2 dubbi (sotto).
- Prossimo: T4.

## Cosa non funziona ancora
- Build completa in locale non verificabile nel revisore: Docker Hub 429 su `caddy:2.11.7` e Chromium Playwright mancante. Il codice è identico a `main`, dove la CI è verde.
- FK di V3 senza `tenant_id`; avviso Ubuntu 26 in CI dal 19/10 (`.github/` è tua).

## Dubbi del revisore
1. Build locale rossa per ambiente (vedi sopra), non per il codice.
2. `$LAB_BASE_DOMAIN` non definita: dominio ricavato dal piano del giro 006 e riportato qui sopra.

## Commit
Solo documenti: piano, rapporto, `traguardi.json`, PROGRESS.
