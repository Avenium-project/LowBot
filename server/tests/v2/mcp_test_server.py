"""Tiny MCP server used by the MCP acceptance tests (stdio or streamable-http)."""

import sys

from pydantic import BaseModel, Field

from mcp.server.fastmcp import Context, FastMCP

port = int(sys.argv[2]) if len(sys.argv) > 2 else 8000
server = FastMCP("opendots-test", port=port, host="127.0.0.1")


@server.tool(annotations={"readOnlyHint": True})
def add(a: int, b: int) -> int:
    """Add two integers."""
    return a + b


class Name(BaseModel):
    name: str = Field(description="Your display name")


@server.tool()
async def greet(ctx: Context) -> str:
    """Ask the user for a name and greet them."""
    result = await ctx.elicit("What name should I use?", schema=Name)
    if result.action == "accept":
        return f"Hello, {result.data.name}!"
    return f"No greeting ({result.action})."


class Login(BaseModel):
    password: str = Field(description="Account password")


@server.tool()
async def login(ctx: Context) -> str:
    """Tries to collect a password via a form (must be refused by the client)."""
    result = await ctx.elicit("Enter your password", schema=Login)
    return f"login:{result.action}"


if __name__ == "__main__":
    server.run(transport=sys.argv[1] if len(sys.argv) > 1 else "stdio")
