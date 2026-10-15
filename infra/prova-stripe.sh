#!/usr/bin/env bash
# Black-box Stripe test-mode proof on the demo shop. Creates a real PENDING_PAYMENT order: run by hand.
#   prova-stripe.sh <demo-domain>                      -> prints the Stripe Checkout URL
#   prova-stripe.sh esito <demo-domain> <checkout-url> -> prints the order number once paid
# Exit: 0 ok, 1 check failed, 2 usage.
set -euo pipefail

usage() { echo "usage: $0 <demo-domain> | $0 esito <demo-domain> <checkout-url>" >&2; exit 2; }
fail() { echo "$*" >&2; exit 1; }

esito() {
  local base="https://$1" sid i page
  sid="${2#*/pay/}"; sid="${sid%%[#?/]*}"
  case "$sid" in cs_test_*) ;; *) fail "not a Checkout test URL: no cs_test_ session" ;; esac
  for i in $(seq 1 15); do
    page=$(curl -s -m 10 -w '\n%{http_code}' "$base/ordine/esito?session_id=$sid") || page=$'\n000'
    case "${page##*$'\n'}" in
      404) fail "sessione sconosciuta: $sid" ;;
      200) ;;
      *) page='' ;;
    esac
    case "$page" in
      *"Pagamento ricevuto"*)
        printf '%s\n' "$page" | grep -Eo '20[0-9]{2}-[0-9]{4}' | head -n 1
        return 0 ;;
      *"non è stato completato"*) fail "sessione scaduta o pagamento non completato" ;;
    esac
    [ "$i" -lt 15 ] && sleep 2
  done
  fail "pagamento ancora in attesa dopo 15 richieste"
}

[ "$#" -ge 1 ] || usage
if [ "$1" = esito ]; then
  [ "$#" -eq 3 ] || usage
  esito "$2" "$3"
  exit 0
fi
[ "$#" -eq 1 ] || usage

base="https://$1"
jar=$(mktemp "${TMPDIR:-/tmp}/prova-stripe.XXXXXX")
trap 'rm -f "$jar"' EXIT
get() { curl -sf -m 15 -b "$jar" -c "$jar" "$base$1"; }

# Prints the variantId of the form in the row containing $2 (page $1 on stdin-less call).
variant_of() {
  printf '%s\n' "$1" | awk -v row="$2" '
    /<li/ { buf = "" } { buf = buf "\n" $0 }
    /<\/li>/ && index(buf, row) { print buf; exit }' |
    sed -n 's/.*name="variantId" value="\([0-9]*\)".*/\1/p' | head -n 1
}
add() { # <product> <row> <quantity>
  local page vid csrf
  page=$(get "/prodotti/$1") || fail "product page $1 unreachable"
  vid=$(variant_of "$page" "$2")
  [ -n "$vid" ] || fail "variant «$2» not found on $1"
  csrf=$(printf '%s' "$page" | sed -n 's/.*name="csrf-token" value="\([^"]*\)".*/\1/p' | head -n 1)
  curl -s -o /dev/null -m 15 -b "$jar" -c "$jar" --data-urlencode "csrf-token=$csrf" \
    --data-urlencode "variantId=$vid" --data-urlencode "quantity=$3" "$base/carrello/aggiungi"
}
add rose-rosse "12 rose" 1
add orchidea-bianca "Vaso 12 cm" 2

summary=$(get /checkout) || fail "checkout summary unreachable"
case "$summary" in *"Ordina e paga"*) ;; *) fail "checkout spento: no «Ordina e paga» in the summary" ;; esac
case "$summary" in *"104,80 €"*) ;; *) fail "unexpected total: expected 104,80 €" ;; esac
min=$(printf '%s' "$summary" | sed -n 's/.*name="pickupDate"[^>]* min="\([^"]*\)".*/\1/p' | head -n 1)
[ -n "$min" ] || fail "pickup date min not found"
csrf=$(printf '%s' "$summary" | sed -n 's/.*name="csrf-token" value="\([^"]*\)".*/\1/p' | head -n 1)

headers=$(curl -s -o /dev/null -D - -m 30 -b "$jar" -c "$jar" --data-urlencode "csrf-token=$csrf" \
  --data-urlencode "customerName=Giulia Bianchi" \
  --data-urlencode "customerEmail=giulia.bianchi@example.com" \
  --data-urlencode "customerPhone=+39 333 000 1234" \
  --data-urlencode "pickupDate=$min" "$base/checkout") || fail "POST /checkout failed"
status=$(printf '%s' "$headers" | head -n 1 | tr -d '\r' | awk '{print $2}')
location=$(printf '%s' "$headers" | tr -d '\r' | sed -n 's/^[Ll]ocation: *//p' | head -n 1)
[ "$status" = 303 ] || fail "POST /checkout answered $status, expected 303"
case "$location" in
  https://checkout.stripe.com/*) printf '%s\n' "$location" ;;
  *) fail "redirect is not Stripe Checkout" ;;
esac
