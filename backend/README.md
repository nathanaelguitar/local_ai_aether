# AetherChat Backend

Small Kotlin/Ktor backend for the SwiftUI app. It exposes an OpenAI-compatible chat endpoint for AetherChat and proxies requests to a local or remote OpenAI-compatible model server.

## Run

```sh
gradle -p backend run
```

The app defaults to:

```text
http://127.0.0.1:8787
```

## Endpoints

```text
GET  /health
POST /v1/chat/completions
```

## Configuration

```sh
export AETHER_UPSTREAM_BASE_URL=http://127.0.0.1:11434/v1
export AETHER_MODEL=aether-local
export AETHER_UPSTREAM_API_KEY=
export AETHER_ALLOW_LOCAL_FALLBACK=true
```

`AETHER_UPSTREAM_BASE_URL` may point at Ollama, LM Studio, llama.cpp server, or any OpenAI-compatible `/v1` endpoint. When the upstream does not answer and `AETHER_ALLOW_LOCAL_FALLBACK=true`, the backend returns a valid diagnostic assistant message so the iOS chat path can still be tested end to end.
