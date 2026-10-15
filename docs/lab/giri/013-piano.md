---
giro: 013
traguardo: T5
fase: chiusura
inizio: 2026-10-15T09:10:56Z
ricerca: no
accettazione: 943e4ac
ripreso: no
---
# Piano del giro 013 — T5, prova in produzione con Stripe test

## DOMANDA (risolta)
Risolta il 15/10 con l'opzione A: decisione n. 31 in `docs/lab/DECISIONI.md`. L'umano ha fatto i
passi 1–3 del runbook §4; il webhook di produzione risponde già **400** (prima riga *prova vera*
soddisfatta); il pagamento con la 4242 lo fa l'umano.

## Fetta e traguardo
T5, parte 4 (ultima): rendere la prova verificabile e farla. Lo smoke diventa rigoroso quando
il segreto del webhook è configurato (oggi accetta 503 per sempre), uno script a scatola chiusa
porta un ordine del negozio demo fino al link di Stripe Checkout e ne legge l'esito, il runbook §4
spiega prova e guasti. Poi un pagamento di test vero e T5 → `passato`.

## Dati del negozio demo (inventati, gli stessi dei giri 010–012)
- Negozio: `demo.gerio-lab.samueleniccolai.com`, «Fioreria Il Glicine».
- Carrello: 1 × «Mazzo di rose rosse — 12 rose» 4500 + 2 × «Orchidea Phalaenopsis bianca — Vaso
  12 cm» 2990 = **10480** centesimi («104,80 €»); ritiro in negozio, costo 0.
- Cliente: Giulia Bianchi, `giulia.bianchi@example.com`, `+39 333 000 1234`; giorno di ritiro = il
  primo possibile, letto da `min="…"` del riepilogo.
- Carta di test Stripe: 4242 4242 4242 4242, scadenza futura qualsiasi, CVC qualsiasi.
- Nei test (curl finto, mai la rete): segreto finto `whsec_giro013_finto`; dominio
  `lab.example.test`, demo `demo.lab.example.test`; varianti finte rose «24 rose» 102 (prima nella
  pagina) e «12 rose» 101, orchidea «Vaso 9 cm» 202 (prima) e «Vaso 12 cm» 201; valore CSRF servito
  `Z2lybzAxMy1jc3JmLXRva2Vu`; `min` del giorno di ritiro `2031-05-17`; sessione
  `cs_test_a1Giro013Glicine`; URL di Checkout
  `https://checkout.stripe.com/c/pay/cs_test_a1Giro013Glicine#fidkdWxOYHwnPyd1blpxYHZxWjA0`;
  ordine `2026-0042`.

## Fatto quando — controlli eseguibili
- [ ] **Smoke.** `infra/smoke.sh` con `STRIPE_CONNECT_WEBHOOK_SECRET` non vuoto: `POST
      https://$LAB_BASE_DOMAIN/webhooks/stripe/connect` deve dare **400**; 503, 404 o 500 → exit 1
      con un messaggio che contiene «webhook» e mai il segreto (né in stdout/stderr né negli
      argomenti di curl). Senza la variabile: 400 o 503 → verde come oggi. Il demo resta 404. Lo
      smoke non chiama mai `/checkout`, `/carrello` né `/ordine` → `Giro013SmokeStripeAcceptanceTest`.
- [ ] **Prova, avvio.** `infra/prova-stripe.sh <dominio-demo>`:
      1. cookie jar creato con `mktemp` (sotto `$TMPDIR`), usato con `-b`/`-c` in ogni chiamata,
         cancellato da un `trap` anche in caso di errore; diverso a ogni lancio;
      2. `GET /prodotti/rose-rosse`: `variantId` preso dal form della riga che contiene «12 rose»
         (non il primo della pagina), `POST /carrello/aggiungi` con quantità 1; `GET
         /prodotti/orchidea-bianca`: variante della riga «Vaso 12 cm», quantità totale 2;
      3. ogni POST porta il campo `csrf-token` uguale al valore servito dalle pagine;
      4. `GET /checkout` deve contenere «Ordina e paga» e «104,80 €»; altrimenti exit 1 (senza
         «Ordina e paga» il messaggio contiene «checkout spento») e nessun `POST /checkout`;
      5. `POST /checkout` **senza `-L`** con `customerName=Giulia Bianchi`,
         `customerEmail=giulia.bianchi@example.com`, `customerPhone=+39 333 000 1234` (codificati
         come form), `pickupDate` = il valore di `min="…"` del riepilogo;
      6. vuole **303** con `Location` che inizia con `https://checkout.stripe.com/` (barra
         compresa: `https://checkout.stripe.com.evil.example.test/…` → exit 1, nessun URL in
         stdout); stampa in stdout solo quell'URL, frammento `#…` compreso; exit 0.
      Senza argomenti → exit 2, nessuna chiamata. `bash -n` pulito → `Giro013ProvaStripeAcceptanceTest`.
- [ ] **Prova, esito.** `infra/prova-stripe.sh esito <dominio-demo> <url-di-checkout>`: accetta
      l'URL stampato dall'avvio, ne estrae `cs_test_…` dal percorso (senza il frammento `#…`) e
      chiede `GET https://<dominio-demo>/ordine/esito?session_id=<id>`, fino a **15 volte, ogni 2 s**
      (`sleep 2`, al massimo 30 s):
      - «Pagamento ricevuto» → stampa il numero d'ordine (`2026-0042`), exit 0 (anche se arriva
        alla terza richiesta);
      - «non è stato completato» → exit 1 subito (una sola richiesta), stderr contiene «scaduta»;
      - 404 → exit 1, stderr contiene «sconosciuta», mai exit 0;
      - ancora «Stiamo confermando» dopo 15 richieste → exit 1, stderr contiene «in attesa».
      → `Giro013ProvaStripeAcceptanceTest` (curl e sleep finti).
- [ ] `docs/runbook.md` §4 completo: passi dell'umano, prova con lo script e la carta 4242; il link
      di Checkout finisce in un commento pubblico della PR ed è valido 24 h (poi la sessione
      scade); cosa guardare se il webhook non arriva (dashboard Stripe → consegne del webhook,
      400 = segreto sbagliato, 503 = segreto assente, 404 = dominio del demo), rinvio di un evento
      (deve dare 200 senza cambiare l'ordine). Revisione.
- [x] *(prova vera)* `curl -s -o /dev/null -w '%{http_code}' -X POST
      https://gerio-lab.samueleniccolai.com/webhooks/stripe/connect` → `400` (15/10, decisione 31).
- [ ] *(prova vera)* `infra/prova-stripe.sh demo.gerio-lab.samueleniccolai.com` → exit 0 e URL
      `https://checkout.stripe.com/…`, passato all'umano in un commento della PR.
- [ ] *(prova vera)* L'umano paga con 4242 e commenta `pagato`; `infra/prova-stripe.sh esito
      demo.gerio-lab.samueleniccolai.com <url>` → exit 0 con il numero d'ordine; nella dashboard
      Stripe (test) l'evento `checkout.session.completed` ha risposta 200. T5 → `passato`,
      `passato_il` = ora della verifica dell'esito.
- [ ] Test dei giri precedenti verdi; `./mvnw -B -ntp verify` verde (esclusi i 2 visivi senza Chromium).

## Fuori perimetro
- Modifiche all'app Java: checkout e webhook sono quelli dei giri 010–012. Se la prova vera trova un
  difetto, si registra e si corregge in un giro di riparazione, non qui.
- `.github/` (protetto): nessuna modifica; lo smoke legge solo variabili già passate al passo.
- `shellcheck` o altri binari nuovi (porta a senso unico): basta `bash -n`.
- Pagamento automatico su Checkout (opzione C), scenari rifiutato/3-D Secure/scaduto in produzione.
- Modalità live, ordine reale con rimborso (SPEC §8.5, prima del go-live), `charge.refunded`,
  `charge.dispute.created`, `account.updated`, onboarding dall'area admin.
- Email di conferma e pagina stato ordine (T6).
- Da `traguardi.json → fuori_perimetro`: back-office del negozio (piano B: email e dashboard
  Stripe); consegna `CEMETERY`; capacità per fascia oraria; impianto consensi e GDPR oltre la
  pagina privacy; SPF e DKIM completi.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|
| `Giro013SmokeStripeAcceptanceTest` | segreto presente e webhook 503/404/500 → rosso, «webhook» nel messaggio, segreto mai visibile; segreto presente e 400 → verde; segreto assente e 503 o 400 → verde; nessuna chiamata a `/checkout`, `/carrello`, `/ordine` |
| `Giro013ProvaStripeAcceptanceTest` | curl finto con carrello vero (stato su file), cookie jar e CSRF: varianti e quantità giuste, valore CSRF servito, `min` del riepilogo, niente `-L`, solo l'URL in stdout; checkout spento, prezzo cambiato, host simile a Stripe → exit 1; jar `mktemp` cancellato e diverso a ogni lancio; esito pagato/tardivo/scaduto/sconosciuto/in attesa con `sleep` finto; `bash -n`; senza argomenti → 2 |

## Porte
- Doppio senso: smoke rigoroso legato a una variabile (si toglie con una riga); script di prova
  nuovo in `infra/` (si cancella); testo del runbook.
- `infra/` è percorso protetto: PR in revisione umana (METODO §4).
- Senso unico: risolta con la decisione n. 31 (opzione A). Nessuna dipendenza (niente
  `shellcheck`), nessuna migrazione.

## Rischi di concorrenza
- Lo script crea un ordine `PENDING_PAYMENT` vero nel negozio demo a ogni lancio: si lancia a mano,
  non nello smoke; un ordine non pagato scade con Stripe (`checkout.session.expired`, 24 h).
- Due lanci insieme non si pestano: ognuno ha il suo cookie jar (`mktemp`), quindi il suo carrello.
- Webhook duplicato o rinviato dalla dashboard: idempotenza già provata nel giro 011; nella prova
  vera un «Rinvia» dalla dashboard deve dare 200 senza cambiare l'ordine (riga del runbook).

## Budget
Righe di produzione ≤ 150 (shell ≤ 120, runbook ≤ 30); file ≤ 4; una sessione.

## Domande per il ricercatore (al massimo 3)
Nessuna (`ricerca: no`: binari e verifica; il cliente non vede nulla di nuovo).

## Idee per dopo
- Prova periodica a scatola chiusa del checkout (senza pagare) per accorgersi di chiavi revocate.
- Scenari di prova in produzione: carta rifiutata (4000 0000 0000 0002), 3-D Secure, sessione scaduta.

## Critica ricevuta e cosa cambia
Accolti tutti e 3 i DA CORREGGERE e i suggerimenti:
1. `session_id`: una sola strada, `esito` accetta l'URL stampato dall'avvio ed estrae `cs_test_…`
   dal percorso senza il frammento; i test passano l'URL col `#…`.
2. `esito` riprova 15 volte ogni 2 s (sleep finto nei test); scaduta, sconosciuta (404, mai exit 0)
   e in attesa distinte; testo di pagato esatto «Pagamento ricevuto» (`storefront/outcome.html`).
3. `variantId` dalla riga «12 rose» e «Vaso 12 cm»: nel curl finto la variante sbagliata è la prima.
- Giorno di ritiro dal `min="…"` del riepilogo (niente calcolo di date nello script).
- Concorrenza: cookie jar con `mktemp` e `trap`; il test verifica che cambi a ogni lancio e sparisca.
- Test più stretti: due varianti per prodotto, quantità per variante, valore CSRF servito, niente
  `-L` sul `POST /checkout`, host simile a Stripe → exit 1; smoke con 404 e 500 → rosso, messaggio
  con «webhook» senza segreto.
- Facoltativi: il runbook dice che il link è pubblico in PR e scade in 24 h; `esito` stampa il
  numero d'ordine.

Scartato: `shellcheck` (binario nuovo, porta a senso unico; d'accordo col critico): resta `bash -n`.
Aggiunto: senza argomenti → exit 2; prezzo cambiato (orchidea a 25,00 €) → totale diverso → exit 1.
Budget shell alzato da 90 a 120 righe per i ritentativi e i casi di esito.
