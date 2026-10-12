---
giro: 009
traguardo: T4
fase: chiusura
inizio: 2026-10-12T06:40:21Z
ricerca: no
accettazione: n/a
ripreso: no
---
# Piano del giro 009 — prova del carrello in produzione (T4)

## Fetta e traguardo
Chiusura di T4. Il codice è su `main` (PR #11, giro 008) e il deploy di 4343195 è verde. Manca la
prova registrata sul demo in produzione. Giro di soli documenti: nessuna riga di produzione e
nessun test nuovo (stesso schema del giro 007 per T3).

## Prova (scenario del piano 008, passi 1–6 sul demo, dati inventati)
Dominio: `demo.gerio-lab.samueleniccolai.com` (`$LAB_BASE_DOMAIN` non è definita in sessione).
Eseguita con `curl` e un cookie jar, token CSRF preso dalla scheda prodotto.

| Passo | Azione | Atteso |
|---|---|---|
| 0 | `GET /carrello` senza cookie | 200, «Il tuo carrello è vuoto.» |
| 1 | aggiungi Medio (variante 2) × 2 | 303; 70,00 € |
| 2 | aggiungi orchidea Vaso 12 cm (variante 8) × 1 | 303; totale 99,90 € |
| 3 | aggiungi Medio × 1 | 303; totale 134,90 € (una riga) |
| 4 | orchidea a 2 | 303; totale 164,80 € |
| 5 | rimuovi Medio | 303; totale 59,80 € |
| 6 | orchidea a 0 | 303; carrello vuoto |
| 7 | `POST /carrello/aggiungi` con `variantId=2`, `quantity=0`; poi senza token CSRF | 400 entrambi |

## Fatto quando — controlli eseguibili
- [ ] `gh run view 38213443526 --json conclusion,headSha` → `success`, `4343195f…`; job `deploy` `success`
- [ ] i passi 0–7 sopra danno gli esiti attesi (output nel rapporto, con data e ora UTC)
- [ ] `python3 scripts/lab/traguardo.py T4 --stato passato --giro 009 --pr 12`, poi `--mostra` → T4 `passato`, PR 12
- [ ] `PROGRESS.md` aggiornato: prossimo traguardo T5

## Fuori perimetro
- Qualsiasi codice, template, migrazione, test o `infra/`.
- Webhook Stripe e filtro CSRF (T5); FK di V3/V4; avviso Ubuntu 26 (`.github/` è tua).
- Da `traguardi.json → fuori_perimetro`: back-office; CEMETERY; capacità per fascia; consensi/GDPR; SPF/DKIM.

## Test di accettazione
Nessuno (`accettazione: n/a`): giro di soli documenti. La prova in JUnit esiste (`Giro008*AcceptanceTest`).

## Porte
Doppio senso: nessuna. Senso unico: nessuna. Effetto in produzione: un carrello di prova su dati
finti, svuotato al passo 6 (la riga `cart` resta, come per ogni carrello).

## Rischi di concorrenza
Nessuno: nessun codice cambia.

## Budget
Righe di produzione = 0; file ≤ 5 (piano, rapporto, `traguardi.json`, `PROGRESS.md`, metriche).

## Domande per il ricercatore
Nessuna (`ricerca: no`).

## Critica ricevuta e cosa cambia
Accolto (2 da correggere, 1 facoltativo):
1. Passo 7 precisato: `/carrello/aggiungi` con `quantity=0` (su `/righe/{id}` lo 0 toglie la riga o dà 404).
2. Gli id delle varianti si leggono dagli `<input>` delle schede prodotto (bouquet-di-stagione: Medio = 2; orchidea-bianca: Vaso 12 cm = 8) e gli id riga da `GET /carrello`; il rapporto li riporta con POST 303 e totale letto dopo ogni passo.
3. (facoltativo) cookie `cart` controllato con `curl -i`; il numero PR (12) è confermato da `pr.py stato`.
