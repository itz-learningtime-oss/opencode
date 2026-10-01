"use strict";

const http = require("http");
const https = require("https");
const { URL } = require("url");
const readline = require("readline");

const serverUrl = (process.env.OPENCODE_SERVER_URL || "").replace(/\/+$/, "");
const token = process.env.OPENCODE_API_KEY || process.env.OPENCODE_TOKEN || process.env.TOKEN || "";

function write(text) {
  process.stdout.write(text);
}

function banner() {
  write("\x1b[36mOpenCode CLI (embedded Node.js / armeabi-v7a)\x1b[0m\n");
  write("Home: " + (process.env.HOME || "") + "\n");
  write("Node: " + process.version + " " + process.arch + "\n");
  write("Server: " + (serverUrl || "(not configured)") + "\n");
  write("Token: " + (token ? "(set)" : "(missing)") + "\n\n");
  if (!serverUrl) {
    write("\x1b[33mSet OpenCode Server URL in Settings, then restart.\x1b[0m\n");
  }
  write("Commands: /ping  /models  /help  /quit\n");
}

function request(path, method, body) {
  return new Promise((resolve, reject) => {
    if (!serverUrl) {
      reject(new Error("OPENCODE_SERVER_URL is empty"));
      return;
    }
    let target;
    try {
      const base = serverUrl.endsWith("/") ? serverUrl : serverUrl + "/";
      target = new URL(path.replace(/^\//, ""), base);
    } catch (e) {
      reject(e);
      return;
    }
    const lib = target.protocol === "https:" ? https : http;
    const payload = body ? Buffer.from(JSON.stringify(body)) : null;
    const headers = {
      Accept: "application/json",
      "User-Agent": "OpenCodeCli-Android/1.0"
    };
    if (token) {
      headers.Authorization = "Bearer " + token;
      headers["X-API-Key"] = token;
    }
    if (payload) {
      headers["Content-Type"] = "application/json";
      headers["Content-Length"] = String(payload.length);
    }
    const req = lib.request(
      {
        protocol: target.protocol,
        hostname: target.hostname,
        port: target.port,
        path: target.pathname + target.search,
        method: method || "GET",
        headers,
        timeout: 20000
      },
      (res) => {
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () => {
          resolve({
            status: res.statusCode,
            body: Buffer.concat(chunks).toString("utf8")
          });
        });
      }
    );
    req.on("error", reject);
    req.on("timeout", () => req.destroy(new Error("timeout")));
    if (payload) {
      req.write(payload);
    }
    req.end();
  });
}

async function ping() {
  const paths = ["health", "api/health", "status", ""];
  for (const p of paths) {
    try {
      const r = await request(p, "GET");
      write("GET /" + p + " -> " + r.status + "\n" + r.body + "\n");
      return;
    } catch (e) {
      write("GET /" + p + " failed: " + e.message + "\n");
    }
  }
}

async function chat(prompt) {
  const body = {
    model: process.env.OPENCODE_MODEL || "default",
    messages: [{ role: "user", content: prompt }]
  };
  const paths = ["v1/chat/completions", "chat/completions", "api/chat", "chat"];
  for (const p of paths) {
    try {
      const r = await request(p, "POST", body);
      write("POST /" + p + " -> " + r.status + "\n");
      try {
        const json = JSON.parse(r.body);
        const choice = json.choices && json.choices[0];
        const text =
          (choice && choice.message && choice.message.content) ||
          json.message ||
          json.output ||
          json.content ||
          r.body;
        write(String(text) + "\n");
      } catch (_err) {
        write(r.body + "\n");
      }
      return;
    } catch (e) {
      write("POST /" + p + " failed: " + e.message + "\n");
    }
  }
}

banner();

const rl = readline.createInterface({
  input: process.stdin,
  output: process.stdout,
  terminal: true
});

rl.setPrompt("> ");
rl.prompt();
rl.on("line", async (line) => {
  const text = String(line || "").trim();
  try {
    if (!text) {
    } else if (text === "/quit" || text === "/exit") {
      rl.close();
      process.exit(0);
    } else if (text === "/help") {
      write("Type a prompt to send it to OPENCODE_SERVER_URL.\n");
    } else if (text === "/ping") {
      await ping();
    } else if (text === "/models") {
      try {
        const r = await request("v1/models", "GET");
        write(r.body + "\n");
      } catch (e) {
        write(String(e.message) + "\n");
      }
    } else {
      await chat(text);
    }
  } catch (e) {
    write("error: " + e.message + "\n");
  }
  rl.prompt();
});
rl.on("close", () => process.exit(0));
