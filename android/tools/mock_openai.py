"""Minimal OpenAI-compatible mock server for smoke-testing the CanopyChat Android client."""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"{}")
        last_user = ""
        for message in body.get("messages", []):
            if message.get("role") == "user":
                last_user = message.get("content", "")
        reply = {
            "choices": [{
                "message": {
                    "role": "assistant",
                    "content": f"Mock backend reply. I received your message: \"{last_user[:120]}\". "
                               "The CanopyChat Android chat pipeline is working end to end."
                }
            }]
        }
        data = json.dumps(reply).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        print("[mock]", args[0] % args[1:])


HTTPServer(("0.0.0.0", 8787), Handler).serve_forever()
