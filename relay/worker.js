// PB-OS Installer device-report relay (Cloudflare Worker).
//
// The app POSTs a device report here; this Worker checks it and posts it to
// a Discord channel. The Discord webhook lives only in this Worker's secrets,
// never in the app, and the Worker accepts nothing but a plain device report:
// fixed fields, short values from a safe character set, no mentions or links.
//
// Bindings (see README.md): KV (KV namespace), secrets DISCORD_WEBHOOK and IP_SALT.

// The report fields, in the order they are posted. Every one is required.
const FIELDS = [
  'app_version', 'manufacturer', 'brand', 'model', 'device', 'product',
  'soc_manufacturer', 'soc_model', 'board_platform', 'android', 'fingerprint',
  'sd_block', 'sd_type', 'root',
];
// Letters, digits and the punctuation build strings use. No backticks, @, <, >,
// so nothing can break out of the code block or ping anyone.
const VALUE = /^[A-Za-z0-9 ._:\/()+,=-]{0,120}$/;

const PER_IP_PER_DAY = 3;      // one person, any number of devices
const ALL_PER_DAY = 100;       // whole relay, stops a flood from many addresses
const DUPLICATE_DAYS = 7;      // the same report again within a week is dropped

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== 'POST' || url.pathname !== '/report') return reply(404, 'not_found');
    if (!(request.headers.get('User-Agent') || '').startsWith('PB-OS-Installer/')) return reply(403, 'forbidden');
    if (Number(request.headers.get('Content-Length') || 0) > 4096) return reply(413, 'too_large');

    let report;
    try {
      report = await request.json();
    } catch {
      return reply(400, 'invalid');
    }
    if (!valid(report)) return reply(400, 'invalid');

    const day = new Date().toISOString().slice(0, 10);
    // Only a salted hash of the address is kept, and only for two days.
    const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
    const ipKey = `ip:${day}:${await sha256(env.IP_SALT + ip)}`;
    const allKey = `all:${day}`;
    const seenKey = `seen:${await sha256(FIELDS.map((f) => report[f]).join('\n'))}`;

    if (await env.KV.get(seenKey)) return reply(200, 'duplicate');
    const ipCount = Number(await env.KV.get(ipKey)) || 0;
    const allCount = Number(await env.KV.get(allKey)) || 0;
    if (ipCount >= PER_IP_PER_DAY || allCount >= ALL_PER_DAY) return reply(429, 'limit');

    const lines = FIELDS.map((f) => `${f}=${report[f]}`).join('\n');
    const discord = await fetch(env.DISCORD_WEBHOOK, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: 'PB-OS Installer',
        content: `**Device report** · ${report.manufacturer} ${report.model}\n\`\`\`\n${lines}\n\`\`\``,
        allowed_mentions: { parse: [] },
        flags: 4, // no link previews
      }),
    });
    if (!discord.ok) return reply(502, 'discord_failed');

    const twoDays = 2 * 86400;
    await Promise.all([
      env.KV.put(ipKey, String(ipCount + 1), { expirationTtl: twoDays }),
      env.KV.put(allKey, String(allCount + 1), { expirationTtl: twoDays }),
      env.KV.put(seenKey, '1', { expirationTtl: DUPLICATE_DAYS * 86400 }),
    ]);
    return reply(200, 'sent');
  },
};

function valid(report) {
  if (!report || typeof report !== 'object' || Array.isArray(report)) return false;
  const keys = Object.keys(report);
  if (keys.length !== FIELDS.length || !FIELDS.every((f) => keys.includes(f))) return false;
  return FIELDS.every((f) => typeof report[f] === 'string' && VALUE.test(report[f]));
}

async function sha256(text) {
  const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(hash)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

function reply(status, result) {
  return new Response(JSON.stringify({ status: result }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
