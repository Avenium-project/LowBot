# Open Dots server image: API + durable worker + scheduler + built web UI.
# docker build -t open-dots .            (with browser surfaces)
# docker build --build-arg WITH_BROWSER=0 -t open-dots .   (smaller, no browser)

# BASE_REGISTRY lets you use a mirror (e.g. mirror.gcr.io/library).
ARG BASE_REGISTRY=docker.io/library
FROM ${BASE_REGISTRY}/node:22-bookworm-slim AS ui
WORKDIR /src/client
COPY client/package.json client/package-lock.json* ./
# Optional extra CA for TLS-intercepting proxies: --secret id=extra_ca,src=ca.pem
RUN --mount=type=secret,id=extra_ca,required=false \
    if [ -f /run/secrets/extra_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/extra_ca; fi; \
    npm ci --no-audit --no-fund
COPY client/ ./
RUN npm run build:export

FROM ${BASE_REGISTRY}/python:3.11-slim-bookworm
ARG WITH_BROWSER=1
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    DATA_DIR=/data UI_DIST=/app/ui HOST=0.0.0.0 PORT=8000 \
    PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers
RUN useradd --system --uid 10001 --home /data opendots && mkdir -p /data /opt/pw-browsers && chown opendots /data
WORKDIR /app/server
COPY server/requirements*.txt ./
RUN --mount=type=secret,id=extra_ca,required=false \
    if [ -f /run/secrets/extra_ca ]; then export PIP_CERT=/run/secrets/extra_ca; fi; \
    pip install --no-cache-dir -r requirements.txt \
 && if [ "$WITH_BROWSER" = "1" ]; then pip install --no-cache-dir -r requirements-browser.txt \
    && python -m playwright install --with-deps chromium && chmod -R a+rX /opt/pw-browsers; fi
COPY server/ ./
COPY LICENSE NOTICE.md /app/
COPY --from=ui /src/client/out /app/ui
USER opendots
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/api/v2/health', timeout=4).status == 200 else 1)"
CMD ["sh", "-c", "exec uvicorn app.main:app --host \"$HOST\" --port \"$PORT\" --proxy-headers --forwarded-allow-ips=127.0.0.1,172.16.0.0/12"]
