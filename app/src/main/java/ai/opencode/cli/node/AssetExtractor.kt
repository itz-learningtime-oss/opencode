package ai.opencode.cli.node

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class AssetExtractor(private val context: Context) {

    data class Layout(
        val filesDir: File,
        val nativeLibDir: File,
        val binDir: File,
        val nodeBinary: File,
        val gitBinary: File,
        val rgBinary: File,
        val opencodeDir: File,
        val opencodeIndex: File,
        val homeDir: File,
        val tmpDir: File
    )

    fun layout(): Layout {
        val files = context.filesDir
        val bin = File(files, "bin")
        return Layout(
            filesDir = files,
            nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
            binDir = bin,
            nodeBinary = File(bin, "node"),
            gitBinary = File(bin, "git"),
            rgBinary = File(bin, "rg"),
            opencodeDir = File(files, "opencode"),
            opencodeIndex = File(files, "opencode/index.js"),
            homeDir = files,
            tmpDir = File(files, "tmp")
        )
    }

    fun ensureReady(): Layout {
        val layout = layout()
        layout.binDir.mkdirs()
        layout.opencodeDir.mkdirs()
        layout.tmpDir.mkdirs()
        File(layout.homeDir, ".opencode").mkdirs()

        extractStandalone("git", layout.gitBinary)
        extractStandalone("rg", layout.rgBinary)
        extractNodeExecutable(layout)
        extractOpenCodeBundle(layout.opencodeDir)
        writeFallbackIndexIfMissing(layout)
        return layout
    }

    /**
     * nodejs-mobile ships libnode.so as a shared library started in-process.
     * This only resolves an optional *executable* Node (legacy fork/exec path).
     */
    fun executableNode(layout: Layout): File {
        if (layout.nodeBinary.exists()) {
            return layout.nodeBinary
        }
        val execSo = File(layout.nativeLibDir, "libnodeexec.so")
        if (execSo.exists()) {
            return execSo
        }
        return layout.nodeBinary
    }

    fun soFile(name: String): File {
        val libDir = File(context.applicationInfo.nativeLibraryDir)
        val withLib = File(libDir, "lib$name.so")
        if (withLib.exists()) {
            return withLib
        }
        return File(libDir, "$name.so")
    }

    private fun extractNodeExecutable(layout: Layout) {
        val dest = layout.nodeBinary
        val candidates = listOf("bin/node", "bin/armeabi-v7a/node", "node")
        for (asset in candidates) {
            if (copyAssetIfPresent(asset, dest)) {
                chmod755(dest)
                return
            }
        }
        val execSo = File(layout.nativeLibDir, "libnodeexec.so")
        if (execSo.exists() && Build.VERSION.SDK_INT < 29) {
            copyIfNewer(execSo, dest)
            chmod755(dest)
        }
    }

    private fun extractStandalone(name: String, dest: File) {
        val candidates = listOf("bin/$name", "bin/armeabi-v7a/$name", name)
        for (asset in candidates) {
            if (copyAssetIfPresent(asset, dest)) {
                chmod755(dest)
                return
            }
        }
        if (dest.exists()) {
            chmod755(dest)
        }
    }

    private fun extractOpenCodeBundle(opencodeDir: File) {
        val marker = File(opencodeDir, ".extracted")
        val zipAsset = "opencode/opencode.zip"
        if (assetExists(zipAsset)) {
            if (!marker.exists() || assetNewerThan(zipAsset, marker)) {
                unzipAsset(zipAsset, opencodeDir)
                marker.writeText(System.currentTimeMillis().toString())
            }
            return
        }
        copyAssetTree("opencode", opencodeDir)
        if (!marker.exists()) {
            marker.writeText(System.currentTimeMillis().toString())
        }
    }

    private fun writeFallbackIndexIfMissing(layout: Layout) {
        if (layout.opencodeIndex.exists() && layout.opencodeIndex.length() > 0) {
            return
        }
        layout.opencodeIndex.parentFile?.mkdirs()
        layout.opencodeIndex.writeText(FALLBACK_INDEX_JS)
    }

    private fun copyIfNewer(src: File, dest: File) {
        if (dest.exists() && dest.length() == src.length() && dest.lastModified() >= src.lastModified()) {
            return
        }
        FileInputStream(src).use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
        dest.setLastModified(src.lastModified())
    }

    private fun copyAssetIfPresent(assetPath: String, dest: File): Boolean {
        return try {
            context.assets.open(assetPath).use { input ->
                val tmp = File(dest.parentFile, dest.name + ".tmp")
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
                if (dest.exists()) {
                    dest.delete()
                }
                tmp.renameTo(dest)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun copyAssetTree(assetDir: String, destDir: File) {
        val children = try {
            context.assets.list(assetDir)
        } catch (_: Exception) {
            null
        } ?: return
        destDir.mkdirs()
        for (child in children) {
            val childPath = if (assetDir.isEmpty()) child else "$assetDir/$child"
            val nested = context.assets.list(childPath)
            if (nested != null && nested.isNotEmpty()) {
                copyAssetTree(childPath, File(destDir, child))
            } else {
                val out = File(destDir, child)
                try {
                    context.assets.open(childPath).use { input ->
                        FileOutputStream(out).use { output -> input.copyTo(output) }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun unzipAsset(assetPath: String, destDir: File) {
        destDir.mkdirs()
        context.assets.open(assetPath).use { raw ->
            ZipInputStream(raw).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val out = File(destDir, entry.name)
                    val canonical = out.canonicalFile
                    if (!canonical.path.startsWith(destDir.canonicalPath)) {
                        throw SecurityException("Zip path traversal: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        canonical.mkdirs()
                    } else {
                        canonical.parentFile?.mkdirs()
                        FileOutputStream(canonical).use { output -> zip.copyTo(output) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
    }

    private fun assetExists(path: String): Boolean {
        return try {
            context.assets.open(path).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun assetNewerThan(path: String, file: File): Boolean {
        return try {
            context.assets.openFd(path).use { fd ->
                fd.length != file.length()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun chmod755(file: File) {
        if (!file.exists()) {
            return
        }
        try {
            file.setReadable(true, false)
            file.setExecutable(true, false)
            file.setWritable(true, true)
            if (Build.VERSION.SDK_INT >= 21) {
                Os.chmod(file.absolutePath, 493)
            }
        } catch (e: Exception) {
            Log.w(TAG, "chmod failed for ${file.absolutePath}: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AssetExtractor"

        const val FALLBACK_INDEX_JS = """
"use strict";
const http = require("http");
const https = require("https");
const { URL } = require("url");
const readline = require("readline");

const serverUrl = process.env.OPENCODE_SERVER_URL || "";
const token = process.env.OPENCODE_API_KEY || process.env.OPENCODE_TOKEN || process.env.TOKEN || "";

function banner() {
  process.stdout.write("\x1b[36mOpenCode CLI (embedded Node.js)\x1b[0m\n");
  process.stdout.write("Home: " + (process.env.HOME || "") + "\n");
  process.stdout.write("Node: " + process.version + " " + process.arch + "\n");
  process.stdout.write("Server: " + (serverUrl || "(not configured)") + "\n");
  process.stdout.write("Token: " + (token ? "(set)" : "(missing)") + "\n\n");
  if (!serverUrl) {
    process.stdout.write("\x1b[33mSet OpenCode Server URL in Settings, then restart.\x1b[0m\n");
  }
  process.stdout.write("Commands: /ping  /models  /help  /quit\n> ");
}

function request(path, method, body) {
  return new Promise((resolve, reject) => {
    if (!serverUrl) {
      reject(new Error("OPENCODE_SERVER_URL is empty"));
      return;
    }
    let target;
    try {
      target = new URL(path, serverUrl.endsWith("/") ? serverUrl : serverUrl + "/");
    } catch (e) {
      reject(e);
      return;
    }
    const lib = target.protocol === "https:" ? https : http;
    const payload = body ? Buffer.from(JSON.stringify(body)) : null;
    const headers = {
      "Accept": "application/json",
      "User-Agent": "OpenCodeCli-Android/1.0"
    };
    if (token) {
      headers["Authorization"] = "Bearer " + token;
      headers["X-API-Key"] = token;
    }
    if (payload) {
      headers["Content-Type"] = "application/json";
      headers["Content-Length"] = String(payload.length);
    }
    const req = lib.request({
      protocol: target.protocol,
      hostname: target.hostname,
      port: target.port,
      path: target.pathname + target.search,
      method: method || "GET",
      headers,
      timeout: 20000
    }, (res) => {
      const chunks = [];
      res.on("data", (c) => chunks.push(c));
      res.on("end", () => {
        resolve({
          status: res.statusCode,
          body: Buffer.concat(chunks).toString("utf8")
        });
      });
    });
    req.on("error", reject);
    req.on("timeout", () => req.destroy(new Error("timeout")));
    if (payload) req.write(payload);
    req.end();
  });
}

async function ping() {
  const paths = ["health", "api/health", "status", ""];
  for (const p of paths) {
    try {
      const r = await request(p, "GET");
      process.stdout.write("GET /" + p + " -> " + r.status + "\n" + r.body + "\n");
      return;
    } catch (e) {
      process.stdout.write("GET /" + p + " failed: " + e.message + "\n");
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
      process.stdout.write("POST /" + p + " -> " + r.status + "\n");
      try {
        const json = JSON.parse(r.body);
        const text =
          (json.choices && json.choices[0] && (json.choices[0].message || {}).content) ||
          json.message || json.output || json.content || r.body;
        process.stdout.write(String(text) + "\n");
      } catch (_) {
        process.stdout.write(r.body + "\n");
      }
      return;
    } catch (e) {
      process.stdout.write("POST /" + p + " failed: " + e.message + "\n");
    }
  }
}

banner();
const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: true });
rl.on("line", async (line) => {
  const text = String(line || "").trim();
  try {
    if (!text) {
    } else if (text === "/quit" || text === "/exit") {
      rl.close();
      process.exit(0);
    } else if (text === "/help") {
      process.stdout.write("Type a prompt to send it to OPENCODE_SERVER_URL.\n");
    } else if (text === "/ping") {
      await ping();
    } else if (text === "/models") {
      try {
        const r = await request("v1/models", "GET");
        process.stdout.write(r.body + "\n");
      } catch (e) {
        process.stdout.write(String(e.message) + "\n");
      }
    } else {
      await chat(text);
    }
  } catch (e) {
    process.stdout.write("error: " + e.message + "\n");
  }
  process.stdout.write("> ");
});
rl.on("close", () => process.exit(0));
"""
    }
}
