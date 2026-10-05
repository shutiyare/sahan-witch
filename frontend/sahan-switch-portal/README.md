# Sahan Switch operations portal

Vite + React 18 + TypeScript + Tailwind. Talks to the backend on `localhost:9090` through the
Vite dev proxy (`/api` → backend).

```bash
cd frontend/sahan-switch-portal
npm ci
npm run dev          # http://localhost:5173
npm run lint
npm run build
```

Sign in with the administrator created at backend startup (`SAHANSWITCH_ADMIN_USERNAME` /
`SAHANSWITCH_ADMIN_PASSWORD`, local default `admin` / `ChangeMe!123`).
