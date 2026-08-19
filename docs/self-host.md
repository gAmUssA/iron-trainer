# Run Iron Trainer on your own computer

You need [Docker Desktop](https://www.docker.com/products/docker-desktop/). Nothing else —
no Java, no Node, no building anything.

## Start it

```bash
curl -O https://raw.githubusercontent.com/gAmUssA/iron-trainer/main/docker-compose.yml
docker compose up
```

Open **http://localhost:8080**.

The first start pulls two images (~500 MB) and sets up the database, so give it a minute.
After that it starts in a couple of seconds.

There is no account and no password. It runs as a single athlete — you.

## What works with no setup at all

Everything except live Strava sync and AI plan adaptation:

- Import your full Strava history from a data export
- Import your WHOOP history from a member export
- Training load (TSS / CTL / ATL / TSB), trends, personal records
- Plan generation with the built-in planner
- Nutrition and race-day fuelling
- Race projection and cut-off checks

## Optional: connect Strava for live sync

Strava requires **your own** API application — we cannot ship ours, because Strava issues
credentials per app and rate-limits them per app. It is free and takes about two minutes.

1. Go to https://www.strava.com/settings/api
2. Create an application. For **Authorization Callback Domain** enter exactly `localhost`
3. Copy the **Client ID** and **Client Secret**
4. Create a file called `.env` next to `docker-compose.yml`:

   ```
   STRAVA_CLIENT_ID=12345
   STRAVA_CLIENT_SECRET=abc123...
   ```

5. `docker compose up -d` again

Without this, use the Strava archive import instead — Strava → Settings → My Account →
Download or Delete Your Account → Request your archive. It arrives by email as a `.zip`
you upload in Settings.

## Optional: AI plan adaptation

Add an [Anthropic API key](https://console.anthropic.com/) to the same `.env`:

```
ANTHROPIC_API_KEY=sk-ant-...
```

This is a paid API. Without it the app uses its deterministic planner, which produces a
complete, valid plan — you are not missing the app's core function.

## Your data

It lives in a Docker volume called `iron-data`, not in a file you can see.

| Command | Effect |
|---|---|
| `docker compose down` | stops everything, **keeps** your data |
| `docker compose down -v` | stops everything and **deletes your training history** |

Back up before upgrading:

```bash
docker compose exec -T db pg_dump -U iron iron > iron-backup-$(date +%F).sql
```

Restore:

```bash
docker compose exec -T db psql -U iron -d iron < iron-backup-2026-08-19.sql
```

## Updating

```bash
docker compose pull
docker compose up -d
```

Database migrations run automatically at startup. Take a backup first anyway.

## When something is wrong

**"port is already allocated"** — something else uses 8080. Change the left-hand number
in `docker-compose.yml` (`"8081:8080"`) and use http://localhost:8081.

**Blank page or connection refused** — the app may still be starting. Watch it:

```bash
docker compose logs -f app
```

Wait for `Listening on: http://0.0.0.0:8080`.

**"No plan yet"** — expected on a fresh install. Import your data, then generate a plan
from the Training Plan tab.

**Everything is broken and I want to start over** — `docker compose down -v` then
`docker compose up`. This deletes your data; export a backup first if you want it.

## The iPhone app

The iOS app pairs to a server over the network. Pointing it at a laptop install means the
phone must be on the same Wi-Fi and able to reach your machine's LAN address — it will not
work as smoothly as the hosted version, and it will stop working when your laptop sleeps.
