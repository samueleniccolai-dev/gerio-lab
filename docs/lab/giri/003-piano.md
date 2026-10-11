---
giro: 003
traguardo: T2
fase: domanda
inizio: 2026-10-11T15:36:45Z
ricerca: no
accettazione:
ripreso: no
---
# Piano del giro 003 — verifica visiva: primo E2E con browser reale e screenshot

## DOMANDA (porta a senso unico: dipendenza nuova)

**Contesto.** T2 chiede test E2E con un browser vero e screenshot in `target/e2e-screenshots/`,
che `ci.yml` carica già come artefatto `e2e-screenshots`. Oggi nel `pom.xml` non c'è nessuna
libreria per pilotare un browser. SPEC §14 dice «Playwright per Java». Serve quindi una dipendenza
Maven nuova (solo `test`) e, di conseguenza, il binario del browser che Playwright scarica al primo
avvio (Chromium headless, in `~/.cache/ms-playwright`). Il `pom.xml` è un percorso protetto: la PR
andrà comunque in revisione umana.

| Opzione | Cosa entra | Pro | Contro |
|---|---|---|---|
| **A. Playwright Java diretto** (raccomandata) | `com.microsoft.playwright:playwright:1.63.0`, scope `test` (ultima su Maven Central, 14/09/2026) | è ciò che dice SPEC §14; un solo artefatto, di Microsoft, aggiornato ogni mese; nessuno strato in mezzo; il browser lo scarica Playwright stesso, nessun `prepare.sh` | il ciclo di vita (avvio/chiusura del browser) lo scriviamo noi in una piccola classe di supporto nei test |
| B. Estensione Quarkiverse | `io.quarkiverse.playwright:quarkus-playwright` (versione e compatibilità con Quarkus 3.33 [DA VERIFICARE]: Maven Central ha rifiutato la richiesta per limite di traffico) | `@WithPlaywright` e `@InjectPlaywright`: meno codice di contorno | due dipendenze (estensione + Playwright transitivo); la versione di Playwright la decide l'estensione e può restare indietro; un'estensione Quarkus in più nel build |
| C. Selenium + browser in container Testcontainers | `org.seleniumhq.selenium:selenium-java` + `org.testcontainers:selenium` + immagine `selenium/standalone-chrome` | nessun browser sull'host, solo Docker | contro SPEC §14; un'immagine da ~1,5 GB a ogni CI; più lento; tre cose nuove invece di una |

**Raccomandazione: A**, versione 1.63.0, scope `test`, solo Chromium headless.

**Dopo la risposta.**
- **A:** il giro riparte da questo piano: dipendenza nel `pom.xml`, registrazione in
  `docs/verifiche.md` (versione, fonte, data), primo test E2E sul negozio demo e sulla landing,
  screenshot in `target/e2e-screenshots/`. Se in CI il browser non parte per librerie di sistema
  mancanti, serve `scripts/ci/prepare.sh` (percorso protetto): lo si aggiunge nella stessa PR, che
  è già in revisione umana per il `pom.xml`.
- **B:** stesso piano; prima il ricercatore verifica versione e compatibilità dell'estensione con
  Quarkus 3.33 e registra in `docs/verifiche.md`; il supporto del ciclo di vita lo dà l'estensione.
- **C:** il piano cambia: niente browser sull'host, un container Selenium; la PR va comunque in
  revisione e SPEC §14 andrebbe corretta con una PR umana (o registrata in `scostamenti-4-10.md`).
- **No a tutte:** T2 resta `bloccato`; si passa a T3 senza verifica visiva.

## Fetta e traguardo
T2 intero: è piccolo (un test E2E e la cartella degli screenshot) e dà a ogni PR successiva
(T3–T8) l'anteprima visiva per l'umano.

## Dati del negozio demo (inventati, usati dai test di accettazione)
| Campo | Valore |
|---|---|
| tenant id | `00000000-0000-0000-0000-0000000000d1` |
| slug / host | `fioraia-demo` → `fioraia-demo.localhost` |
| nome | `Le Rose di Lucia` |
| stato | `ACTIVE` |
| secondo negozio (isolamento) | `00000000-0000-0000-0000-0000000000d2`, `fiori-di-marco`, `Fiori di Marco` |

Nessun prezzo: T2 non tocca il catalogo.

## Fatto quando — controlli eseguibili
- [ ] `./mvnw -q -Dtest='Giro003*AcceptanceTest' test` → verde; il test avvia Chromium headless
  reale (non HtmlUnit, non un client HTTP) contro l'app di test su `quarkus.http.test-port`.
- [ ] Aperto `http://fioraia-demo.localhost:<porta>/` nel browser → l'`h1` letto dal DOM è
  `Le Rose di Lucia`; il testo della pagina non contiene `Fiori di Marco`.
- [ ] Aperto `http://localhost:<porta>/` → la pagina contiene `Negozi locali online` e nessun nome
  di negozio.
- [ ] Dopo il test esistono `target/e2e-screenshots/storefront-home.png` e
  `target/e2e-screenshots/platform-landing.png`: file PNG validi (firma `89 50 4E 47`), larghi
  almeno 360 px (viewport mobile 390×844, SPEC §2.4 «prima il telefono»).
- [ ] Browser che non parte = test **rosso**, mai saltato (niente `assumeTrue`, niente `@Disabled`).
- [ ] `./mvnw -B -ntp verify` → verde (E2E inclusi, Spotless incluso).
- [ ] Sulla PR del giro, run `ci` verde e artefatto presente:
  `gh api repos/{owner}/{repo}/actions/runs/<run>/artifacts --jq '.artifacts[].name'` →
  contiene `e2e-screenshots`.
- [ ] `docs/verifiche.md` registra la versione della dipendenza con fonte e data.

## Fuori perimetro
- Acquisto completo, back-office, giro consegne, Stripe: gli E2E di SPEC §14 su quei flussi
  arrivano con T3–T6.
- Controlli di accessibilità axe (SPEC §14): un'altra dipendenza; vanno in «Idee per dopo».
- Firefox e WebKit: solo Chromium.
- Confronto automatico degli screenshot (regressione visiva): solo pubblicazione.
- Modifiche a `.github/workflows/ci.yml` (protetto; il caricamento dell'artefatto c'è già).
- Da `traguardi.json → fuori_perimetro`: back-office del negozio; tipo di consegna CEMETERY;
  capacità per fascia oraria; impianto consensi e GDPR oltre la pagina privacy; SPF e DKIM completi.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|
| `Giro003VisualAcceptanceTest` | dato il negozio demo, quando Chromium apre il suo host, allora l'`h1` è `Le Rose di Lucia` e non compare `Fiori di Marco` |
| `Giro003VisualAcceptanceTest` | quando Chromium apre `localhost`, allora si vede la landing senza nomi di negozio |
| `Giro003VisualAcceptanceTest` | dopo i due casi, allora i due PNG esistono, validi, larghezza ≥ 360 px |

I test si scrivono solo dopo la risposta alla domanda (devono compilare: senza la dipendenza non
compilano).

## Porte
- Doppio senso previste: classe di supporto nei test (`src/test/java/.../e2e/`); nomi dei file
  degli screenshot; viewport 390×844; cartella `target/e2e-screenshots/`.
- Senso unico: **DOMANDA** sopra (dipendenza Maven nuova + binario del browser). Eventuale
  `scripts/ci/prepare.sh`: protetto, rientra nella stessa revisione umana.

## Rischi di concorrenza
Nessuno stato condiviso nuovo. Un solo browser per classe di test, chiuso in `@AfterAll`; i dati dei
negozi inseriti e cancellati attorno a ogni test, come in `StorefrontPageTest`. Rischio pratico:
CI che passa a Ubuntu 26 dal 19/10 (PROGRESS) e librerie di sistema di Chromium: si vede nel run
`ci` della PR.

## Budget
Righe di produzione: 0 (solo test, `pom.xml`, documenti). File ≤ 5. Una sessione.

## Domande per il ricercatore (al massimo 3)
Nessuna: `ricerca: no` (binari e test; il cliente non vede niente di nuovo).

## Idee per dopo
- axe-core dentro gli E2E (SPEC §14), con la sua porta.
- Screenshot anche in viewport desktop.

## Critica ricevuta e cosa cambia
(dopo il critico)
