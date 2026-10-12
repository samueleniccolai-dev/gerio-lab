# Ricerca giro 008 — carrello e CSRF

(Risposta del ricercatore, 11/10/2026. Nessun concorrente consultato: lo dichiara l'agente.)

## Come lo fanno gli altri
- Nessuna pagina di concorrente consultata (Shopify, Square, Floranext, Hana, Fioricittà, Floracom). Nulla affermato su di loro.
- Stripe: nell'esempio ufficiale il carrello è una pagina di anteprima con un form `POST`, poi redirect 303 a Checkout (docs.stripe.com/checkout/quickstart).

## Casi reali e documentazione
- D1, CSRF. Campo nascosto `name="{inject:csrf.parameterName}"` `value="{inject:csrf.token}"`. Default: campo `csrf-token`, cookie `csrf-token`, HttpOnly, `Path=/`, 2 ore.
- D1, errore: `POST` form senza campo, senza cookie o con valori diversi → 400.
- D1, `POST` non-form: il filtro controlla solo `application/x-www-form-urlencoded` e `multipart/form-data` (`require-form-url-encoded=true` di default). Il webhook Stripe (JSON) in teoria passa. [DA VERIFICARE] con un test reale in T5.
- D1, il cookie del token si crea sulle `GET` elencate in `create-token-path`. Dietro Caddy serve `cookie-force-secure=true`. La proprietà si chiama `require-form-url-encoded`.
- D2, WCAG 3.3.2 (A): per la quantità serve un `<label>` visibile, non solo `aria-label`. Su select, +/− e conferma: nessuna fonte, buon senso.
- D3, MDN: `Lax` invia il cookie sulle navigazioni top-level cross-site con metodo sicuro; `Strict` no. MDN non tratta i redirect espressamente.
- D3, banner: fonte secondaria (legiscope.com, pagina non aperta): i cookie tecnici non richiedono consenso, solo informativa.

## Cosa prendiamo
1. `quarkus-rest-csrf` senza versione; campo nascosto in ogni form `POST`.
2. `quarkus.rest-csrf.create-token-path` per le pagine con form; test che il cookie del token arrivi dalla scheda prodotto.
3. Prod: `cookie-force-secure=true`; `token-signature-key` da variabile d'ambiente, mai nel repository.
4. Quantità: `<input type="number" min="1" max="20" inputmode="numeric">` con `<label for>`; «Rimuovi» senza conferma (scelte nostre, senza fonte).
5. Cookie `cart` `SameSite=Lax`, nessun banner; annotare in `docs/verifiche.md` che la fonte è secondaria.

## Cosa evitiamo
- `SameSite=Strict` sul carrello (ritorno da Stripe).
- Dare per scontato che il webhook non sia toccato dal filtro CSRF, senza test in T5.
- `aria-label` al posto dell'etichetta visibile.

## Idee per dopo
- Demo pubbliche di Floranext, Hana, Floracom. Con htmx il token va nell'header `X-CSRF-TOKEN`. +/− come miglioramento progressivo.

## Fonti (11/10/2026)
- https://quarkus.io/guides/security-csrf-prevention
- https://docs.stripe.com/checkout/quickstart
- https://www.w3.org/WAI/WCAG21/Understanding/labels-or-instructions.html
- https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie
- https://www.legiscope.com/blog/cookie-linee-guida-garante.html (secondaria, non aperta)
