# PROGRESS — letto all'avvio di ogni giro, aggiornato alla fine (≤ 1 pagina)

**Obiettivo:** traguardi T1–T8 di `docs/lab/traguardi.json` — demo in produzione con ordine
end-to-end in Stripe test, lo stesso piano di Gerio al 4/10/2026.
**Giro corrente:** 003 · **Fase:** domanda (da: piano) · **PR aperta:** #5 · **Blocchi:** nessuno
**T0 del laboratorio:** 2026-10-11T09:43:32Z

## Prossimo

Dopo il merge di #3: T2 (verifica visiva). Dipendenza nuova (Playwright o equivalente): porta a senso unico, richiesta formale con alternativa.

## Ultimi giri

- 002 — T1 passato: deploy reale provato (run 38151028698, smoke ok), solo documenti. PR #3.
- 001 — T1 in-corso: file di deploy in `infra/`, 19 test di accettazione. PR #2 (mergiata).

## Per l'umano

- **DOMANDA (PR #5, giro 003, T2):** dipendenza Maven nuova per il browser E2E. A) `com.microsoft.playwright:playwright:1.63.0` scope test (raccomandata, SPEC §14); B) estensione Quarkiverse; C) Selenium + Testcontainers. Dettagli e conseguenze in `docs/lab/giri/003-piano.md`.
- Ratifiche da leggere: 503 per alcuni secondi dopo ogni deploy (DECISIONI n. 5).
- Avviso CI: `ubuntu-latest` passa a Ubuntu 26 dal 19/10 (`.github/` è protetto: decidi tu).

**Ultimo aggiornamento:** 2026-10-11 — giro 002.
