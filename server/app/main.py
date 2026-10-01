import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.config import settings
from app.routers import auth, bots, models, chat, approvals, upload, settings as settings_router, connectors, audit, computers
from app.services.auth_service import auth_service
from app.services.computer_provider import computer_provider
from app.services.storage_service import storage_service

# --- LowBot extension: durable v2 engine -----------------------------------
from app.v2.api import automation_routes, core_routes, system_routes
from app.v2.devices import DeviceService
from app.v2.runtime import Runtime

# Origins used by the packaged native shells. They authenticate with a
# per-device Bearer token (never cookies), so they are accepted only for
# Bearer requests; cookie-authenticated requests must come from CORS_ORIGINS.
NATIVE_ORIGINS = {
    o.strip() for o in os.getenv(
        "NATIVE_ORIGINS", "capacitor://localhost,http://localhost,https://localhost,tauri://localhost,http://tauri.localhost"
    ).split(",") if o.strip()
}


@asynccontextmanager
async def lifespan(app: FastAPI):
    runtime = Runtime()
    runtime.load_extensions()
    app.state.v2 = runtime
    devices = DeviceService(runtime.core)
    auth_service.bearer_authenticators.append(devices.authenticate)
    await runtime.start()
    try:
        yield
    finally:
        await runtime.stop()
        auth_service.bearer_authenticators.remove(devices.authenticate)


app = FastAPI(
    title="Open Dots API",
    description="Open-source alternative to OpenAI Dots: self-hosted AI workspace API with a configurable inference adapter",
    version="2.0.0",
    lifespan=lifespan,
)

PUBLIC_API_PATHS = {
    "/api/v1/health",
    "/api/v1/auth/status",
    "/api/v1/auth/session",
    "/api/v1/auth/login",
    "/api/v1/auth/logout",
    "/api/v2/health",
    "/api/v2/pair/exchange",
}
PUBLIC_API_PREFIXES = ("/api/v2/hooks/",)  # HMAC-signed webhooks


def _is_api(path: str) -> bool:
    return path.startswith("/api/v1") or path.startswith("/api/v2")


@app.middleware("http")
async def require_authentication(request: Request, call_next):
    path = request.url.path
    bearer = request.headers.get("authorization", "").lower().startswith("bearer ")
    if _is_api(path) and request.method != "OPTIONS":
        origin = request.headers.get("origin")
        allowed_origins = {*settings.CORS_ORIGINS, str(request.base_url).rstrip("/")}
        native_ok = bearer and origin in NATIVE_ORIGINS
        # CORS alone does not stop credentialed requests from changing state.
        if (origin and origin not in allowed_origins and not native_ok) or (
            not origin and not bearer and request.headers.get("sec-fetch-site") in {"cross-site", "same-site"}
        ):
            return JSONResponse({"detail": "Untrusted request origin."}, status_code=403)
    if (
        request.method == "OPTIONS"
        or not _is_api(path)
        or path in PUBLIC_API_PATHS
        or path.startswith(PUBLIC_API_PREFIXES)
    ):
        response = await call_next(request)
        if path.startswith("/api/v1/auth/"):
            response.headers["Cache-Control"] = "no-store"
        return response

    user = auth_service.authenticate_request(request)
    if not user:
        return JSONResponse(
            {"detail": "Authentication is required."},
            status_code=401,
            headers={"WWW-Authenticate": "Bearer", "Cache-Control": "no-store"},
        )
    request.state.user = user
    response = await call_next(request)
    if path.startswith("/api/v2"):
        response.headers.setdefault("Cache-Control", "no-store")
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
    return response

# Wrap authentication so allowed browser clients can read 401 responses.
app.add_middleware(
    CORSMiddleware,
    allow_origins=[*settings.CORS_ORIGINS, *sorted(NATIVE_ORIGINS)],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(auth.router)
app.include_router(bots.router)
app.include_router(models.router)
app.include_router(chat.router)
app.include_router(upload.router)
app.include_router(approvals.router)
app.include_router(settings_router.router)
app.include_router(connectors.router)
app.include_router(audit.router)
app.include_router(computers.router)

app.include_router(core_routes.router)
app.include_router(automation_routes.router)
app.include_router(system_routes.router)
app.include_router(system_routes.public)
try:  # optional subsystems register their own routers
    from app.v2.api import extension_routes
    app.include_router(extension_routes.router)
except ImportError:  # pragma: no cover
    pass


@app.get("/api/v1/health")
async def health_check():
    return {
        "status": "online",
        "service": "Open Dots FastAPI Backend",
        "provider": "configured inference endpoint",
        "computer_provider": computer_provider.provider_name,
        "default_model": storage_service.get_settings().get("default_model") or settings.DEFAULT_MODEL
    }
