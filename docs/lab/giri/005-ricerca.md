## Come lo fanno gli altri
- FiorinCittà (home e liste): prezzi nel formato «da €44,00» (simbolo PRIMA della cifra, virgola, due decimali), cioè «da» = prezzo minimo. Nella parte letta non c'erano varianti, «IVA inclusa» né il recesso (le schede prodotto non si sono viste). Fonte: https://www.fiorincitta.it
- Un fiorista di Modena (modulo d'ordine, non un negozio vero): bouquet in tre formati piccolo/medio/grande a 40/60/100 €, con l'avviso «fiori ed elementi possono variare rispetto alle foto». Nessuna dicitura IVA. Fonte: risultato di ricerca, https://form.jotform.com/250313493005345 (pagina non aperta, solo estratto).
- Non ho trovato 3-5 esempi reali con «IVA inclusa» sulla scheda. Non l'ho verificato per Floracom, Shopify, Floranext o Hana: limite dichiarato, non indovino.
- Un reclamo su Altroconsumo lamenta che al momento dell'acquisto non era indicato da nessuna parte che il prodotto fosse escluso dal recesso. Quindi l'avviso va messo in evidenza, prima dell'acquisto. Fonte: https://www.altroconsumo.it/reclamare/bacheca-dei-reclami/recesso-non-accordato-e-scarsa/CPTIT01877386-48 (solo estratto).

## Casi reali e documentazione
- Recesso: l'art. 59 del Codice del consumo esclude i beni che rischiano di deteriorarsi o scadere rapidamente. L'estratto di un reclamo lo cita come lett. d). Il testo ufficiale NON l'ho letto. Il PDF del D.Lgs. 206/2005 era illeggibile, brocardi ha dato 404 e EUR-Lex è stato troncato prima dell'art. 16. Fonti: https://www.altroconsumo.it/reclamare/bacheca-dei-reclami/panificio-senza-glutine-on-lin/e416628122cc7b5dab (secondaria). Una ricerca ha dato «lett. e)»: la lettera resta da controllare su Normattiva.
- Non esiste una definizione di legge o giurisprudenza di «bene deperibile»: conta la natura del bene. Un blog indica come riferimento i 14 giorni del recesso (fonte secondaria). Fonte: https://it.shopify.com/blog/diritto-di-recesso-siti-ecommerce
- Quarkus 3.33 Flyway: `locations` è un elenco separato da virgole, scandito in modo ricorsivo. Default `db/migration`. Senza prefisso o con `classpath:` accetta SQL e Java. Fonte: https://quarkus.io/version/3.33/guides/flyway
- Quarkus 3.33: la migrazione ripetibile ha prefisso `R` e nome `R__Descrizione.sql`. `validate-on-migrate` ha default `true`, `out-of-order` default `false`, `migrate-at-start` default `false`. La guida non mostra esempi con `%prod.` o `%dev.` per `locations`; mostra solo `%dev.quarkus.flyway.clean-at-start`. Fonte: stessa guida.
- Il prefisso di profilo vale per qualunque chiave di configurazione Quarkus. È una regola generale che non ho verificato in questa ricerca: va provata con un test (vedi sotto).

## Cosa prendiamo per questa fetta
1. Prezzi nel formato `25,00 €` (cifra, spazio, simbolo) e «da 25,00 €» in catalogo, come da piano. FiorinCittà usa «da» con il simbolo prima; per un sito italiano il simbolo dopo è lecito, ma teniamo un solo formato ovunque (una funzione sola, importi in centesimi).
2. «IVA inclusa» sulla scheda accanto al prezzo: è quanto richiede il piano. Non l'ho visto su un concorrente, quindi non è un uso verificato; non c'è motivo di toglierlo.
3. Recesso: la nostra frase («Fiori e piante sono deperibili: il diritto di recesso è escluso.») va bene come avviso breve. Il Codice non impone una formula, ma l'esclusione deve essere chiara prima dell'acquisto (reclamo Altroconsumo). Suggerimento: aggiungere il riferimento «(art. 59 Codice del consumo)», se la lettera viene verificata. La formula completa va poi nelle condizioni di vendita (T8). La norma va verificata su Normattiva prima di scriverla nel codice; si registra in `docs/verifiche.md`.
4. Flyway R__ in `db/demo` solo in alcuni profili: i punti da coprire con un test sono tre. (a) Le ripetibili girano dopo tutte le versionate, quindi `db/demo` può contare sulle tabelle di `V3`. (b) Con `validate-on-migrate=true` Flyway ricalcola il checksum: se si modifica il seme, la ripetibile viene rieseguita, e va bene; se un profilo toglie `db/demo` dopo che il seme è stato applicato, Flyway di norma segnala la migrazione come «missing». Per questo nel piano la rimozione del seme va fatta con una migrazione successiva, non togliendo la location. Questo comportamento è conoscenza generale di Flyway, non l'ho verificato in questa ricerca. (c) Il seme deve essere idempotente (upsert), come già previsto.
5. Per il seme scrivere `%prod.quarkus.flyway.locations` e `%dev.quarkus.flyway.locations` per intero (`db/migration,db/demo`), non solo `db/demo`: la proprietà sostituisce il valore, non lo estende. Questa è una deduzione da «elenco di locations», da confermare con il `grep` del piano.

## Cosa evitiamo
- Citare l'articolo con una lettera non verificata: se la lettera è sbagliata, il testo è peggio che senza citazione.
- Togliere `db/demo` da un profilo dopo che il seme è già stato applicato in quel database (rischio «missing migration»; usare `ignore-migration-patterns` solo come ripiego, è documentato nella guida).
- Dare per scontato che i concorrenti italiani usino «IVA inclusa» sulla scheda: non l'ho trovato.

## Idee per dopo
- Un confronto vero di 3-5 schede (Floracom, Interflora, FiorinCittà, Shopify demo) con screenshot, dentro un giro sul checkout.
- Formula completa del recesso e delle informazioni precontrattuali nelle condizioni di vendita (T8).
- Variante «da» solo quando ci sono prezzi diversi; con una sola variante mostrare il prezzo semplice (non nel piano).

## Fonti (consultate l'11/10/2026)
- https://www.fiorincitta.it
- https://quarkus.io/version/3.33/guides/flyway
- https://it.shopify.com/blog/diritto-di-recesso-siti-ecommerce (estratto di ricerca)
- https://www.altroconsumo.it/reclamare/bacheca-dei-reclami/recesso-non-accordato-e-scarsa/CPTIT01877386-48 (estratto)
- https://www.altroconsumo.it/reclamare/bacheca-dei-reclami/panificio-senza-glutine-on-lin/e416628122cc7b5dab (estratto)
- https://form.jotform.com/250313493005345 (estratto)
- Non aperte o non leggibili: PDF D.Lgs. 206/2005 del Consiglio regionale Calabria, brocardi.it (404), EUR-Lex 32011L0083 (troncato prima dell'art. 16).
