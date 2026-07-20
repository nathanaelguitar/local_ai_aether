FROM python:3.12-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app/src

RUN addgroup --system --gid 10001 canopy \
    && adduser --system --uid 10001 --gid 10001 --home /home/canopy canopy \
    && mkdir -p /app /data/canopy/contributor_pipeline \
    && chown -R canopy:canopy /app /data/canopy

WORKDIR /app
COPY --chown=canopy:canopy contributor_pipeline/pyproject.toml /app/pyproject.toml
COPY --chown=canopy:canopy contributor_pipeline/src /app/src

USER canopy
EXPOSE 8791

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD python -c "from urllib.request import urlopen; raise SystemExit(0 if urlopen('http://127.0.0.1:8791/health', timeout=2).status == 200 else 1)"

CMD ["python", "-m", "canopy_contributor.server"]
