# Sahan Switch

Payment switch backend (Spring Boot 4 / Java 21 / PostgreSQL) and operations portal (Vite + React).

- Backend: `backend/sahan-switch-backend` (port 9090)
- Portal: `frontend/sahan-switch-portal` (port 5173)
- Architecture and API: [docs/SYSTEM_OVERVIEW.md](docs/SYSTEM_OVERVIEW.md)

```bash
cd backend/sahan-switch-backend && ./mvnw spring-boot:run
cd frontend/sahan-switch-portal && npm ci && npm run dev
```
