# PROGRESS — letto all'avvio di ogni giro, aggiornato alla fine (≤ 1 pagina)

**Obiettivo:** traguardi T1–T8 di `docs/lab/traguardi.json` — demo in produzione con ordine
end-to-end in Stripe test, lo stesso piano di Gerio al 4/10/2026.
**Giro corrente:** 019 · **Fase:** chiusura · **PR aperta:** #24 · **Blocchi:** nessuno
**T0 del laboratorio:** 2026-10-11T09:43:32Z

## Prossimo

Dopo il merge di #24 tutti i traguardi sono `passato`: il giro successivo è la fine dell'esperimento (METODO §13).

## Ultimi giri

- 019 — T6 passato: email di conferma con PDF arrivata in produzione (Brevo, 16/10); nessun codice. PR #24.
- 018 — T8 passato: 4 pagine legali e piè di pagina completo, senza banner cookie; 430 test. PR #23.
- 017 — T7 passato: zone di consegna per CAP/comune, minimo, orario limite, IVA e riga Stripe; 418 test. PR #21.
- 016 — T6 in-corso: pagina e PDF delle condizioni + cablaggio SMTP/Brevo in `infra/`; 391 test. PR #20 (revisione umana: `pom.xml`).

## Per l'umano

- Far leggere a un consulente i testi legali del giro 018 (art. 14 par. 5, esenzione EAA, Stripe/Connect: [DA VERIFICARE]).
- Ratifiche n. 46 (IVA consegna), n. 35 (timer Vert.x), n. 36 (invio ora acceso in prod con Brevo), n. 39 (cache PDF non invalidata).
- Controllare il formato `wa.me` (FAQ WhatsApp troncata) e l'IVA con aliquote miste.
- Controllare l'art. 49 del Codice del consumo su Normattiva (art. 59 già letto).
- Controllare il testo di recesso/garanzia su artt. 49, 59, 128 ss. del Codice del consumo.
- Dopo il deploy: `curl -i https://demo.<LAB_BASE_DOMAIN>/ordine/<43 × A>` → 404.
- Caddy e app potrebbero inviare due `Referrer-Policy` (infra/, giro dedicato).
- Avviso CI: `ubuntu-latest` passa a Ubuntu 26 dal 19/10 (`.github/` è protetto: decidi tu).
- Dubbio: FK di V3 senza `tenant_id`; FK variante di V4 senza `on delete`.
- **Scadenza:** job di anonimizzazione dei dati di consegna entro 10/2027 (la privacy del giro 018
  promette 12 mesi, SPEC §12; nessun job lo fa ancora).

**Ultimo aggiornamento:** 2026-10-16 — giro 019.
