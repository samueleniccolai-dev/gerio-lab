# PROGRESS — letto all'avvio di ogni giro, aggiornato alla fine (≤ 1 pagina)

**Obiettivo:** traguardi T1–T8 di `docs/lab/traguardi.json` — demo in produzione con ordine
end-to-end in Stripe test, lo stesso piano di Gerio al 4/10/2026.
**Giro corrente:** 017 · **Fase:** chiusura · **PR aperta:** #21 · **Blocchi:** nessuno
**T0 del laboratorio:** 2026-10-11T09:43:32Z

## Prossimo

Dopo il merge di #21: giro 018 su T8 (pagine legali minime e rifiniture demo). T6 attende azioni umane.

## Ultimi giri

- 017 — T7 passato: zone di consegna per CAP/comune, minimo, orario limite, IVA e riga Stripe; 418 test. PR #21.
- 016 — T6 in-corso: pagina e PDF delle condizioni + cablaggio SMTP/Brevo in `infra/`; 391 test. PR #20 (revisione umana: `pom.xml`).
- 015 — T6 in-corso: coda `outbox_message`, worker e email di conferma (invio spento in prod); 374 test. PR #19 (revisione umana: `pom.xml`).
- 014 — T6 in-corso: pagina `/ordine/{token}` senza dati personali; 361 test. PR #18.

## Per l'umano

- **Brevo:** dominio autenticato e variabili in `produzione`. Manca: le 6 righe in `.github/workflows/consegna.yml` (contratto in `infra/README.md`), poi `GERIO_MAIL_ENABLED=true`.
- Ratifiche n. 46 (IVA consegna), n. 35 (timer Vert.x), n. 36 (invio spento in prod), n. 39 (cache PDF non invalidata).
- Controllare il formato `wa.me` (FAQ WhatsApp troncata) e l'IVA con aliquote miste.
- Controllare l'art. 49 del Codice del consumo su Normattiva (art. 59 già letto).
- Controllare il testo di recesso/garanzia su artt. 49, 59, 128 ss. del Codice del consumo.
- Dopo il deploy: `curl -i https://demo.<LAB_BASE_DOMAIN>/ordine/<43 × A>` → 404.
- Caddy e app potrebbero inviare due `Referrer-Policy` (infra/, giro dedicato).
- Avviso CI: `ubuntu-latest` passa a Ubuntu 26 dal 19/10 (`.github/` è protetto: decidi tu).
- Dubbio: FK di V3 senza `tenant_id`; FK variante di V4 senza `on delete`.
- **Scadenza:** job di anonimizzazione dei dati di consegna entro 10/2027 (la privacy del giro 018
  promette 12 mesi, SPEC §12; nessun job lo fa ancora).

**Ultimo aggiornamento:** 2026-10-16 — giro 017.
