---
giro: 003
traguardo: T2
fase: chiusura
inizio: 2026-10-11T15:36:45Z
ricerca: no
accettazione: 531633e
ripreso: no
---
# Piano del giro 003 — verifica visiva: primo E2E con browser reale e screenshot

## RISPOSTA (11/10): **A** — Playwright Java 1.63.0, scope `test`. Da Gerio: sì. Tempo umano: 2'.

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

## Stato all'inizio della costruzione
La dipendenza approvata (`com.microsoft.playwright:playwright:1.63.0`, scope `test`) è già nel
`pom.xml`, in un commit `build(test):` del pianificatore: senza, i test di accettazione non
compilano. Il test di accettazione **è** il primo E2E: gestisce da sé il ciclo di vita del browser
(una classe di supporto senza un secondo utilizzatore sarebbe codice senza uso, vedi «Idee per
dopo»). Al costruttore restano: registro in `docs/verifiche.md`, `verify` completo con Spotless,
run `ci` verde con l'artefatto.

## Browser nell'ambiente del costruttore (proxy con CA propria)
- Qui `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers` e `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`: Playwright
  **non** scarica il browser da solo. La build 1.63.0 vuole `chromium_headless_shell-1243`
  (in `/opt/pw-browsers` c'era solo la 1194). Il pianificatore l'ha già scaricata così:
  ```
  ./mvnw -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=<scratch>/cp.txt
  PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD= java -cp "$(cat <scratch>/cp.txt)" \
    com.microsoft.playwright.CLI install --only-shell chromium
  ```
  Il download passa dal proxy (`HTTPS_PROXY`) e si fida della CA con `NODE_EXTRA_CA_CERTS`
  (già impostata, `/root/.ccr/README.md`). Se in una nuova sessione manca, si ripete il comando.
- In CI (`ubuntu-latest`) la variabile di salto non c'è: `Playwright.create()` scarica Chromium
  al primo avvio.
- In nessun caso il test si salta: niente `assumeTrue`, niente `@Disabled`, niente profili che lo
  escludono. Browser assente = rosso.

## Fatto quando — controlli eseguibili
- [ ] `./mvnw -q -Dtest='Giro003*AcceptanceTest' test` → verde (5 test); il browser è Chromium
  reale (`browserType().name()` = `chromium`, user agent con `Chrome/`), headless, contro l'app di
  test sulla porta data da `@TestHTTPResource` (mai `8081` scritto a mano).
- [ ] Aperto `http://fioraia-demo.localhost:<porta>/` → `200`, l'`h1` letto dal DOM è
  `Le Rose di Lucia`; il testo della pagina non contiene `Fiori di Marco`.
- [ ] Aperto `http://fiori-di-marco.localhost:<porta>/` → `200`, `h1` `Fiori di Marco`, nessun
  `Le Rose di Lucia` (isolamento nei due sensi; entrambi i negozi inseriti in `@BeforeEach`).
- [ ] Aperto `http://localhost:<porta>/` → `200`, contiene `Negozi locali online`, non contiene né
  `Le Rose di Lucia` né `Fiori di Marco`.
- [ ] Ogni caso salva e controlla il **proprio** PNG nello stesso metodo (`@BeforeAll` cancella i
  due file prima): `target/e2e-screenshots/storefront-home.png` e
  `target/e2e-screenshots/platform-landing.png`, firma PNG, `fullPage(false)`,
  `deviceScaleFactor 1`, esattamente **390×844** px (SPEC §2.4 «prima il telefono»).
- [ ] Browser che non parte = test **rosso**, mai saltato (niente `assumeTrue`, niente `@Disabled`).
- [ ] `./mvnw -B -ntp verify` → verde (E2E inclusi, Spotless incluso).
- [ ] Sulla PR del giro, run `ci` verde e artefatto presente:
  `gh api repos/{owner}/{repo}/actions/runs/<run>/artifacts --jq '.artifacts[].name'` →
  contiene `e2e-screenshots`.
- [ ] `docs/verifiche.md` ha una riga datata `| 2026-…` con `com.microsoft.playwright`, `1.63.0` e
  la fonte (Maven Central, playwright.dev o GitHub): provato dal test di accettazione.

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
| `Giro003VisualAcceptanceTest` | quando parte il browser, allora è un Chromium vero |
| `Giro003VisualAcceptanceTest` | dato il negozio demo, quando Chromium apre il suo host, allora l'`h1` è `Le Rose di Lucia`, non compare `Fiori di Marco` e `storefront-home.png` è un PNG 390×844 |
| `Giro003VisualAcceptanceTest` | dato il secondo negozio, quando Chromium apre il suo host, allora l'`h1` è `Fiori di Marco` e non compare `Le Rose di Lucia` |
| `Giro003VisualAcceptanceTest` | quando Chromium apre `localhost`, allora si vede la landing senza nessuno dei due nomi e `platform-landing.png` è un PNG 390×844 |
| `Giro003VisualAcceptanceTest` | `docs/verifiche.md` registra Playwright 1.63.0 con fonte e data |

Stato alla consegna al costruttore: 4 verdi (la dipendenza approvata è già nel `pom.xml` e il
Chromium 1243 è scaricato), 1 rosso per asserzione (`verifiche.md`).

## Porte
- Doppio senso previste: nomi dei file
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
Rischio CI: il passaggio di `ubuntu-latest` a Ubuntu 26 dal 19/10 potrebbe lasciare Chromium senza
librerie di sistema supportate da Playwright; se il run `ci` fallisce per questo dopo il 19/10, si
rilancia e si annota nel rapporto (eventuale `scripts/ci/prepare.sh`, protetto).

## Domande per il ricercatore (al massimo 3)
Nessuna: `ricerca: no` (binari e test; il cliente non vede niente di nuovo).

## Idee per dopo
- axe-core dentro gli E2E (SPEC §14), con la sua porta.
- Screenshot anche in viewport desktop.
- Classe di supporto piccola in `src/test/java/it/gerio/platform/e2e/` (avvio/chiusura di
  Chromium, contesto mobile 390×844, salvataggio PNG): da estrarre dal test del giro 003 quando
  arriva il secondo E2E (T3), non prima.
- Cache di `~/.cache/ms-playwright` in CI: oggi ogni run riscarica Chromium (~115–150 MB). Toccare
  `ci.yml` è protetto: proposta per l'umano.

## Critica ricevuta e cosa cambia
Accolti 2 su 2 «DA CORREGGERE», 4 su 4 note sui test, 3 su 3 facoltativi; accolti in parte 2 «altro».

| Punto | Esito | Come |
|---|---|---|
| 1. Screenshot dipendenti dall'ordine e PNG vecchi | accolto | ogni caso salva e controlla il suo PNG; `@BeforeAll` cancella i due file |
| 2. Download di Chromium dietro il proxy | accolto | sezione «Browser nell'ambiente del costruttore»; il test non si salta mai |
| Classe di supporto in `e2e/` piccola | accolto in parte | rimandata a T3 («Idee per dopo»): oggi non avrebbe un secondo utilizzatore, e i test di accettazione non possono usare classi che non esistono |
| Ubuntu 26 dal 19/10 | accolto | nota in «Budget»: se cade, si rilancia `ci` e si annota |
| Larghezza == 390 con `deviceScaleFactor 1` | accolto | anche altezza == 844 |
| Browser reale | accolto | `browserType().name()` + user agent `Chrome/` (non `HeadlessChrome`: dipende dalla modalità headless) |
| Fiori di Marco davvero inserito | accolto | entrambi in `@BeforeEach`, più il caso simmetrico sul suo host |
| Landing senza entrambi i nomi | accolto | |
| URL da `@TestHTTPResource` | accolto | |
| Cache Chromium in CI | accolto | «Idee per dopo» |
| `fullPage(false)` | accolto | |

Nota: con la dipendenza nel `pom.xml` (approvata, necessaria alla compilazione) i casi col browser
passano già. Non è un piano sbagliato: il lavoro di prodotto di T2 era la dipendenza più il primo
E2E, e il primo E2E è il test di accettazione stesso. Il rosso resta su `verifiche.md`; il resto
del «Fatto quando» (verify, CI, artefatto) si prova con i comandi.
