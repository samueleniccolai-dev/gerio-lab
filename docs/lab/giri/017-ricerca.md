# Ricerca del giro 017 — zone di consegna e orario limite (T7)

Fonti consultate il 16/10/2026: 8 (3 aperte, 5 solo dai risultati di ricerca).

## Come lo fanno gli altri
- Shopify (consegna locale): zone con nome e lista di CAP, ordine minimo e prezzo per zona (0 = gratis). Se l'indirizzo non è ammesso, il cliente non vede l'opzione. Non è descritto un messaggio personalizzato né un orario limite. [Shopify Help]
- Interflora: consegna in giornata con 9 € di supplemento, ordine entro le 18:30 da lunedì a sabato e entro le 12:30 la domenica. [Il Post 2021]
- Frida's: consegna gratis sopra 50 €, altrimenti 4,90 €. In giornata entro le 14:00 con 9 €. [Il Post 2021]
- Colvin e Italia in fiore: spedizione gratuita, 24 ore (Colvin: 48 ore in Sardegna, Sicilia e Calabria). Nessun ordine minimo dichiarato. [Il Post 2021]
- Floranext, Hana, Square, Fioricittà, Floracom: nessuna pagina pubblica trovata con zone, minimo e messaggio «fuori zona». È una lacuna, non un'assenza accertata.

## Casi reali e documentazione
- WhatsApp click-to-chat: `https://wa.me/<numero>`, numero in formato internazionale senza `+`, senza zeri iniziali né altri simboli. Il testo precompilato è facoltativo: `?text=` con URL-encoding. [WhatsApp FAQ] — pagina arrivata troncata: le regole fini sono da ricontrollare.
- Un comune può avere più CAP (Milano 20121-20162, Brescia 25121-25136). [comuni-italiani.it]
- Un CAP può coprire più comuni (25080: Nuvolento, Nuvolera, Mazzano). [GEOmedia; qualcherisposta.it]
- I CAP cambiano con le fusioni di comuni (Cadrezzate e Osmate, CAP 21062 nel 2019). Fonte ufficiale: «Cerca CAP» di Poste Italiane. [VareseNews; 01net]

## Cosa prendiamo per questa fetta
1. «Prima CAP esatto, poi comune». Zona = nome + lista di CAP, come Shopify.
2. CAP validato solo come 5 cifre; nessun elenco nazionale in tabella. Le zone le decide il negozio.
3. Minimo e costo per zona, calcolati sul server, con un messaggio chiaro invece della sparizione muta dell'opzione.
4. Orario limite per negozio, «prima delle ore X» (prassi dei grandi: 12:30, 14:00, 18:30).
5. Link fuori zona `https://wa.me/<numero senza +>`; testo precompilato facoltativo e fisso. Nel link mai dati del cliente (privacy, finisce nei log).

## Cosa evitiamo
- Nascondere «Consegna» senza spiegare.
- Un elenco CAP-comune nazionale nel prodotto.
- Nome, indirizzo o telefono del cliente nel link WhatsApp.

## Idee per dopo
- Supplemento per consegna in giornata; consegna gratis sopra soglia; CAP con prefisso o intervallo.

## Fonti
- https://faq.whatsapp.com/5913398998672934 (aperta, troncata)
- https://help.shopify.com/en/manual/fulfillment/setup/delivery-methods/local-delivery
- https://www.ilpost.it/2021/02/10/siti-fiori-domicilio-colvin-interflora/ (2021: prezzi datati)
- https://www.comuni-italiani.it/cap/multicap.html (solo risultati)
- https://ojs.mediageo.it/index.php/GEOmedia/article/download/1313/1201/5636 (solo risultati)
- https://qualcherisposta.it/come-sono-stati-assegnati-i-cap (solo risultati)
- https://www.varesenews.it/2019/06/le-poste-cap-del-comune-cadrezzate-osmate/827563/ (solo risultati)
- https://www.01net.it/?p=108861 (solo risultati)

Nota per `docs/verifiche.md`: il punto wa.me va registrato, ma con controllo umano del formato prima di darlo per verificato.
