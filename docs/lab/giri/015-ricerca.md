# Ricerca giro 015 — email di conferma (consultata il 15/10/2026)

Risposta del ricercatore, riportata così com'è nella sostanza. Fonti aperte: 3 di 5 citate.

## Come lo fanno gli altri
- Shopify: la documentazione pubblica NON descrive oggetto e blocchi di default della conferma; elenca
  solo le variabili del modello (numero ordine/data, righe, spedizione, subtotale/tasse/totale,
  cliente, contatti negozio, `order_status_url`): https://help.shopify.com/en/manual/orders/notifications/email-variables
- Etsy e fioristi italiani (Fioricitta, Floracom): non verificato.
- Guide Shopify Italia sul recesso: beni deperibili come questione di natura del prodotto:
  https://it.shopify.com/blog/diritto-di-recesso-siti-ecommerce (solo dal sunto della ricerca)

## Casi reali e documentazione
- Gmail, da 1/2/2024: SPF o DKIM, TLS, PTR valido, RFC 5322, un solo indirizzo in From, spam sotto
  0,3% (https://support.google.com/a/answer/81126). DMARC e allineamento obbligatori solo sopra
  5.000 messaggi al giorno.
- Da evitare: nome visualizzato fuorviante, promozioni mescolate in una ricevuta, contenuti
  nascosti, emoji al posto della grafica, picchi di volume. Outlook e iOS: non verificato.
- Art. 59 c.1 lett. d) Codice del consumo: recesso escluso per beni che rischiano di deteriorarsi
  rapidamente (https://brocardi.it/codice-del-consumo/parte-iii/titolo-iii/capo-i/sezione-ii/art59.html,
  solo dal sunto). Normattiva segnala modifiche recenti a artt. 49 e 59: il testo va controllato.

## Cosa prendiamo
1. Mittente `"Fioreria Il Glicine tramite Gerio" <ordini@dominio-piattaforma>`, un solo From, nome senza oggetto né cliente; Reply-To al negozio lecito.
2. Il test non prova la consegnabilità: SPF o DKIM del provider sono il minimo; DMARC non ancora.
3. Solo contenuto transazionale.
4. Oggetto «Conferma ordine 2026-0001 — Fioreria Il Glicine». Blocchi (dedotti dalle variabili Shopify): numero e data, righe e totale, pagamento e ritiro, venditore, recesso e garanzia, link allo stato.
5. Formula D3 (non è parere legale): «Per i fiori freschi, beni che si deteriorano rapidamente, il diritto di recesso è escluso (art. 59, c. 1, lett. d, Codice del consumo). Resta la garanzia legale di conformità: per un difetto scrivi a <contatti>». «24 mesi» e articoli sulla garanzia: [DA VERIFICARE] in `docs/verifiche.md`.

## Cosa evitiamo
- Marketing o disiscrizione nella conferma; articoli o durate citati a memoria; «recesso escluso» per prodotti non deperibili.

## Idee per dopo
- Confrontare il modello reale di Shopify e i siti Fioricitta/Floracom; test su Outlook/iOS col provider; DMARC `p=none`.
