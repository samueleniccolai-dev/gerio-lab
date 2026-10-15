---
giro: 014
traguardo: T6
esito: pr-aperta
inizio: 2026-10-15T12:18:01Z
fine: 2026-10-15T12:41:58Z
pr: 18
modello_orchestratore: sonnet
modello_costruttore: sonnet
modello_revisore: sonnet
ricerca_fonti: 5
critico_da_correggere: 4
critico_accolti: 4
revisore_bloccanti: 0
revisore_dubbi: 2
dubbi_corretti: 0
fuori_perimetro: 0
mutazioni_provate: 3
mutazioni_prese: 3
verify_rossi_locali: 2
test_totali: 361
test_delta: 6
decisioni_registro: 1
decisioni_ratifica: 1
porte_senso_unico: 0
riparazioni: 0
difetto_di:
---
# Rapporto del giro 014 — pagina di stato dell'ordine (T6, parte 1)

> **PR:** #18 · **Branch:** `claude/giro-014-t6` · **Traguardo:** T6 — in corso
> **Domande che bloccano:** nessuna

## Per te, in 10 righe
- Nuova pagina `/ordine/{token}`: stato, articoli, totale, data (fuso di Roma) e contatti del negozio.
- Nessun dato personale del cliente in pagina (ratifica n. 32).
- 404 identico per token malformato, sconosciuto, di un altro negozio o sull'Host della piattaforma.
- Intestazioni `no-store`, `no-referrer`, `noindex`.
- La pagina di esito, se l'ordine è pagato, mostra «Segui il tuo ordine».
- Nessuna migrazione, nessuna dipendenza, niente in `infra/`: il guard dice `auto-merge` (nessuna revisione umana).
- Resta di T6 l'email di conferma: servono `quarkus-mailer`, un generatore PDF e un SMTP UE → **porte a senso unico del giro 015**.
- Dopo il deploy: `curl -i https://demo.<LAB_BASE_DOMAIN>/ordine/<43 × A>` deve dare 404 «Pagina non trovata».
- Nota: Caddy imposta già `Referrer-Policy strict-origin-when-cross-origin`; in produzione potrebbero arrivare due intestazioni (vale l'ultima valida, `no-referrer`). Da sistemare in un giro su `infra/`.
- Il costruttore ha implementato senza vedere prima i test rossi (protocollo non seguito alla lettera).

## Cosa funziona ora — con la prova
- 6 test di accettazione `Giro014*` verdi; 361 test totali, `verify` verde in copia isolata (revisore).
- 3 mutazioni su 3 prese (fuso orario, ordine righe, filtro per negozio).
- Concorrenza: 40 GET paralleli insieme al webhook, nessun errore.

## Cosa non funziona ancora
- Email di conferma, PDF delle condizioni: giro 015.
- «Ritiro in negozio» fisso nel template: sbaglierà con la consegna a domicilio (T7).
- La pagina di esito promette ancora «Riceverai un'email», che arriva solo dal giro 015.
- Il token non scade; nessun limite di frequenza (idee per dopo).
- Controllo `curl` dopo il deploy non ancora eseguito.

## Verdetto del revisore
- **BLOCCANTI:** 0 · **DUBBI:** 2 (testo fisso «Ritiro in negozio»; controllo negozio ridondante) · **PERIMETRO:** nessuna aggiunta.
- VERDETTO finale: {"sha": "b7bf115ece0748db36a07db56a31faf5bd3dc887", "bloccanti": 0, "dubbi": 2, "fuori_perimetro": 0, "mutazioni_provate": 3, "mutazioni_prese": 3, "test_totali": 361}

## Ricerca e critica
Ricerca: 5 fonti (W3C Capability URLs, Shopify, Floranext…), `docs/lab/giri/014-ricerca.md`. Critica: 4 punti, tutti accolti.

## Prossima fetta
Giro 015: email di conferma (porte a senso unico: mailer, PDF, SMTP).
