# Device-report relay

The app's **Send Device Report** button sends its device info here. This
small Cloudflare Worker checks the report and posts it to a Discord channel.
The Discord webhook is a secret of the Worker, so it is never in the app or in
this repo.

The Worker only accepts a plain device report: the fixed list of fields in
[worker.js](worker.js), short values made of letters, digits and build-string
punctuation. Mentions, links and anything else are refused. It also limits
reports to 3 per address per day and 100 in total per day, and drops a report
it has already seen in the past week. It keeps only a salted hash of the
sender's address, for two days.

## Setup

You need a Discord server where you can manage webhooks, and a free Cloudflare
account.

### 1. Discord channel and webhook

1. Make a channel for the reports, e.g. `#device-reports`. In its permissions,
   turn off **Send Messages** for `@everyone` so only the webhook posts there.
2. Channel settings → **Integrations** → **Webhooks** → **New Webhook**. Name
   it `PB-OS Installer`.
3. **Copy Webhook URL**. Keep it private: anyone who has it can post in the
   channel. Don't put it in a chat, an issue or a file in this repo.

### 2. Cloudflare account

1. Sign up at <https://dash.cloudflare.com/sign-up> (the free plan is enough)
   and confirm your email.
2. The first time you open **Workers & Pages**, Cloudflare asks for a
   `workers.dev` subdomain. It becomes part of the public address built into
   the app, so pick a project name such as `project-barry`, not your own name.

### 3. KV storage (the counters)

1. **Storage & Databases** → **KV** → **Create** (or **Create instance**).
2. Name it `pbos-device-report` and create it.

### 4. The Worker

1. **Workers & Pages** → **Create** → **Create Worker** (start from
   *Hello World*). Name it `pbos-device-report` and **Deploy**.
2. **Edit code**, delete everything in `worker.js`, paste the contents of
   [worker.js](worker.js), and **Deploy**.
3. Worker → **Settings** → **Bindings** → **Add** → **KV namespace**:
   variable name `KV`, namespace `pbos-device-report`. Save.
4. Worker → **Settings** → **Variables and Secrets** → **Add**, type
   **Secret**, for each of:
   - `DISCORD_WEBHOOK`: the webhook URL from step 1.
   - `IP_SALT`: any long random text, e.g. 30 characters you mash on the
     keyboard. You never need it again.

   Then **Deploy**.
5. Note the Worker's address, shown on its overview page:
   `https://pbos-device-report.<your-subdomain>.workers.dev`. This is not a
   secret; it goes into the app.

### 5. Check it

From any computer (replace the address):

```sh
curl -X POST https://pbos-device-report.<your-subdomain>.workers.dev/report \
  -H 'User-Agent: PB-OS-Installer/test' -H 'Content-Type: application/json' \
  -d '{"app_version":"test","manufacturer":"Test","brand":"test","model":"Setup check","device":"test","product":"test","soc_manufacturer":"test","soc_model":"test","board_platform":"test","android":"test","fingerprint":"test","sd_block":"test","sd_type":"test","root":"none"}'
```

It should answer `{"status":"sent"}`, and a report should appear in the
Discord channel. Running it again answers `{"status":"duplicate"}`.

## If someone abuses it

Each report is limited to the fixed fields above, so the worst anyone can post
is a fake device report, up to 100 a day. To stop it at once, delete the
webhook in Discord: the Worker then answers `discord_failed` and the app shows
an error. Make a new webhook and put it in `DISCORD_WEBHOOK` to start again.
The app doesn't need an update for this.

## Local test

`test/run.sh` runs the Worker with `wrangler dev` against a fake Discord and
sends valid and invalid reports (needs Node.js):

```sh
cd relay && bash test/run.sh
```
