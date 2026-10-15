# PROGRESS — letto all'avvio di ogni giro, aggiornato alla fine (≤ 1 pagina)

**Obiettivo:** traguardi T1–T8 di `docs/lab/traguardi.json` — demo in produzione con ordine
end-to-end in Stripe test, lo stesso piano di Gerio al 4/10/2026.
**Giro corrente:** 016 · **Fase:** domanda · **PR aperta:** #20 · **Blocchi:** nessuno
**T0 del laboratorio:** 2026-10-11T09:43:32Z

## Prossimo

Dopo il merge di #20: giro 017 su T7 (zone di consegna e orario limite).

## Ultimi giri

- 016 — T6 in-corso: pagina e PDF delle condizioni, allegato alla conferma (openhtmltopdf 1.1.97); 385 test. PR #20 (revisione umana: `pom.xml`).
- 015 — T6 in-corso: coda `outbox_message`, worker e email di conferma (invio spento in prod); 374 test. PR #19 (revisione umana: `pom.xml`).
- 014 — T6 in-corso: pagina `/ordine/{token}` senza dati personali; 361 test. PR #18.

## Per l'umano

- **DOMANDA giro 016 (PR #20):** il PDF delle condizioni richiede una dipendenza nuova. A (consigliata) `openhtmltopdf-pdfbox`; B scrittore PDF in casa; C PDF statico; D rimandare. Dettagli in `docs/lab/giri/016-piano.md`. Rispondi con un commento (`⏱ <minuti>'`, `da Gerio: sì/no`) e l'etichetta `risposto`.

- **Provider SMTP UE** (Brevo, Scaleway, Mailjet: [DA VERIFICARE]): account, DNS, segreti; poi `GERIO_MAIL_ENABLED=true` e `GERIO_SMTP_*`. Finché manca, le conferme restano `PENDING`.
- Ratifiche n. 35 (timer Vert.x), n. 36 (invio spento in prod), n. 39 (cache PDF non invalidata).
- Controllare l'art. 49 del Codice del consumo su Normattiva (art. 59 già letto).
- Controllare il testo di recesso/garanzia su artt. 49, 59, 128 ss. del Codice del consumo.
- Dopo il deploy: `curl -i https://demo.<LAB_BASE_DOMAIN>/ordine/<43 × A>` → 404.
- Caddy e app potrebbero inviare due `Referrer-Policy` (infra/, giro dedicato).
- Avviso CI: `ubuntu-latest` passa a Ubuntu 26 dal 19/10 (`.github/` è protetto: decidi tu).
- Dubbio: FK di V3 senza `tenant_id`; FK variante di V4 senza `on delete`.

**Ultimo aggiornamento:** 2026-10-15 — giro 016.
