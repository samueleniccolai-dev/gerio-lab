---
giro: 018
traguardo: T8
fase: accettazione
inizio: 2026-10-16T10:12:04Z
ricerca: sì
accettazione: Giro018LegalFooterAcceptanceTest, Giro018LegalPagesAcceptanceTest, Giro018LegalIsolationAcceptanceTest
ripreso: no
---
# Piano del giro 018 — T8: pagine legali minime e piè di pagina completo

## Fetta e traguardo
T8 intero: il negozio demo ha le pagine legali minime di SPEC §2.2 e §12.3 (venditore, privacy,
cookie, accessibilità; le condizioni esistono dal giro 016) e un piè di pagina completo su ogni
pagina della vetrina. È l'ultimo traguardo non bloccato da azioni umane (T6 attende Brevo). Ci sta
in una sessione: niente migrazioni, niente dipendenze, quattro template quasi statici, un piè di
pagina condiviso.

## Dati del negozio demo (inventati, usati dai test)
In `src/main/resources/demo/demo-shop.sql`, riga `tenant` di «Fioreria Il Glicine» (upsert esistente).
Si aggiungono solo i campi che mancano, le colonne ci sono già da V1:

| Campo | Valore |
|---|---|
| `legal_name` | Il Glicine di Lucia Ferri (c'è già) |
| `address_line`, `postal_code`, `city`, `province` | Via dei Fossi 12, 55100, Lucca, LU (ci sono già) |
| `vat_number` | IT00000000000 (c'è già) |
| `email`, `phone` | ciao@glicine.example, +39 0583 000000 (ci sono già) |
| `pec` | **glicine@pec.example** (nuovo) |
| `rea` | **LU-000000** (nuovo) |

L'upsert aggiorna anche `pec` e `rea` nel `do update set`: in produzione la riga del demo
esiste già, e un seed che li scrivesse solo all'inserimento non li porterebbe mai lì.

`tax_code` resta vuoto: un codice fiscale plausibile potrebbe coincidere con quello di una persona
reale, e per una ditta individuale con P. IVA non serve in queste pagine.

Negozio B dei test: «Fiori di Marco Srl» (`fiori-di-marco.localhost`, `Giro005DemoData.insertMarco`),
senza P. IVA, PEC né REA.

Percorsi nuovi (solo su host di negozio; sull'host della piattaforma `localhost` → 404, come
`/condizioni-di-vendita`):

| Percorso | Titolo `<h1>` |
|---|---|
| `/informazioni-venditore` | Informazioni sul venditore |
| `/privacy` | Informativa sulla privacy |
| `/cookie` | Informativa sui cookie |
| `/accessibilita` | Dichiarazione di accessibilità |

Piè di pagina (un solo frammento, nel layout `storefront/layout.html`), per il demo:
«Il Glicine di Lucia Ferri · Via dei Fossi 12, 55100 Lucca (LU) · P. IVA IT00000000000 ·
ciao@glicine.example · +39 0583 000000», poi i link a `/informazioni-venditore`,
`/condizioni-di-vendita`, `/privacy`, `/cookie`, `/accessibilita`. Un campo vuoto (Marco senza
P. IVA) non lascia etichette orfane («P. IVA » senza numero).

## Fatto quando — controlli eseguibili
Tutti con Host `demo.localhost` salvo dove detto; il seed del demo è caricato.

- [ ] Piè di pagina su ogni pagina della vetrina: `GET /`, `/catalogo`, `/catalogo/bouquet`,
      `/prodotti/bouquet-di-stagione`, `/carrello`, `/checkout` (con un carrello non vuoto),
      le quattro pagine nuove, la pagina d'esito `/ordine/esito?session_id=…` e la pagina di stato
      `/ordine/{token}` di un ordine pagato → 200, l'HTML contiene un solo `<footer>` con
      «Il Glicine di Lucia Ferri», «Via dei Fossi 12», «55100 Lucca (LU)», «P. IVA IT00000000000»,
      «ciao@glicine.example», «+39 0583 000000» e i cinque `href` delle pagine legali.
      (La home oggi non usa il layout: va portata sul layout.)
- [ ] `GET /informazioni-venditore` → 200, contiene ragione sociale, sede, email, PEC
      «glicine@pec.example», P. IVA, «REA LU-000000», telefono; la sede è «55100 Lucca (LU)».
- [ ] `GET /privacy` → 200, contiene: titolare «Il Glicine di Lucia Ferri» con email; dati di
      cliente e **destinatario**; basi giuridiche (contratto, obbligo di legge) con riferimento a
      «art. 6» GDPR; conservazione «10 anni» (dati contabili) e «12 mesi» (dati di consegna);
      i fornitori «Stripe» e il servizio email; i diritti (accesso, rettifica, cancellazione,
      opposizione); il reclamo al «Garante per la protezione dei dati personali».
- [ ] `GET /cookie` → 200, dice che il sito usa solo cookie tecnici, nomina i cookie `cart` e
      quello CSRF con il nome reale (il costruttore lo legge da `Set-Cookie`; atteso `csrf-token`,
      default di Quarkus REST CSRF) e che non ci sono cookie di profilazione né di terze parti.
      Nessuna pagina GET della vetrina (anche con carrello pieno) manda un `Set-Cookie` con nome
      diverso da `cart` e `csrf-token`, e la pagina `/cookie` nomina ogni cookie visto.
- [ ] `GET /accessibilita` → 200, contiene «WCAG 2.1», il livello «AA», lo stato di conformità,
      la frase «Stato di conformità», l'email del negozio per segnalazioni e una data `gg/mm/aaaa`.
- [ ] Isolamento: con Host `fiori-di-marco.localhost` le quattro pagine e il piè di pagina mostrano
      «Fiori di Marco Srl» e **non** contengono «Glicine», «IT00000000000», «glicine@pec.example»,
      «(LU)»; il piè di pagina di Marco non contiene «P. IVA» (campo vuoto). Ogni pagina si chiede
      prima al demo e subito dopo a Marco.
- [ ] Host della piattaforma `localhost`: le quattro pagine → 404.
- [ ] I testi seguono il dato del negozio, non sono scritti nel template: cambiando via JDBC la
      `pec` del demo in «altra@pec.example», `/informazioni-venditore` la mostra.
- [ ] Seed: con `pec` e `rea` del demo messi a `null` via JDBC, rieseguire `demo-shop.sql` li
      riporta a «glicine@pec.example» e «LU-000000», e la pagina venditore li mostra.
- [ ] `./mvnw -B -ntp verify` verde (test esistenti compresi, ~418).
- [ ] Dopo il deploy (comando per l'umano, non JUnit):
      `curl -s https://demo.<LAB_BASE_DOMAIN>/privacy | grep -c "Garante"` → ≥ 1.

## Fuori perimetro
- Da `traguardi.json → fuori_perimetro`: back-office del negozio; consegna CEMETERY; capacità per
  fascia; impianto consensi e GDPR oltre la pagina privacy (niente `consent_record`, caselle di
  consenso, soft spam); SPF e DKIM completi.
- Pagine «Informazioni» di §2.2 (Chi siamo, Consegne e zone, Contatti con mappa, FAQ) e richiesta
  preventivo: sono vetrina, non legali.
- Testi legali versionati in `legal_document`: privacy, cookie e accessibilità sono template fissi
  riempiti con i dati del negozio, senza versione salvata negli ordini.
- La pagina `/condizioni-di-vendita` resta XHTML autonomo senza piè di pagina (DECISIONI n. 41:
  stesso template del PDF); mostra già i dati del venditore. Il suo contenuto non cambia.
- Pagine d'errore (`errors/*.html`) e landing della piattaforma: senza piè di pagina del negozio.
- Modulo di recesso per beni non deperibili, prezzo più basso a 30 giorni (R4), stile e Tailwind.
- Nuove migrazioni Flyway: non servono (colonne `pec` e `rea` già in V1).

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|
| `Giro018LegalFooterAcceptanceTest` | seed demo · GET delle pagine della vetrina · `<footer>` con dati e cinque link |
| `Giro018LegalPagesAcceptanceTest` | seed demo · GET delle quattro pagine · contenuti attesi; cambio `pec` via JDBC visibile |
| `Giro018LegalIsolationAcceptanceTest` | demo + Marco · GET con Host Marco e con `localhost` · solo dati di Marco; 404 sulla piattaforma |

## Porte
- Doppio senso previste: mappare `pec`, `rea` e `province` in `Tenant` e in `ShopProfile.Info`;
  il modo di dare i dati del venditore al layout (per esempio un bean `@Named @RequestScoped` letto
  con `{inject:…}`, oppure un parametro in ogni `@CheckedTemplate`): lo sceglie il costruttore;
  una risorsa `LegalPagesResource` in `storefront`; home sul layout; righe nuove nel seed demo.
- Senso unico: nessuna (niente dipendenze, niente migrazioni, dati inventati, nessun dato reale).

## Rischi di concorrenza
Nessuno stato condiviso nuovo: pagine di sola lettura. Rischio vero: un bean del piè di pagina con
ambito sbagliato (`@ApplicationScoped` con cache) mostrerebbe i dati di un negozio su un altro; lo
prova il test d'isolamento che alterna demo e Marco nella stessa JVM.

## Budget
Righe di produzione ≤ 400 (template compresi); file ≤ 12; una sessione.

## Domande per il ricercatore (al massimo 3)
1. Contenuto minimo dell'informativa art. 13 GDPR per un piccolo e-commerce di fiori senza account:
   voci obbligatorie, come trattare i dati del destinatario (art. 14?), e se Stripe va indicato come
   responsabile o titolare autonomo.
2. Informativa cookie con soli cookie tecnici (linee guida Garante 10/6/2021): cosa deve dire e se
   basta una pagina raggiungibile dal piè di pagina, senza banner.
3. Informazioni sul venditore obbligatorie (art. 7 d.lgs. 70/2003, art. 2250 c.c.) e struttura
   minima di una dichiarazione di accessibilità per un privato (modello AgID o EAA).

## Critica ricevuta e cosa cambia
Accolto (3 punti + 2 note sui test):
1. `province` non arrivava al piè di pagina: si mappa con `pec` e `rea`; i test cercano
   «55100 Lucca (LU)» nel demo e l'assenza di «(LU)» in Marco.
2. `pec` e `rea` anche nel `do update set` dell'upsert; test che li azzera e riesegue il seed.
3. La privacy promette anonimizzazione dopo 12 mesi senza un job: il testo resta (SPEC §12),
   scadenza «job di anonimizzazione entro 10/2027» in «Idee per dopo» e in PROGRESS.
4. Il controllo «assenza di Accetta» era debole: sostituito dal controllo dei `Set-Cookie` reali
   delle pagine GET (solo `cart` e `csrf-token`), legato alla pagina `/cookie`.
5. Pagina d'esito e pagina di stato ordine aggiunte alla lista del piè di pagina.

Precisato da me: per l'accessibilità il test cerca la frase «Stato di conformità» e una data
`gg/mm/aaaa` (prima «lo stato» era vago). Scartato: niente.

Rosso: 11 test su 12 falliscono con asserzioni (404 sulle pagine nuove, nessun `<footer>`,
`tenant.pec` null dopo il seed). Verde già ora: `thePlatformHostHasNoLegalPagesOfAShop`
(guardia d'isolamento: deve restare 404 anche dopo).

## Idee per dopo
- **Scadenza 10/2027:** job che anonimizza i dati di consegna dopo 12 mesi, come promette la privacy.
- Pagine «Chi siamo», «Consegne e zone», «Contatti», «FAQ» (§2.2).
- Testi legali in `legal_document` versionati, modificabili dal back-office (R2).
- Piè di pagina anche nella pagina delle condizioni, con un layout XHTML compatibile col PDF.
