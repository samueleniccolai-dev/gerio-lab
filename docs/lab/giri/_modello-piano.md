---
giro: NNN
traguardo: Tn
fase: piano          # piano → critica → costruzione → revisione → chiusura | domanda | bloccato
inizio: AAAA-MM-GGTHH:MM:SSZ
ricerca: sì
accettazione:        # SHA del commit test(acceptance)
ripreso: no
---
# Piano del giro NNN — <fetta>

## Fetta e traguardo
Quale traguardo (o quale parte) e perché proprio adesso, in una riga.

## Fatto quando — controlli eseguibili
- [ ] `comando`, URL o test → risultato atteso

## Fuori perimetro
Cosa NON si fa in questo giro, anche se sembra utile.

## Test di accettazione (scritti prima, intoccabili dal costruttore)
| File | Dato … quando … allora … |
|---|---|

## Porte
- Doppio senso previste: …
- Senso unico: nessuna / **DOMANDA:** …

## Rischi di concorrenza
Stato condiviso, transazioni, doppio invio, idempotenza: cosa può andare storto e come si prova.

## Budget
Righe di produzione ≤ …; file ≤ …; deve stare in una sessione.

## Domande per il ricercatore (al massimo 3)

## Critica ricevuta e cosa cambia
(dopo il critico) Accolto: … · Scartato, con motivo: …
