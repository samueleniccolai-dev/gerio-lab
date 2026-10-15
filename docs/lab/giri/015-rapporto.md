---
giro: 015
traguardo: T6
esito: pr-aperta
inizio: 2026-10-15T12:51:40Z
fine: 2026-10-15T13:26:26Z
pr: 19
modello_orchestratore: sonnet
modello_costruttore: sonnet
modello_revisore: sonnet
ricerca_fonti: 3
critico_da_correggere: 5
critico_accolti: 5
revisore_bloccanti: 0
revisore_dubbi: 2
dubbi_corretti: 0
fuori_perimetro: 0
mutazioni_provate: 3
mutazioni_prese: 2
verify_rossi_locali: 0
test_totali: 374
test_delta: 13
decisioni_registro: 1
decisioni_ratifica: 2
porte_senso_unico: 1
riparazioni: 0
difetto_di:
---
# Rapporto del giro 015 — email di conferma (T6, parte 2)

> **PR:** #19 · **Branch:** `claude/giro-015-t6` · **Traguardo:** T6 — in corso
> **Domande che bloccano:** nessuna

## Per te, in 10 righe
- Dopo il pagamento il webhook scrive una riga in `outbox_message` (solo id, stessa transazione del `PAID`).
- Un worker (timer Vert.x, 30 s) la invia: oggetto, mittente, Reply-To, venditore con P. IVA, righe, totale, ritiro, recesso escluso (art. 59), garanzia legale, link `/ordine/<token>`.
- Ritentativi 1, 2, 4… minuti, all'ottavo errore `FAILED`. Un evento doppio = una sola email.
- Dipendenza nuova: solo `quarkus-mailer` (la tua risposta A). Migrazione `V7` additiva. **La PR va in revisione umana** (`pom.xml`).
- Non ho aggiunto `quarkus-scheduler` (non approvato): timer Vert.x (ratifica n. 35).
- **In produzione l'invio è spento** (`GERIO_MAIL_ENABLED=false`): il mock logga i dati del cliente, quindi le righe restano `PENDING` (ratifica n. 36). Serve il provider SMTP UE (Brevo, Scaleway, Mailjet: [DA VERIFICARE]) con account, DNS, segreti.
- Resta il PDF delle condizioni (giro dedicato, altra porta).
- La pagina di esito dice «Riceverai un'email»: vero solo col provider acceso.
- Il revisore consiglia di citare il rischio doppio invio con lease di 5 minuti e SMTP lento (vedi sotto).
- Il testo del recesso va controllato sul testo vigente di artt. 49 e 59 (modificati di recente); non è parere legale.

## Cosa funziona ora — con la prova
- 11 test `Giro015*` verdi; 374 test totali, `verify` verde in copia isolata (revisore).
- Mutazioni: 2 su 3 prese. Concorrenza: 8 webhook in parallelo = 1 riga; 4 `drainOnce()` = 1 email; 8 thread su `take()` = nessun duplicato.
- Escaping Qute verificato con un nome cliente malevolo.

## Cosa non funziona ancora
- Nessuna email reale in produzione (provider mancante); PDF assente; SPF/DKIM fuori perimetro.
- Nessun test sul timer Vert.x (spento nei test).
- Nessun test che copra `for update skip locked` tra repliche (mutazione M1 non presa; la sonda del revisore sì).
- Possibile doppio invio: crash dopo l'invio e prima di `SENT`, o passata oltre il lease di 5 minuti con SMTP lento (al più una volta in più, accettato). Idea: timeout SMTP ≪ lease.
- Un test di accettazione del piano è stato adattato dal pianificatore (cliente «Giulia Bianchi» al posto di «Marta Rossi»), prima della costruzione.

## Verdetto del revisore
- **BLOCCANTI:** 0 · **DUBBI:** 2 (sopra) · **PERIMETRO:** nessuna aggiunta.
- VERDETTO finale: {"sha": "0be2519b6a24e7ab92768067d1b28db8642cea94", "bloccanti": 0, "dubbi": 2, "fuori_perimetro": 0, "mutazioni_provate": 3, "mutazioni_prese": 2, "test_totali": 374}

## Ricerca e critica
Ricerca: 3 fonti aperte, `docs/lab/giri/015-ricerca.md`. Critica: 5 punti DA CORREGGERE più 3 facoltativi, tutti accolti (il 5 con variante: interruttore `app.mail.enabled`).

## Prossima fetta
Giro 016: scegliere tra PDF delle condizioni (porta: `openhtmltopdf` o PDF statico) e T7. Il provider SMTP dipende da te.
