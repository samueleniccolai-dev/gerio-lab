# Ricerca giro 014 — pagina di stato ordine via link segreto

Fonti consultate il 2026-10-15 (5; solo il W3C aperto, le altre lette come riassunto di ricerca).

## Come lo fanno gli altri
- Shopify: la pagina di stato senza login mostra solo dati pubblici (articoli, stato, totale) e oscura i dati personali. Il token nel link ha durata limitata. Fonte: https://shopify.dev/docs/apps/customer-accounts/order-status-page (non aperta).
- Floranext: tracciamento lato negozio e foto di consegna; nessuna pagina pubblica «numero d'ordine» trovata. Fonti: https://floranext.com/floranext-delivery-manager/ , https://www.capterra.com/p/122876/Floranext/reviews/ (non aperte).
- Fioricitta, Floracom, Hana, Square Online: nulla di utile trovato.

## Casi reali e documentazione
- W3C TAG, Capability URLs: https, scadenza, niente effetti collaterali in GET, limite di frequenza, controllo del referrer, niente script o contenuti di terzi, 404/410 per URL scaduti. Fonte: https://www.w3.org/2001/tag/doc/capability-urls/ (aperta).
- Referrer-Policy assente: il token può finire nel Referer. Fonte: https://www.invicti.com/web-vulnerability-scanner/vulnerabilities/referrer-policy-not-implemented (non aperta).
- Non consultati GDPR né Garante: la minimizzazione è un ragionamento, non una fonte.

## Cosa prendiamo
1. Minimizzazione: niente nome, email, telefono, indirizzo.
2. `Referrer-Policy: no-referrer`, `Cache-Control: no-store`, `X-Robots-Tag: noindex`.
3. Niente script, immagini o link verso domini esterni.
4. 404 identico per token sconosciuto, malformato o di altro negozio; GET di sola lettura.

## Idee per dopo
- Scadenza del link (oggi il token non scade; da decidere con SPEC §7.2).
- Limite di frequenza per IP su `/ordine/{token}`.
- Con T7: verifica aggiuntiva prima di mostrare destinatario e indirizzo.
