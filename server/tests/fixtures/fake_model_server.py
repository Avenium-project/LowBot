"""Fake model HTTP server for testing the real Codex / OpenCode CLIs offline.

Implements just enough of two public wire formats:
  POST /v1/responses         (OpenAI Responses, SSE stream)   -> used by `codex exec`
  POST /v1/chat/completions  (Chat Completions, SSE or JSON)  -> used by `opencode run`
It answers "FAKE-REPLY: <last user text>" and records requests to a JSONL log.
"""

import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = sys.argv[2] if len(sys.argv) > 2 else None


def last_user_text(body):
    items = body.get("input") or body.get("messages") or []
    for it in reversed(items):
        if isinstance(it, dict) and it.get("role") == "user":
            c = it.get("content")
            if isinstance(c, str):
                return c
            for part in c or []:
                if isinstance(part, dict) and part.get("text"):
                    return part["text"]
    return ""


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _sse(self, events):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        for name, data in events:
            chunk = (f"event: {name}\n" if name else "") + f"data: {json.dumps(data) if not isinstance(data, str) else data}\n\n"
            self.wfile.write(chunk.encode())
            self.wfile.flush()
        self.close_connection = True

    def do_GET(self):
        body = json.dumps({"object": "list", "data": [{"id": "fake-model", "object": "model"}]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(n) or b"{}")
        if LOG:
            with open(LOG, "a") as f:
                f.write(json.dumps({"path": self.path, "auth": self.headers.get("Authorization", "")[:12], "body": body}) + "\n")
        text = "FAKE-REPLY: " + last_user_text(body).strip().splitlines()[-1][:200] if last_user_text(body).strip() else "FAKE-REPLY"
        if self.path.endswith("/responses"):
            rid, mid = f"resp_{int(time.time()*1000)}", "msg_1"
            item = {"type": "message", "id": mid, "role": "assistant", "status": "completed",
                    "content": [{"type": "output_text", "text": text, "annotations": []}]}
            usage = {"input_tokens": 11, "input_tokens_details": {"cached_tokens": 0},
                     "output_tokens": 7, "output_tokens_details": {"reasoning_tokens": 0}, "total_tokens": 18}
            self._sse([
                ("response.created", {"type": "response.created", "response": {"id": rid}}),
                ("response.output_item.added", {"type": "response.output_item.added", "output_index": 0,
                                                "item": {**item, "status": "in_progress", "content": []}}),
                ("response.output_text.delta", {"type": "response.output_text.delta", "item_id": mid, "output_index": 0,
                                                "content_index": 0, "delta": text}),
                ("response.output_item.done", {"type": "response.output_item.done", "output_index": 0, "item": item}),
                ("response.completed", {"type": "response.completed", "response": {"id": rid, "status": "completed",
                                                                                    "output": [item], "usage": usage}}),
            ])
            return
        if self.path.endswith("/chat/completions"):
            if body.get("stream"):
                base = {"id": "chatcmpl-1", "object": "chat.completion.chunk", "created": int(time.time()), "model": body.get("model")}
                self._sse([
                    (None, {**base, "choices": [{"index": 0, "delta": {"role": "assistant", "content": text}, "finish_reason": None}]}),
                    (None, {**base, "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
                            "usage": {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18}}),
                    (None, "[DONE]"),
                ])
                return
            out = json.dumps({"id": "chatcmpl-1", "object": "chat.completion", "model": body.get("model"),
                              "choices": [{"index": 0, "message": {"role": "assistant", "content": text}, "finish_reason": "stop"}],
                              "usage": {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18}}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(out)))
            self.end_headers()
            self.wfile.write(out)
            return
        self.send_response(404)
        self.send_header("Content-Length", "0")
        self.end_headers()


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
