"""HTTP API for the v2 engine (``/api/v2``)."""

from fastapi import HTTPException, Request

from app.v2.runtime import Runtime


def rt(request: Request) -> Runtime:
    runtime = getattr(request.app.state, "v2", None)
    if runtime is None:
        raise HTTPException(503, "Engine not started.")
    return runtime


def user_id(request: Request) -> str:
    user = getattr(request.state, "user", None) or {}
    return user.get("id", "local-user")


def bad(exc: Exception, code: int = 400) -> HTTPException:
    return HTTPException(code, str(exc))
