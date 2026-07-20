# Canopy Contributor Pipeline operations

This service is for the opt-in Canopy Contributor Beta only. Keep the DGX storage volume restricted to the service account and approved reviewers. Do not put production conversations, raw uploads, or credentials in the repository.

The production host root is `/data/canopy/contributor_pipeline`. The marketing site remains separate at `https://canopychat.app/`; the contributor API hostname is `contributor-api.canopychat.app`.

## First-run checklist

1. Confirm the DGX has no inbound router port forwarding and the host firewall does not expose the service.
2. Create the storage root and grant it to the container UID:

   ```sh
   sudo install -d -m 700 /data/canopy/contributor_pipeline
   sudo chown 10001:10001 /data/canopy/contributor_pipeline
   ```

3. Create a local untracked environment file. This repository intentionally contains only `.env.example`:

   ```sh
   cd contributor_pipeline
   cp .env.example .env
   umask 077
   openssl rand -hex 32
   openssl rand -hex 32
   ```

   Put the two outputs into `CANOPY_CONTRIBUTOR_SHARED_SECRET` and `CANOPY_CONTRIBUTOR_ADMIN_TOKEN`. Generate a separate Cloudflare Tunnel token in the Cloudflare dashboard; never commit any of these values.

4. Confirm `.env` contains `CANOPY_CONTRIBUTOR_DOMAIN=contributor-api.canopychat.app` and `CANOPY_HOST_STORAGE_ROOT=/data/canopy/contributor_pipeline`.
5. Start the internal services and verify readiness:

   ```sh
   docker compose --env-file .env up --build -d
   docker compose --env-file .env ps
   docker compose --env-file .env logs --tail=50 ingest curator retention caddy
   ```

6. Configure the Cloudflare Tunnel as described below, then start the connector:

   ```sh
   docker compose --env-file .env --profile tunnel up -d cloudflared
   ```

7. Confirm that `https://contributor-api.canopychat.app/health` and `/ready` respond through Cloudflare. Do not expose `127.0.0.1:8791` externally; Compose publishes no host port.

## Startup and shutdown

```sh
cd contributor_pipeline
docker compose --env-file .env up --build -d
docker compose --env-file .env --profile tunnel up -d cloudflared
docker compose --env-file .env ps
docker compose --env-file .env logs --tail=100 ingest curator retention caddy cloudflared
```

Stop without deleting persistent data:

```sh
docker compose --env-file .env --profile tunnel stop cloudflared
docker compose --env-file .env stop retention curator ingest caddy
```

Never use `docker compose down -v` for routine shutdown; removing the volume can remove contributor data.

## Cloudflare Tunnel configuration

Use the existing Cloudflare zone for `canopychat.app`; do not create a separate domain and do not create an A or AAAA record pointing at the DGX.

1. In Cloudflare Zero Trust, create a tunnel and install/configure a Docker connector.
2. Add one public hostname: `contributor-api.canopychat.app` -> `http://caddy:8080`.
3. Copy the tunnel token into the local, untracked `.env` as `CLOUDFLARE_TUNNEL_TOKEN`.
4. Keep the public hostname equal to `CANOPY_CONTRIBUTOR_DOMAIN`. If it changes, replace it in local `.env` and the Cloudflare public-hostname rule; no code or Compose edit is needed.
5. Start the connector with the command above and verify its logs.

The connector has outbound internet access only so it can reach Cloudflare. Ingest and Caddy share an internal Docker network, and the DGX has no inbound API listener. Configure a Cloudflare WAF/rate-limit rule in addition to the application limiter. Keep the Canopy marketing hostname and its Cloudflare rules in a separate policy and service configuration.

## Storage, permissions, backup, and restore

The Compose named volume is bind-backed to `CANOPY_HOST_STORAGE_ROOT`, defaulting to `/data/canopy/contributor_pipeline`. The service creates:

```text
raw/ quarantine/ bronze/ silver/ gold/ processed/ deleted/ logs/ backups/
```

Receipts, SQLite, and locks are under `processed/`. Deleted-user tombstones are under `deleted/`; they contain selector hashes, counts, and timestamps only.

Stop writers before backup so SQLite WAL and JSONL files are consistent:

```sh
docker compose --env-file .env --profile tunnel stop cloudflared
docker compose --env-file .env stop retention curator ingest caddy
backup_name="canopy-contributor-$(date -u +%Y%m%dT%H%M%SZ).tar.gz"
sudo tar --exclude='./backups' -C /data/canopy/contributor_pipeline \
  -czf "/data/canopy/contributor_pipeline/backups/$backup_name" .
sudo chmod 600 "/data/canopy/contributor_pipeline/backups/$backup_name"
```

Copy backups to encrypted, access-controlled storage. Restore only while all services are stopped:

```sh
sudo tar -xzf /secure/backup/path/canopy-contributor-YYYYMMDDTHHMMSSZ.tar.gz \
  -C /data/canopy/contributor_pipeline
sudo chown -R 10001:10001 /data/canopy/contributor_pipeline
sudo chmod 700 /data/canopy/contributor_pipeline
docker compose --env-file .env up -d
```

Filesystem unlinking cannot guarantee removal from SSD wear-leveling, copy-on-write snapshots, or old backups. Secure deletion includes destroying expired encrypted backups and snapshots.

## Retention cleanup

| Dataset | Retention | Automatic behavior |
| --- | ---: | --- |
| `raw/` | 30 days after successful processing | scheduled cleanup |
| `quarantine/` | 7 days | cleanup skips `.approved` sidecars |
| `bronze/` | 90 days | scheduled cleanup |
| `silver/` | 90 days | scheduled cleanup |
| `gold/training/` | explicit deletion | never automatic |
| `gold/eval/` | explicit deletion | never automatic |

Change the first four periods in local `.env` with `CANOPY_RAW_RETENTION_DAYS`, `CANOPY_QUARANTINE_RETENTION_DAYS`, `CANOPY_BRONZE_RETENTION_DAYS`, and `CANOPY_SILVER_RETENTION_DAYS`. Change `RETENTION_INTERVAL_SECONDS` for the schedule and restart the service:

```sh
docker compose --env-file .env up -d --force-recreate retention
docker compose --env-file .env exec retention python -m canopy_contributor.cleanup
```

To preserve a quarantined file for review, create a sidecar marker without editing the data file:

```sh
touch /data/canopy/contributor_pipeline/quarantine/path/to/file.jsonl.approved
```

Promotion from quarantine to gold must be deliberate and human-reviewed. Never train directly from raw, bronze, silver, or unreviewed quarantine data. Retention audit logs contain no prompt, response, or upload content.

## Deletion requests

Deletion is local-admin-only and is not a public endpoint:

```sh
export CANOPY_CONTRIBUTOR_ADMIN_TOKEN='the-local-value-from-.env'
PYTHONPATH=src python3 -m canopy_contributor.deletion \
  --root /data/canopy/contributor_pipeline \
  --installation-id INSTALLATION-UUID
```

The CLI also accepts `--batch-id` or `--receipt-id`, exactly one selector at a time. It removes matching raw, receipt, bronze, silver, gold, and quarantine records, clears corresponding ledger entries, and writes only a content-free tombstone under `deleted/`.

## Key rotation

The current iOS wire protocol uses one beta HMAC secret. It must not be shipped as a universal secret in an external TestFlight build.

1. Pause the Cloudflare connector and ingest service.
2. Generate a new secret with `openssl rand -hex 32`.
3. Replace `CANOPY_CONTRIBUTOR_SHARED_SECRET` only in local `.env`.
4. Restart ingest and the connector.
5. Update the controlled beta client distribution and verify one signed test batch.
6. Revoke or destroy the old secret through the operator secret-management policy.

Rotate `CANOPY_CONTRIBUTOR_ADMIN_TOKEN` separately; it is never sent by the iOS client.

## Incident response

If unauthorized traffic, a leaked credential, unexpected data, or suspected PII exposure is reported:

```sh
docker compose --env-file .env --profile tunnel stop cloudflared
docker compose --env-file .env stop retention curator ingest caddy
docker compose --env-file .env logs --no-color --since 2h > /secure/incident-log.txt
```

Do not include request bodies or credentials in incident artifacts. Preserve encrypted backups and content-free audit logs, rotate the affected secret, inspect Cloudflare events and deletion/retention logs, and restart only after the exposure path is understood.

## Production-readiness checklist

- [ ] Cloudflare Tunnel hostname is `contributor-api.canopychat.app` with no DGX A/AAAA record.
- [ ] No router port forwarding or public DGX firewall rule exists.
- [ ] Cloudflare WAF/rate limiting is configured separately from the in-process limiter.
- [ ] Shared secret, admin token, and tunnel token are independently generated and stored outside Git.
- [ ] Tunnel service points to `http://caddy:8080`.
- [ ] `/data/canopy/contributor_pipeline` is owned by UID/GID 10001 with mode 700.
- [ ] Encrypted backup and restore have been tested.
- [ ] Retention periods are recorded and scheduled cleanup is healthy.
- [ ] Deletion procedure has been exercised and tombstones contain no content.
- [ ] Gold training/evaluation review ownership is assigned.
- [ ] Tests, compilation, Compose validation, and image builds pass.

## Rollback

If a new image or configuration fails readiness:

```sh
docker compose --env-file .env --profile tunnel stop cloudflared
docker compose --env-file .env stop retention curator ingest caddy
git log --oneline -5
git show KNOWN_GOOD_COMMIT:contributor_pipeline/docker-compose.yml >/tmp/known-good-compose.yml
```

Restore the reviewed known-good repository revision through the normal Git change process, then rebuild and start services. Do not delete the persistent volume. If a data-format change caused the issue, stop writers, restore the last verified backup into a separate path, validate it, and only then switch the bind mount back.

## Validation commands

```sh
cd contributor_pipeline
PYTHONPATH=src python3 -m unittest discover -s tests -v
PYTHONPATH=src python3 -m compileall -q src tests
docker compose --env-file .env.example config --quiet
docker compose --env-file .env.example build
```

The example environment contains placeholders only. Never use it to start a service that could accept real data.
