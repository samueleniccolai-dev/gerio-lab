---
giro: 018
traguardo: T8
esito: pr-aperta
inizio: 2026-10-16T10:12:04Z
fine: 2026-10-16T10:37:52Z
pr: 23
modello_orchestratore: sonnet
modello_costruttore: sonnet
modello_revisore: sonnet
ricerca_fonti: 6
critico_da_correggere: 3
critico_accolti: 3
revisore_bloccanti: 0
revisore_dubbi: 2
dubbi_corretti: 0
fuori_perimetro: 0
mutazioni_provate: 3
mutazioni_prese: 3
verify_rossi_locali: 0
test_totali: 430
test_delta: 12
decisioni_registro: 6
decisioni_ratifica: 0
porte_senso_unico: 0
riparazioni: 0
difetto_di:
---
# Rapporto del giro 018 — pagine legali minime e piè di pagina (T8)

> **PR:** #23 · **Branch:** `claude/giro-018-t8` · **Traguardo:** T8 — passato
> **Domande che bloccano:** nessuna

## Per te, in 10 righe
- Quattro pagine nuove sul negozio: `/informazioni-venditore`, `/privacy`, `/cookie`, `/accessibilita`.
- Piè di pagina su ogni pagina della vetrina (anche checkout, esito, stato ordine): ragione sociale, sede, P. IVA, contatti, 5 link legali.
- Nessun banner cookie: ci sono solo cookie tecnici (`cart`, `csrf-token`), come dicono le linee guida del Garante.
- Dati del demo inventati (PEC, REA); il seed li aggiorna anche sulla riga già in produzione.
- Nessuna migrazione, nessuna dipendenza, nessuna ratifica: il guard non dovrebbe chiedere revisione umana.
- Da fare tu: far leggere i testi a un consulente (art. 14 par. 5 GDPR, esenzione EAA, Stripe/Connect): sono [DA VERIFICARE].
- Scadenza: la privacy promette anonimizzazione dopo 12 mesi, ma nessun job la fa: va fatto entro 10/2027.
- Dopo il deploy: aprire `https://demo.<LAB_BASE_DOMAIN>/privacy` e controllare il piè di pagina.
- T6 resta in attesa delle tue azioni (Brevo, `consegna.yml`).

## Cosa funziona ora — con la prova
- Piè di pagina su tutte le pagine elencate (prova: `Giro018LegalFooterAcceptanceTest`, 4 test).
- Contenuti delle 4 pagine, PEC cambiata via JDBC visibile, seed rieseguito che ripristina PEC e REA (`Giro018LegalPagesAcceptanceTest`, 6 test).
- Isolamento: Marco vede solo i suoi dati; host piattaforma → 404 (`Giro018LegalIsolationAcceptanceTest`, 2 test).
- Sonda del revisore: 400 GET in parallelo su due negozi, 0 mescolanze.

## Cosa non funziona ancora
- I testi legali non sono validati da un professionista; art. 14 par. 5, esenzione EAA e Stripe/Connect da verificare.
- Nessun job di anonimizzazione (promessa di 12 mesi nella privacy).
- Piè di pagina assente su `/condizioni-di-vendita` (resta autonomo, decisione n. 41) e sulle pagine d'errore.
- Pagine informative di SPEC §2.2 (Chi siamo, Consegne, Contatti, FAQ) fuori perimetro.
- Data della dichiarazione di accessibilità scritta a mano (16/10/2026).

## Verdetto del revisore
- **BLOCCANTI:** nessuno.
- **DUBBI:** 2 (non corretti): il layout senza negozio definito lancia eccezione (coerente con la regola); data costante.
- **PERIMETRO:** nessuna aggiunta oltre il piano.
- **CONCORRENZA:** sonda 400 richieste su 32 thread, bad=0.
- **MUTAZIONI:** provate 3, prese 3.
- Pulizia: copia `/tmp/revisione-018` rimossa; albero pulito.

## Ricerca e critica
Ricerca (6 fonti): niente banner con soli cookie tecnici; Stripe responsabile e titolare autonomo; accessibilità volontaria. Critica: 3 punti accolti (provincia mappata; `pec`/`rea` nell'upsert; scadenza anonimizzazione), più due note sui test (Set-Cookie reali, pagine esito/stato).
Nota del costruttore: un solo commit invece di piccoli passi TDD.

## Prossima fetta
Tutti i traguardi tranne T6 sono passati: attende le azioni umane su Brevo; poi chiusura dell'esperimento (§13).
