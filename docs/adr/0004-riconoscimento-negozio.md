# ADR-0004 — Riconoscere il negozio dall'host

- **Data:** 2026-09-27
- **Stato:** accettata
- **Collegamenti:** PROGETTO.md §5.1–5.4, §11.4, App. B.1 · ADR-0003 · `docs/verifiche.md` (27/09/2026) ·
  `docs/scostamenti-4-10.md` (righe 10–13) · voce del diario 002 · PR della milestone R0-tenant

## Contesto
Ogni richiesta deve sapere per quale negozio lavora **prima** di toccare il database: Hibernate
fissa il negozio quando apre la sessione (discriminatore, `@TenantId`), e da lì non lo cambia più.
PROGETTO §5.2 descrive il meccanismo a grandi linee. Restavano da decidere: come riconoscere i
sottodomini, di quali header fidarsi dietro Caddy, come cercare il dominio senza una sessione già
legata a un negozio, e cosa fare con redirect, domini non verificati e negozi non attivi.

Verificato leggendo il bytecode di Quarkus 3.33.3.3 e Hibernate 7.2.25, poi confermato dai test:
- senza request context Quarkus passa a Hibernate un negozio `null` **senza chiamare** il nostro
  resolver; Hibernate lo rifiuta, ma solo se esiste almeno un'entità `@TenantId`;
- `isRoot() == true` disattiva il filtro per negozio;
- Hibernate chiama il resolver **anche per costruire** una `StatelessSession` con un negozio
  esplicito (la prima verifica, sbagliata, diceva il contrario: `verifiche.md`).

## Decisione
1. **Il sottodominio si ricava dallo slug**, non si salva in `tenant_domain`. La tabella contiene
   solo domini personalizzati. Dominio principale = dominio personalizzato principale **verificato**,
   altrimenti `slug.<base>`.
2. **Il negozio si decide solo dall'header `Host`.** Caddy lo inoltra già invariato.
   `enable-forwarded-host` resta spento, anche se App. B.1 lo attiva. `X-Forwarded-For` e
   `X-Forwarded-Proto` valgono solo dai proxy fidati (`172.16.0.0/12`).
3. **Ordine del filtro** (`TenantRequestFilter`):
   1. host assente o non valido (`HostNormalizer`) → 404;
   2. il contesto diventa **piattaforma**;
   3. host = dominio base → resta piattaforma;
   4. `x.<base>` → negozio per slug; nomi più profondi → 404;
   5. altro host → dominio personalizzato **verificato**;
   6. nessun negozio → 404 generico; negozio `DRAFT`/`SUSPENDED` → 503, **prima** di ogni redirect;
   7. host diverso dal principale → 301 (GET/HEAD) o 308 (altri metodi), `Cache-Control: max-age=3600`,
      percorso e query ancora codificati, host preso sempre dal DB;
   8. `switchTo(negozio)`.
4. **Nessun redirect `www.x` → `x`.** Le due forme servono la stessa vetrina: il negozio può aver
   configurato solo il CNAME `www` (§5.4).
5. **Le righe di `tenant_domain` sotto il dominio base sono ignorate** sia nella ricerca sia come
   dominio principale: quei nomi appartengono agli slug.
6. **Ricerca con `StatelessSession`** e negozio esplicito `__platform__`. Siccome Hibernate chiama
   comunque il resolver, **ogni richiesta parte come piattaforma** e passa al negozio solo alla fine.
   Se per errore la sessione della richiesta si aprisse prima, resterebbe fissata sulla piattaforma
   e vedrebbe zero righe dei negozi: nessun dato di un negozio esposto a un altro.
7. **`TenantContext`**: `set` una sola volta; `switchTo` solo da piattaforma a negozio e una sola
   volta (usato dal filtro e, in R1, dal webhook Stripe); `require` lancia se il contesto è vuoto.
8. **`PlatformTenantResolver`** non ridefinisce mai `isRoot`. `getDefaultTenantId` lancia
   un'eccezione: nessun negozio di ripiego.
9. **Dominio base obbligatorio in produzione** (`PlatformConfig`, senza valore predefinito) e già in
   forma normalizzata (`PlatformDomain`, controllato all'avvio).
10. **Slug riservati** (`V2`): `www`, `admin`, `api`, `app`, `internal`, `mail`, `static`, `status`,
    `img`, `staging`, `dev`, `test`, `docs`, `help`, `blog`, `cdn`, `assets`, `mta-sts`. Vietato anche
    `--` in posizione 3-4 (RFC 5891): niente slug punycode `xn--…`, che il browser mostrerebbe come
    nomi Unicode simili a quelli di un altro negozio.
11. **Thread:** il filtro gira sul thread dell'endpoint e fa query bloccanti. Gli endpoint devono
    essere bloccanti (non restituire `Uni`). Il filtro lo controlla in testa e rifiuta **su ogni
    host**, anche sulla piattaforma: altrimenti un endpoint `Uni` funzionerebbe in dev su `localhost`
    e si romperebbe solo sugli host dei negozi. Il filtro non è *pre-matching*: quei filtri girano
    sempre sull'event loop.

## Regole per il codice futuro
- **Job, worker, outbox:** appena esiste un'entità `@TenantId` in `src/main`, **ogni** sessione senza
  request context fallisce, anche sulle sole tabelle globali. Ogni job usa `@ActivateRequestContext`
  e imposta il `TenantContext` prima di usare il database.
- **`Tenant` e `TenantDomain` non sono filtrati.** In una richiesta di negozio si leggono solo con
  `tenantContext.require().id()`, mai con un id preso dalla richiesta, e con un test A/B.
- **SQL nativo non filtrato** (§5.3): solo in servizi dedicati, con `tenant_id` esplicito.
- **Niente update HQL di massa sulle entità con audit** (`Entity.update("...")`): saltano listener e
  `@Version`. Se servono davvero, `update versioned ...` e `updatedAt` dal `Clock`.
- **R1, login del back-office:** l'autenticazione HTTP di Quarkus gira *prima* di questo filtro, con
  il `TenantContext` ancora vuoto. Se il fornitore di identità legge gli utenti con Panache,
  `require()` fallisce; se imposta lui la piattaforma, il `set()` del filtro fallisce. Deve quindi
  leggere le tabelle globali (`app_user`, `membership`) con una propria `StatelessSession` e il tenant
  esplicito, come `TenantDirectory`. Il controllo «l'utente appartiene al negozio» va dopo il filtro,
  quando il negozio è noto.
- **Rotte fuori da JAX-RS** (route Vert.x, health, file statici) non passano dal filtro: non devono
  toccare le tabelle dei negozi, oppure impostano il contesto da sole.
- **Ogni entità su una tabella di negozio estende `TenantScopedEntity`**: `SchemaRulesTest` lo
  controlla sul metamodello di Hibernate. Lo schema da solo non basta: un'entità con `tenant_id` come
  colonna normale non sarebbe filtrata.
- **`/internal/tls/allowed` (§11.4):** Caddy lo chiama con l'`Host` `app:8080`, che il filtro
  risponderebbe con 404. Va escluso esplicitamente quando arriva.
- **Ogni tabella di negozio** segue ADR-0003 punto 8: `SchemaRulesTest` lo controlla.

## Alternative considerate
| Alternativa | Pro | Contro | Perché scartata |
|---|---|---|---|
| Sottodominio salvato in `tenant_domain` | Una sola query | Dati legati all'ambiente (il dominio base cambia fra dev e prod); righe da riscrivere se cambia lo slug; «almeno un principale» richiede un trigger | Più stato da tenere allineato |
| `enable-forwarded-host` (App. B.1) | Configurazione del documento | Chiunque sulla rete Docker del proxy può scegliere il negozio (dimostrato da una prova di mutazione su `TrustedProxyTest`) | Non serve: Caddy passa l'`Host` |
| Ricerca con JDBC semplice | Nessuna interazione col resolver | Duplica la mappatura delle entità | Scelta dello sviluppatore; resta l'alternativa se il passaggio piattaforma → negozio creasse problemi |
| Ricerca con la sessione della richiesta (Panache) | Il più semplice | La sessione resta fissata sul negozio sbagliato per tutta la richiesta | Rompe §5.2 |
| 301 senza scadenza, anche `www` → radice | SEO classica | Il browser lo memorizza per sempre; manda al nulla i negozi con solo il CNAME `www` | Rischio per i clienti |
| Cache in memoria dei domini subito (§5.2) | Una query in meno per richiesta | `quarkus-cache` è una dipendenza nuova; volumi R0 trascurabili | Rimandata a R1 |

## Conseguenze
- Una o due query per ogni richiesta finché non c'è la cache.
- I test di isolamento (`TenantIsolationTest`, `EndpointIsolationTest`, `TenantRequestFilterTest`,
  `SchemaRulesTest`) sono il modello per ogni nuovo endpoint e repository.
- Da provare nella PR del deploy, con il database disponibile: `PLATFORM_BASE_DOMAIN=Piattaforma.it.`
  deve fermare l'avvio con l'`IllegalArgumentException` di `PlatformDomain` (oggi provato solo
  l'avvio **senza** la variabile).
- Al deploy: controllare che la rete Compose di Caddy stia dentro `172.16.0.0/12`, e che la porta 8080
  dell'app **non** sia pubblicata sull'host: tutto il modello si fida dell'`Host` che passa da Caddy.
- Cache dei domini (R1): chiave = host normalizzato; va invalidata quando cambiano stato o dominio
  principale di un negozio, altrimenti un negozio sospeso resta visibile.
- Cambiare il dominio principale di un negozio è sicuro dopo un'ora (scadenza del 301).
- Scelta consapevole: 503 per i negozi `DRAFT`/`SUSPENDED` e 404 per gli host sconosciuti permettono
  di scoprire quali slug esistono, bozze comprese. Rischio basso (lo slug non è un segreto; Shopify
  si comporta allo stesso modo). Se diventasse un problema: `DRAFT` → 404.
- Tre endpoint esistono solo nei test (`TenantEchoResource`, `ProbeItemResource`,
  `NonBlockingEchoResource`): non sono nel jar, quindi quei test non si possono spostare su
  `@QuarkusIntegrationTest`.

## Da riportare in PROGETTO.md §17.2?
Sì: «Negozio dall'`Host` (mai da `X-Forwarded-Host`); sottodominio ricavato dallo slug; solo domini
verificati; slug riservati». Le correzioni puntuali sono in `scostamenti-4-10.md`.
