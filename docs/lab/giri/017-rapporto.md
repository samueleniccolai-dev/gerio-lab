---
giro: 017
traguardo: T7
esito: pr-aperta
inizio: 2026-10-16T09:31:01Z
fine: 2026-10-16T10:06:00Z
pr: 21
modello_orchestratore: sonnet
modello_costruttore: sonnet
modello_revisore: sonnet
ricerca_fonti: 8
critico_da_correggere: 5
critico_accolti: 5
revisore_bloccanti: 0
revisore_dubbi: 4
dubbi_corretti: 0
fuori_perimetro: 0
mutazioni_provate: 4
mutazioni_prese: 4
verify_rossi_locali: 1
test_totali: 418
test_delta: 27
decisioni_registro: 2
decisioni_ratifica: 1
porte_senso_unico: 0
riparazioni: 0
difetto_di:
---
# Rapporto del giro 017 — zone di consegna e orario limite (T7)

> **PR:** #21 · **Branch:** `claude/giro-017-t7` · **Traguardo:** T7 — passato
> **Domande che bloccano:** nessuna

## Per te, in 10 righe
- Al checkout il cliente sceglie «Ritiro in negozio» o «Consegna a domicilio».
- La consegna vale solo nelle zone del negozio (CAP, poi comune); costo e minimo li calcola il server.
- Fuori zona: messaggio chiaro e link WhatsApp del negozio (senza dati del cliente).
- Ordine minimo sul totale dei prodotti, consegna esclusa.
- Orario limite per negozio (demo: 12:00 Europe/Rome): dopo, niente ordini per oggi.
- Il costo di consegna è nell'IVA salvata e in una riga Stripe «Consegna — zona».
- Migrazione `V8` solo additiva (`delivery_zone`, colonne nullable).
- Ratifica n. 46: IVA della consegna all'aliquota del bene principale; con aliquote miste serve un commercialista.
- Da fare tu: controllare il formato `wa.me` (pagina WhatsApp arrivata troncata); dopo il deploy provare una consegna a Lucca.
- Il guard dice `auto-merge`: nessun motivo di revisione umana.

## Cosa funziona ora — con la prova
- Zone, minimo, fuori zona, orario limite, isolamento tra negozi, pagina di stato ed email (prova: 27 test `Giro017*AcceptanceTest` verdi).
- 4 mutazioni su 4 prese; 10 POST paralleli identici → 1 ordine e 1 sessione Stripe.

## Cosa non funziona ancora
- Il `verify` locale ha 2 rossi ambientali (`Giro006DemoHost…`: immagine `caddy:2.11.7` non scaricabile); passano con l'immagine presente (verificato dal revisore).
- Cambiando i dati di consegna si crea un nuovo ordine e il vecchio resta `PENDING_PAYMENT` con la sessione aperta (doppio pagamento possibile, già vero per nome ed email).
- Il nome della riga Stripe segue il nome attuale della zona.
- IVA con aliquote miste non verificata.
- T6 resta in-corso: mancano le righe in `consegna.yml` e `GERIO_MAIL_ENABLED=true`.

## Verdetto del revisore
- **BLOCCANTI:** nessuno.
- **DUBBI:** 4 (ordini orfani, nome zona, IVA mista, 23 rossi su `main` invece di 25), nessuno corretto.
- **PERIMETRO:** nessuna aggiunta.
- **CONCORRENZA:** 10 POST identici → 1 ordine; fee cambiata durante i POST → totale coerente.
- **MUTAZIONI:** provate 4, prese 4.

## Ricerca e critica
Presi: zona = nome + CAP (Shopify), orario limite per negozio (Interflora, Frida's). Scartati: elenco CAP nazionale, opzione nascosta senza spiegazione. Critica: 5 punti su 5 accolti.

## Prossima fetta
T8: pagine legali minime e rifiniture demo.
