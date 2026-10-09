#!/bin/bash
# Local test of worker.js: fake Discord + wrangler dev, then a set of requests.
set -u
cd "$(dirname "$0")/.."
printf 'DISCORD_WEBHOOK=http://127.0.0.1:8799/hook\nIP_SALT=test-salt\n' > .dev.vars
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
sleep 1; echo "--- Discord got $(grep -c 'DISCORD POST' /tmp/fake-discord.log) posts:"; grep 'DISCORD POST' /tmp/fake-discord.log | head -1
