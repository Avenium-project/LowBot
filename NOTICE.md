# Notice

LowBot / Open Dots v2 is based on **Open Dots** by Anil-matcha
(https://github.com/Anil-matcha/open-dots), MIT License, Copyright (c) 2026
Anil-matcha. Imported unmodified at upstream commit
`5abe1b3d65d5176af936ed92de1b731634d2bcb5` (see the first commit of this
repository); the original `LICENSE` is kept.

Changes made in this repository (LowBot extensions) are marked as such and
are released under the same MIT license. They are concentrated in:

- `server/app/v2/` — durable multi-bot engine, API, scheduler, MCP client,
  browser broker, device pairing (new)
- `server/tests/v2/`, `server/tests/e2e/` — tests (new)
- `client/components/v2/`, `client/lib/v2/`, `client/app/bots/` — new UI
- `desktop/` (Tauri, Windows), `mobile/` (Capacitor, Android), `deploy/`,
  `Dockerfile`, `.github/workflows/` (new)
- small, commented edits to upstream files: `server/app/main.py`,
  `server/app/services/auth_service.py`, `client/next.config.mjs`,
  `client/app/page.js`, `client/package.json`, `server/requirements.txt`

Open Dots is not affiliated with or endorsed by xAI, OpenAI, Anthropic or any
model provider. "Grok" is a trademark of its owner and is referenced only to
describe functional comparisons; no xAI code, branding, assets or non-public
APIs are used.
