#!/bin/bash
# Local test of worker.js: fake Discord + wrangler dev, then a set of requests.
set -u
cd "$(dirname "$0")/.."
printf 'DISCORD_WEBHOOK=http://127.0.0.1:8799/hook\nSOUND_WEBHOOK=http://127.0.0.1:8799/sound\nIP_SALT=test-salt\n' > .dev.vars
node test/fake-discord.mjs > /tmp/fake-discord.log 2>&1 & D=$!
npx --yes wrangler@4 dev --port 8798 --persist-to /tmp/relay-kv > /tmp/wrangler.log 2>&1 & W=$!
trap 'kill $D $W 2>/dev/null; rm -f .dev.vars' EXIT
for i in $(seq 60); do curl -s -o /dev/null http://127.0.0.1:8798/ && break; sleep 1; done
R='{"app_version":"0.1.0","manufacturer":"Moorechip","brand":"qti","model":"Retroid Pocket Nova","device":"kalama","product":"kalama","soc_manufacturer":"QTI","soc_model":"QCS8550","board_platform":"kalama","android":"13 (API 33)","fingerprint":"qti/kalama/kalama:13/TKQ1.231222.001/eng.RPN.20260722.081626:user/release-keys","sd_block":"mmcblk1","sd_type":"SD","root":"none"}'
post() { curl -s -w " http=%{http_code}\n" -X POST http://127.0.0.1:8798/report -H "User-Agent: ${UA:-PB-OS-Installer/0.1.0}" -H 'Content-Type: application/json' -d "$1"; }
echo "1 valid report:        $(post "$R")"
echo "2 same again:          $(post "$R")"
echo "3 no app user agent:   $(UA=curl post "$R")"
echo "4 mention attempt:     $(post "${R/Moorechip/@everyone}")"
echo "5 link attempt:        $(post "${R/Moorechip/<https:\/\/x.y>}")"
echo "6 extra field:         $(post "${R%\}},\"x\":\"y\"}")"
echo "7 missing field:       $(post "${R/\"root\":\"none\"/\"rot\":\"none\"}")"
echo "8 GET:                 $(curl -s -w " http=%{http_code}" http://127.0.0.1:8798/report)"
echo "9 2nd device:          $(post "${R/Nova/6}")"
echo "10 3rd device:         $(post "${R/Nova/Mini}")"
echo "11 4th device (limit): $(post "${R/Nova/Flip}")"
# Documentation addresses only (RFC 7042 MAC, RFC 5737 IP), built in parts.
DOC=00:00:5e:00:53; BT=AA_BB_CC_DD_EE
S=$'=== PB-OS Sound Check: sound-gone\ntone 1 on pbos_top_screen: NOT heard\nconnected to AP MAC: '"$DOC:01"$'\nName: bluez_output.'"${BT}_FF"$'.1\naddr 198.51.100.7 fe80:0:0:0:1:2:3:4\nalpha-v0.5.4.25'
spost() { curl -s -w " http=%{http_code}\n" -X POST http://127.0.0.1:8798/sound-report -H "User-Agent: ${UA:-PB-OS-SoundCheck/1}" \
  -H 'Content-Type: text/plain' -H "X-PBOS-Device: ${DEV:-AYN Thor}" -H 'X-PBOS-Version: alpha-v0.5.4.25' -H "X-PBOS-Mode: ${MODE:-sound-gone}" --data-binary "$1"; }
echo "12 sound report:       $(spost "$S")"
echo "13 same again:         $(spost "$S")"
echo "14 wrong user agent:   $(UA=curl spost "$S x")"
echo "15 bad mode:           $(MODE=other spost "$S y")"
echo "16 mention in device:  $(DEV=@everyone spost "$S z")"
echo "17 too large:          $(spost "$(head -c 600000 /dev/zero | tr '\0' a)")"
sleep 1; echo "--- sound posts:"; grep -A12 'DISCORD POST' /tmp/fake-discord.log | grep -E 'AP MAC|bluez|addr|alpha-v|Sound report' | head
sleep 1; echo "--- Discord got $(grep -c 'DISCORD POST' /tmp/fake-discord.log) posts:"; grep 'DISCORD POST' /tmp/fake-discord.log | head -1
