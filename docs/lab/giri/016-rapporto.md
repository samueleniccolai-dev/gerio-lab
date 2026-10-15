---
giro: 016
traguardo: T6
esito: pr-aperta
inizio: 2026-10-15T14:02:30Z
fine: 2026-10-15T17:58:00Z
pr: 20
modello_orchestratore: sonnet
modello_costruttore: sonnet
modello_revisore: sonnet
ricerca_fonti: 7
critico_da_correggere: 3
critico_accolti: 3
revisore_bloccanti: 0
revisore_dubbi: 2
dubbi_corretti: 0
fuori_perimetro: 0
mutazioni_provate: 3
mutazioni_prese: 2
verify_rossi_locali: 1
test_totali: 385
test_delta: 11
decisioni_registro: 3
decisioni_ratifica: 1
porte_senso_unico: 1
riparazioni: 0
difetto_di:
---
# Rapporto del giro 016 — condizioni di vendita e PDF allegato (T6, parte 3)

> **PR:** #20 · **Branch:** `claude/giro-016-t6` · **Traguardo:** T6 — in corso
> **Domande che bloccano:** nessuna

## Per te, in 10 righe
- Nuova pagina pubblica `/condizioni-di-vendita` e `/condizioni-di-vendita.pdf` per ogni negozio (dati legali dal profilo, versione `2026-10-12`); 404 su Host sconosciuto o della piattaforma.
- Ogni email di conferma porta in allegato il PDF della versione **accettata dall'ordine**; versione ignota = nessuna email, riga ritentata.
- Dipendenza nuova: `openhtmltopdf-pdfbox` 1.1.97 (tua risposta A, LGPL 2.1+). **La PR va in revisione umana** (`pom.xml`).
- Stesso template XHTML per pagina e PDF; date del PDF fisse (non dipendono dall'orologio).
- Il PDF è in cache per (negozio, versione): non segue modifiche al profilo (ratifica n. 39; oggi nessun percorso le fa).
- Il testo di recesso cita art. 59, comma 1, lett. d, Codice del consumo; l'art. 49 non è stato letto su Normattiva: da controllare. Non è parere legale.
- Java 25 con openhtmltopdf: provato dal `verify` (385 test verdi in copia isolata).
- T6 resta `in-corso`: manca il provider SMTP UE (azione tua); finché manca, le conferme restano `PENDING`.
- In locale `verify` aveva 1 rosso (`caddyAdapts…`): Docker Hub 429 sul pull di `caddy`, ambiente e non codice; il revisore l'ha trovato verde.
- Prossimo: giro 017 su T7.

## Cosa funziona ora — con la prova
- 11 test `Giro016*` verdi; 385 test totali, `verify` verde (revisore, copia isolata); test di accettazione intatti.
- Concorrenza: 24 richieste miste demo/Marco in parallelo = ognuna col proprio negozio; escaping Qute verificato con un indirizzo malevolo.
- Mutazioni: 2 su 3 prese (la terza è equivalente: byte deterministici).

## Cosa non funziona ancora
- Nessuna email reale in produzione (provider mancante); SPF/DKIM fuori perimetro.
- Cache del PDF non invalidata se il profilo cambia (serve con il back-office).
- Il PDF si genera dentro la transazione `REQUIRES_NEW` del composer alla prima volta per negozio (solo lettura, più lunga); si può scaldare la cache fuori.
- Art. 49 Codice del consumo non verificato.

## Verdetto del revisore
- **BLOCCANTI:** 0 · **DUBBI:** 2 (cache e transazione, sopra) · **PERIMETRO:** solo `pom.xml`, previsto.
- VERDETTO finale: {"sha": "838dfc4275676afd7d803d3cabf4a9ca2e98a6bd", "bloccanti": 0, "dubbi": 2, "fuori_perimetro": 0, "mutazioni_provate": 3, "mutazioni_prese": 2, "test_totali": 385}

## Ricerca e critica
Ricerca: 7 fonti, `docs/lab/giri/016-ricerca.md`. Critica: 3 punti DA CORREGGERE, tutti accolti, più i facoltativi.

## Prossima fetta
Giro 017: T7 (zone di consegna e orario limite).
