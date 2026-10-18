import "dotenv/config";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";

const port = Number(process.env.API_PORT ?? 4000);

function sendJson(res: ServerResponse, status: number, body: unknown) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    "Content-Type": "application/json",
    "Content-Length": Buffer.byteLength(payload),
  });
  res.end(payload);
}

function handler(req: IncomingMessage, res: ServerResponse) {
  const url = new URL(req.url ?? "/", `http://${req.headers.host ?? "localhost"}`);

  if (req.method === "GET" && url.pathname === "/health") {
    sendJson(res, 200, { status: "ok", service: "api" });
    return;
  }

  sendJson(res, 404, { error: "not_found" });
}

createServer(handler).listen(port, () => {
  console.log(`api listening on http://localhost:${port}`);
});
