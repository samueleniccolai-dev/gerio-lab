# ADR-0003 — Entità base a strati, cancellazioni ed eventi verso l'esterno

- **Data:** 2026-09-27
- **Stato:** accettata
- **Collegamenti:** PROGETTO.md §4.3, §5.1, §6, §10.4, §12.4 · ADR-0002 · PR #1 ·
  `docs/scostamenti-4-10.md` (riga 8)

## Contesto
Lo sviluppatore ha proposto:
- una classe base per tutte le entità, con `id` numerico e 6 colonne comuni (`created_date`,
  `created_by`, `updated_date`, `updated_by`, `deleted_by`, `active`);
- solo cancellazioni logiche;
- un punto unico per le logiche trasversali, per esempio chiamare Kafka in `onUpdate`.

V1 non è ancora su `main`, quindi le colonne si possono decidere adesso senza una V2.

## Decisione
1. **Classi base a strati, divise per responsabilità e non per tipo di id.** Le classi Java arrivano
   al passo 3, con la prima entità (`TenantDomain`) e i loro test.
   ```
   PanacheEntityBase
   └── AuditedEntity          createdAt, updatedAt (dal Clock iniettato), @Version
       ├── EntityBase         @Id Long, GenerationType.IDENTITY
       │   └── TenantScopedEntity   @TenantId String tenantId
       └── Tenant             @Id String (UUID): eccezione dichiarata
   ```
   - `EntityBase` estende `PanacheEntityBase`, **non** `PanacheEntity`. La seconda genera l'id con una
     sequence, ma le tabelle usano `generated always as identity`: Postgres rifiuterebbe gli inserimenti.
   - `TenantScopedEntity` rende impossibile dimenticare `@TenantId` su un'entità di un negozio.
     Da verificare con un test che `@TenantId` funzioni su un `@MappedSuperclass`: la guida Quarkus
     non lo cita.
   - I timestamp li imposta un entity listener con il `Clock` iniettato (Quarkus supporta `@Inject`
     nei listener: quarkus.io/guides/hibernate-orm, 27/09/2026). Non si usa `@CreationTimestamp`,
     che legge l'orologio della JVM.
2. **Colonne su ogni tabella modificabile:** `created_at`, `updated_at`, `version`. In V1 le hanno
   già `tenant` e `tenant_domain`.
3. **Fuori dalla gerarchia:**
   - `cart` (id testuale segreto);
   - `stripe_event` (id di Stripe, solo inserimento);
   - `membership`, `tenant_counter` (chiavi composte);
   - `order_event`, `consent_record`, `price_history`, `audit_log` (sola aggiunta: niente
     `updated_at` né `version`; la prova di un consenso deve restare immutabile).
4. **Niente cancellazione logica come regola generale.** Si decide tabella per tabella:
   - il catalogo si archivia con il campo `active` che c'è già (significa «in vendita», non «cancellato»);
   - i dati personali si cancellano o si anonimizzano come richiede §12.4;
   - dove servisse davvero, si usa `@SoftDelete` di Hibernate 7.2, non una colonna gestita a mano.
5. **«Chi ha fatto cosa»** va in `audit_log` (§10.4), con storia completa. `created_by` compare
   solo dove il documento lo prevede (`customer_order`, R2).
6. **Effetti verso l'esterno** (email, Telegram, un domani Kafka) **mai nel ciclo di vita delle
   entità**. Il servizio scrive un evento di business in `outbox_message` nella stessa transazione;
   `OutboxWorker` lo consegna e riprova (transactional outbox, già previsto in §4.3 e §6).
7. **Aggancio su tutte le entità:** se un giorno servirà, si userà un listener predefinito in
   `META-INF/orm.xml` (`<persistence-unit-defaults>`). È JPA standard e non richiede ereditarietà.
   Quarkus supporta `orm.xml`, ma il listener predefinito va dimostrato con un test prima di
   farci affidamento.

8. **Regole per ogni tabella di un negozio** (pratica comune per il multi-tenant su PostgreSQL,
   aggiunta dopo la revisione del 27/09/2026):
   - la tabella dichiara `unique (tenant_id, id)`;
   - le tabelle figlie la referenziano con una **chiave esterna composta**
     `foreign key (tenant_id, parent_id) references parent (tenant_id, id)`. Così il database stesso
     impedisce che una riga del negozio A punti a una riga del negozio B; il filtro di Hibernate
     da solo non lo impedisce;
   - gli indici delle tabelle di un negozio iniziano con `tenant_id`, per esempio
     `(tenant_id, created_at)`: ogni query è filtrata per negozio.
9. **Row Level Security (R3, §5.3):** funziona solo se l'app **non** si collega con il ruolo che
   possiede le tabelle, oppure con `force row level security`. Oggi Flyway gira all'avvio con lo stesso
   ruolo dell'app. Prima di R3 servono due ruoli: uno per le migrazioni e uno, senza privilegi di
   proprietà, per l'app.

## Alternative considerate
| Alternativa | Pro | Contro | Perché scartata |
|---|---|---|---|
| `tenant.id` come `bigint identity` | 8 byte, leggibile | Il `TenantResolver` di Quarkus restituisce una `String` (§5.1, problema noto); id indovinabili; collisioni fra ambienti | Rischio sull'isolamento |
| Due basi parallele per tipo di id (`Long` / `String`) | Simmetria | Colonne di audit duplicate; la base testuale servirebbe solo a `tenant`, e i tre id testuali si generano in tre modi diversi | Astrazione prematura |
| `EntityBase<ID>` con tipo generico | Una sola classe | Generici ovunque per due eccezioni | Complessità senza guadagno |
| Base unica per tutte le entità, senza id | «Un punto solo» | Classe vuota che obbliga a una gerarchia; le tabelle in sola aggiunta erediterebbero colonne sbagliate | Il listener in `orm.xml` fa lo stesso senza ereditarietà |
| `created_by`/`updated_by` ovunque | Traccia immediata | Solo l'ultimo autore, nessuna storia; `null` per clienti anonimi, webhook e job; la chiave esterna verso `app_user` blocca la cancellazione dei dipendenti | Lo fa meglio `audit_log` |
| Cancellazione logica ovunque (`active`, `deleted_by`) | Si può annullare | Viola la conservazione di §12.4 (GDPR art. 5 e 17); filtro da ricordare in ogni query, SQL nativo compreso; vincoli di unicità da riscrivere; nessuna cascata; `active` significa già «in vendita» | Rischio legale e di bug |
| Chiamare Kafka da `@PreUpdate`/`@PostUpdate` | Immediato | Doppia scrittura: eventi per modifiche annullate o modifiche senza evento; il flush non è il commit; effetti nascosti; Kafka non è nello stack | L'outbox risolve senza doppia scrittura |

## Conseguenze
- Ogni nuova tabella modificabile riceve `created_at`, `updated_at`, `version`. La sua entità estende
  `EntityBase` o `TenantScopedEntity`, oppure dichiara perché no.
- Due dipendenti che modificano insieme la stessa riga: il secondo riceve un errore di
  conflitto (`OptimisticLockException`) invece di sovrascrivere in silenzio. Il back-office dovrà
  mostrarlo in modo comprensibile.
- `GenerationType.IDENTITY` disattiva gli inserimenti a gruppi (batch) di Hibernate: irrilevante a
  questi volumi.
- Le proposte sul modello dati di §6 vanno portate nella sessione del prodotto.

## Da riportare in PROGETTO.md §17.2?
Sì: «Entità base a strati con created_at/updated_at/version; niente cancellazione logica generale;
eventi esterni solo via outbox».
