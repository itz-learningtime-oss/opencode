package ai.opencode.cli.node

import android.content.Context
import android.os.Build
import android.util.Log
import ai.opencode.cli.settings.OpenCodeSettings
import ai.opencode.cli.terminal.NativePty
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class NodeExecutor(
    private val context: Context,
    private val settings: OpenCodeSettings
) {
    interface Listener {
        fun onOutput(data: ByteArray)
        fun onExit(code: Int)
        fun onStatus(message: String)
    }

    private val extractor = AssetExtractor(context)
    private val pty = NativePty()
    private val booting = AtomicBoolean(false)
    @Volatile
    private var listener: Listener? = null
    @Volatile
    var cols: Int = 80
    @Volatile
    var rows: Int = 24

    val nativePty: NativePty get() = pty

    fun isRunning(): Boolean = pty.isAlive()

    fun start(listener: Listener) {
        if (!booting.compareAndSet(false, true)) {
            return
        }
        this.listener = listener
        Thread({
            try {
                boot(listener)
            } catch (t: Throwable) {
                Log.e(TAG, "boot failed", t)
                listener.onStatus("Failed to start OpenCode: ${t.message}")
                listener.onOutput("\r\n\u001b[31m${t.message}\u001b[0m\r\n".toByteArray())
            } finally {
                booting.set(false)
            }
        }, "opencode-boot").start()
    }

    fun write(data: ByteArray) {
        pty.write(data)
    }

    fun resize(cols: Int, rows: Int) {
        this.cols = cols
        this.rows = rows
        pty.resize(cols, rows)
    }

    fun stop() {
        pty.close()
    }

    private fun boot(listener: Listener) {
        listener.onStatus("Preparing runtime…")
        val layout = extractor.ensureReady()

        val sharingLib = extractor.soFile("libnode")
        if (sharingLib.exists()) {
            startEmbeddedNode(sharingLib, layout, listener)
            return
        }

        val node = extractor.executableNode(layout)
        if (node.exists()) {
            startExecutableNode(node, layout, listener)
            return
        }

        val msg = buildString {
            append("\u001b[31mNode.js runtime not found.\u001b[0m\r\n")
            append("Package an armeabi-v7a Node runtime as:\r\n")
            append("  app/src/main/jniLibs/armeabi-v7a/libnode.so  (nodejs-mobile, in-process)\r\n")
            append("or a standalone executable as libnode.so / assets/bin/node.\r\n")
            append("OpenCode bundle: assets/opencode/index.js or opencode.zip\r\n")
        }
        listener.onOutput(msg.toByteArray())
        listener.onStatus("Missing Node runtime")
        startFallbackShell(layout, listener)
    }

    private fun startEmbeddedNode(
        libnode: File,
        layout: AssetExtractor.Layout,
        listener: Listener
    ) {
        listener.onStatus("Starting embedded Node…")
        val envp = buildEnv(layout)
        val callback = object : NativePty.Callback {
            override fun onPtyOutput(data: ByteArray) {
                listener.onOutput(data)
            }

            override fun onPtyExit(exitCode: Int) {
                listener.onExit(exitCode)
                listener.onStatus("OpenCode exited ($exitCode)")
            }
        }
        val ok = pty.setupTty(
            cwd = layout.homeDir.absolutePath,
            envp = envp,
            cols = cols,
            rows = rows,
            callback = callback
        )
        if (!ok) {
            listener.onOutput("\u001b[31mPTY setup failed\u001b[0m\r\n".toByteArray())
            listener.onStatus("PTY setup failed")
            startFallbackShell(layout, listener)
            return
        }

        val argv = buildNodeArgv(layout)
        Thread({
            val code = pty.startNode(libnode.absolutePath, argv)
            if (code < 0) {
                listener.onOutput(
                    "\u001b[31mFailed to start node::Start (dlopen/dlsym).\u001b[0m\r\n".toByteArray()
                )
                listener.onStatus("Node start failed")
            }
        }, "node-start").start()
    }

    private fun startExecutableNode(
        node: File,
        layout: AssetExtractor.Layout,
        listener: Listener,
    ) {
        if (!node.canExecute()) {
            node.setExecutable(true, false)
        }
        listener.onStatus("Starting OpenCode…")
        val argv = mutableListOf(node.absolutePath)
        argv.addAll(buildNodeArgs(layout))
        val envp = buildEnv(layout)
        val ok = pty.create(
            cwd = layout.homeDir.absolutePath,
            argv = argv.toTypedArray(),
            envp = envp,
            cols = cols,
            rows = rows,
            callback = object : NativePty.Callback {
                override fun onPtyOutput(data: ByteArray) {
                    listener.onOutput(data)
                }

                override fun onPtyExit(exitCode: Int) {
                    listener.onExit(exitCode)
                    listener.onStatus("OpenCode exited ($exitCode)")
                }
            }
        )
        if (!ok) {
            listener.onOutput("\u001b[31mforkpty/exec failed\u001b[0m\r\n".toByteArray())
            listener.onStatus("PTY spawn failed")
            startFallbackShell(layout, listener)
        }
    }

    private fun startFallbackShell(layout: AssetExtractor.Layout, listener: Listener) {
        val sh = File("/system/bin/sh")
        if (!sh.exists()) {
            listener.onOutput("No /system/bin/sh available.\r\n".toByteArray())
            return
        }
        val envp = buildEnv(layout)
        pty.create(
            cwd = layout.homeDir.absolutePath,
            argv = arrayOf(sh.absolutePath, "-"),
            envp = envp,
            cols = cols,
            rows = rows,
            callback = object : NativePty.Callback {
                override fun onPtyOutput(data: ByteArray) {
                    listener.onOutput(data)
                }

                override fun onPtyExit(exitCode: Int) {
                    listener.onExit(exitCode)
                }
            }
        )
        val notice = "\r\n# fallback shell. Install libnode.so to run OpenCode.\r\n"
        pty.write(notice.toByteArray())
    }

    private fun buildNodeArgs(layout: AssetExtractor.Layout): Array<String> {
        val args = mutableListOf(
            "--max-old-space-size=${settings.maxOldSpaceMb}",
            layout.opencodeIndex.absolutePath
        )
        val extra = settings.extraArgs
        if (extra.isNotBlank()) {
            args.addAll(extra.split(Regex("\\s+")).filter { it.isNotBlank() })
        }
        return args.toTypedArray()
    }

    private fun buildNodeArgv(layout: AssetExtractor.Layout): Array<String> {
        val argv = mutableListOf("node")
        argv.addAll(buildNodeArgs(layout))
        return argv.toTypedArray()
    }

    fun buildEnv(layout: AssetExtractor.Layout): Array<String> {
        val pathParts = linkedSetOf(
            layout.binDir.absolutePath,
            layout.nativeLibDir.absolutePath,
            "/system/bin",
            "/system/xbin",
            "/vendor/bin"
        )
        val env = linkedMapOf<String, String>()
        System.getenv().forEach { (k, v) ->
            if (k != "LD_LIBRARY_PATH" && k != "PATH") {
                env[k] = v
            }
        }

        val libPath = listOf(
            layout.nativeLibDir.absolutePath,
            layout.binDir.absolutePath,
            "/system/lib"
        ).joinToString(":")

        val gitSo = extractor.soFile("libgit")
        val rgSo = extractor.soFile("librg")
        if (gitSo.exists()) {
            env["GIT"] = gitSo.absolutePath
            env["GIT_EXEC_PATH"] = layout.nativeLibDir.absolutePath
        }
        if (rgSo.exists()) {
            env["RIPGREP"] = rgSo.absolutePath
            env["RG"] = rgSo.absolutePath
        }
        env["PATH"] = pathParts.joinToString(":")
        env["HOME"] = layout.homeDir.absolutePath
        env["TMPDIR"] = layout.tmpDir.absolutePath
        env["TMP"] = layout.tmpDir.absolutePath
        env["TEMP"] = layout.tmpDir.absolutePath
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["LANG"] = "en_US.UTF-8"
        env["LC_ALL"] = "en_US.UTF-8"
        env["LD_LIBRARY_PATH"] = libPath
        env["NODE_PATH"] = layout.opencodeDir.absolutePath
        env["OPENCODE_SERVER_URL"] = settings.serverUrl
        env["OPENCODE_API_KEY"] = settings.apiKey
        env["OPENCODE_TOKEN"] = settings.apiKey
        env["TOKEN"] = settings.apiKey
        env["OPENCODE_HOME"] = layout.homeDir.absolutePath
        env["OPENCODE_BIN"] = layout.binDir.absolutePath
        env["ANDROID_DATA"] = "/data"
        env["ANDROID_ROOT"] = "/system"
        env["USER"] = "opencode"
        env["LOGNAME"] = "opencode"
        env["SHELL"] = "/system/bin/sh"
        env["OPENCODE_PLATFORM"] = "android"
        env["OPENCODE_ARCH"] = "armeabi-v7a"
        env["NODE_OPTIONS"] = "--max-old-space-size=${settings.maxOldSpaceMb}"
        env["UV_THREADPOOL_SIZE"] = "2"
        if (Build.VERSION.SDK_INT >= 29) {
            env["ANDROID_API"] = Build.VERSION.SDK_INT.toString()
        }
        return env.map { "${it.key}=${it.value}" }.toTypedArray()
    }

    companion object {
        private const val TAG = "NodeExecutor"
    }
}
