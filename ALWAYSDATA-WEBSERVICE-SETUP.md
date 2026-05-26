# Webservice Setup on alwaysdata

## Architecture

```
Internet  -->  Site (Reverse Proxy)  -->  Service (Java app, 24/7)
               weather.duhmatias.alwaysdata.net     port 8300
```

Your existing **Service** keeps running 24/7 (polling + notifications).  
A new **Site** (Reverse Proxy) gives the API a public URL.

---

## Step 1: Update the Service

Go to: **alwaysdata Admin > Advanced > Services > [your existing service]**

Add this environment variable (in the "Environment" field):

```
HTTP_SERVER_ENABLED=true
```

The port defaults to `8300` (already in the allowed range 8300-8499).  
If you need a different port, also add: `HTTP_SERVER_PORT=8300`

**Save** and let the service restart.

---

## Step 2: Verify the Service is Listening

SSH into your alwaysdata account and test:

```bash
curl http://localhost:8300/api/health
```

Expected response:
```json
{"status":"up","version":"1.2.0.20260522..."}
```

If it doesn't respond, check logs at: `$HOME/admin/logs/services/`

---

## Step 3: Create a Reverse Proxy Site

Go to: **alwaysdata Admin > Web > Sites > Add a site**

Fill in the fields:

| Field | Value |
|-------|-------|
| **Name** | `SMN Weather API` (informational only) |
| **Addresses** | `weather.duhmatias.alwaysdata.net` (or your preferred subdomain) |
| **Type** | Reverse proxy |
| **URL** | `http://services-duhmatias.alwaysdata.net:8300` |

> Replace `duhmatias` with your actual alwaysdata account name.

**Save** the site.

---

## Step 4: Test Public Access

Wait a minute for DNS/proxy propagation, then:

```bash
# Health check
curl https://weather.duhmatias.alwaysdata.net/api/health

# Current conditions for configured stations (CABA + Aeroparque by default)
curl https://weather.duhmatias.alwaysdata.net/api/stations

# Search by location name
curl "https://weather.duhmatias.alwaysdata.net/api/current?q=Salta"
curl "https://weather.duhmatias.alwaysdata.net/api/current?q=Buenos+Aires"
curl "https://weather.duhmatias.alwaysdata.net/api/current?q=Mendoza"
```

---

## API Endpoints Reference

| Endpoint | Description | Example |
|----------|-------------|---------|
| `GET /api/health` | Version and status | `{"status":"up","version":"..."}` |
| `GET /api/stations` | Conditions for all configured stations | Returns temperature, humidity, wind, etc. |
| `GET /api/current?q=<location>` | Search by city/station name | Same data as Telegram `/current` command |

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Service not listening | Check `$HOME/admin/logs/services/` for errors |
| 502 Bad Gateway on site | Service may not be running; verify it's enabled in Admin > Services |
| Port not reachable | Ensure port is in 8300-8499 range |
| Empty response from `/api/stations` | SMN may be rate-limiting; check service logs |
| Service keeps restarting | Check if `config.properties` has valid SMTP credentials (required by app) |

---

## Notes

- The Service runs 24/7 with auto-restart on crash
- The HTTP server runs inside the same process as the polling loop (no extra deployment)
- CORS headers are included (`Access-Control-Allow-Origin: *`) for browser access
- No authentication — anyone with the URL can query the API
- SMN data is fetched live on each request (not cached)
