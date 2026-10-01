#!/usr/bin/env bash
# Starts the JVM stub server (tools/jvmtest) and checks the HTTP layer and the `hands` client.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT=18765
TOKEN=abcdefghijklmnopqrstuvwxyz012345
export HANDS_URL="http://127.0.0.1:$PORT" HANDS_TOKEN="$TOKEN"
HANDS="$ROOT/client/hands"
fails=0
check() { # name, command..., expects success and stdout matching $EXPECT
  local name=$1; shift
  local out; out=$("$@" 2>&1); local rc=$?
  if [ $rc -eq "${RC:-0}" ] && [[ $out == *"$EXPECT"* ]]; then echo "PASS  $name"
  else echo "FAIL  $name (rc=$rc)"; echo "$out" | head -5; fails=$((fails+1)); fi
}

"$ROOT/tools/jvmtest/build/install/scrobot-jvmtest/bin/scrobot-jvmtest" "$TOKEN" "$PORT" 2>/tmp/stub.log &
STUB=$!
trap 'kill $STUB 2>/dev/null' EXIT
for _ in $(seq 50); do curl -s "$HANDS_URL/ping" >/dev/null && break; sleep 0.2; done

EXPECT='"ok":true' check "ping" "$HANDS" ping
EXPECT='"version":"1.0"' check "ping version" "$HANDS" ping
RC=1 EXPECT='"ok":false' check "no token -> ok:false" env HANDS_TOKEN=wrong "$HANDS" ping
EXPECT='401' check "no token -> 401" curl -s -o /dev/null -w '%{http_code}' "$HANDS_URL/ping"
EXPECT='401' check "wrong token -> 401" curl -s -o /dev/null -w '%{http_code}' -H 'X-Token: nope' "$HANDS_URL/ping"
EXPECT='"error":"missing or wrong X-Token header"' check "401 body" curl -s "$HANDS_URL/tap"
EXPECT='404' check "unknown endpoint -> 404" curl -s -o /dev/null -w '%{http_code}' -H "X-Token: $TOKEN" "$HANDS_URL/nope"
EXPECT='"received":{"x":540,"y":1200}' check "tap" "$HANDS" tap 540 1200
EXPECT='"x2":100' check "swipe" "$HANDS" swipe 500 1800 100 300 250
EXPECT='"ms":800' check "longpress default" "$HANDS" longpress 10 20
EXPECT='"text":"Ask Meta AI or Search"' check "click text" "$HANDS" click "Ask Meta AI or Search"
EXPECT='"id":"com.whatsapp:id/send"' check "click-id" "$HANDS" click-id com.whatsapp:id/send
EXPECT='"text":"Mahmoud Samy - Me (You)"' check "click with parens" "$HANDS" click "Mahmoud Samy - Me (You)"
EXPECT='"text":"Hello 👋 مرحبا"' check "type arabic+emoji" "$HANDS" type "Hello 👋 مرحبا"
EXPECT='"text":"line1\nline2 \"q\" back\\slash\ttab"' check "type newline/quote/backslash/tab" "$HANDS" type $'line1\nline2 "q" back\\slash\ttab'
EXPECT='"field":"Message"' check "type into field" "$HANDS" type "hi" "Message"
EXPECT='"append":true' check "append" "$HANDS" append "more"
EXPECT='"package":"com.whatsapp"' check "HANDS_PKG" env HANDS_PKG=com.whatsapp "$HANDS" type "x"
EXPECT='"key":"back"' check "key" "$HANDS" key back
EXPECT='"package":"com.whatsapp"' check "open package" "$HANDS" open com.whatsapp
EXPECT='"uri":"https://example.com/a?b=1&c=2"' check "open url" "$HANDS" open "https://example.com/a?b=1&c=2"
EXPECT='"timeout":3000' check "wait" "$HANDS" wait "Chats" 3000
EXPECT='"gone":true' check "wait-gone" "$HANDS" wait-gone "Loading"
EXPECT='"text":"نص"' check "clip set" "$HANDS" clip "نص"
EXPECT='[WhatsApp]' check "screen text" "$HANDS" screen
EXPECT='مرحبا 👋  [Claude]' check "screen text utf-8" "$HANDS" screen
EXPECT='"all":"1"' check "screen-json all" "$HANDS" screen-json all
EXPECT='saved' check "shot" "$HANDS" shot /tmp/hands-test.png
EXPECT='PNG' check "shot bytes" head -c 4 /tmp/hands-test.png
RC=1 EXPECT='"ok":false' check "endpoint error -> exit 1" "$HANDS" raw /fail
EXPECT='"received":{}' check "raw default body" "$HANDS" raw /key
RC=2 EXPECT='not a number' check "bad number" "$HANDS" tap 1 x
# Big body: curl sends "Expect: 100-continue" above 1 KB.
BIG=$(head -c 100000 /dev/zero | tr '\0' 'a')
EXPECT='"ok":true' check "100 KB body (Expect: 100-continue)" "$HANDS" type "$BIG"
EXPECT='400' check "invalid JSON -> 400" curl -s -o /dev/null -w '%{http_code}' -H "X-Token: $TOKEN" -d '{nope' "$HANDS_URL/tap"
# The server must only listen on loopback.
if command -v ss >/dev/null; then
  # Accept 127.0.0.1:PORT and the IPv4-mapped form [::ffff:127.0.0.1]:PORT; anything else is a wildcard bind.
  addrs=$(ss -ltn | awk -v p=":$PORT" '$4 ~ p"$" {print $4}')
  if [ -n "$addrs" ] && ! printf '%s\n' "$addrs" | grep -qvE "^(\[::ffff:)?127\.0\.0\.1\]?:$PORT\$"; then
    echo "PASS  bound to 127.0.0.1 only"
  else
    echo "FAIL  bound to 127.0.0.1 only: ${addrs:-not listening}"; fails=$((fails+1))
  fi
fi
echo "failures: $fails"
exit $fails
