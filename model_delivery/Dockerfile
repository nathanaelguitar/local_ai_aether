FROM python:3.12-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app/src

RUN addgroup --system --gid 10002 canopy \
    && adduser --system --uid 10002 --gid 10002 --home /home/canopy canopy \
    && mkdir -p /app /data/canopy/model_delivery \
    && chown -R canopy:canopy /app /data/canopy

WORKDIR /app
COPY --chown=canopy:canopy model_delivery/pyproject.toml /app/pyproject.toml
COPY --chown=canopy:canopy model_delivery/src /app/src

USER canopy
EXPOSE 8792

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD python -c "from urllib.request import urlopen; raise SystemExit(0 if urlopen('http://127.0.0.1:8792/ready', timeout=2).status == 200 else 1)"

CMD ["python", "-m", "canopy_delivery.server"]
