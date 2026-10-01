package ai.opencode.cli.terminal

class NativePty {
    interface Callback {
        fun onPtyOutput(data: ByteArray)
        fun onPtyExit(exitCode: Int)
    }

    @Volatile
    private var handle: Long = 0L

    fun isAlive(): Boolean = handle != 0L

    fun create(
        cwd: String,
        argv: Array<String>,
        envp: Array<String>,
        cols: Int,
        rows: Int,
        callback: Callback
    ): Boolean {
        close()
        handle = nativeCreate(cwd, argv, envp, cols, rows, callback)
        return handle != 0L
    }

    fun setupTty(
        cwd: String,
        envp: Array<String>,
        cols: Int,
        rows: Int,
        callback: Callback
    ): Boolean {
        close()
        handle = nativeSetupTty(cwd, envp, cols, rows, callback)
        return handle != 0L
    }

    fun startNode(libPath: String, argv: Array<String>): Int {
        val h = handle
        if (h == 0L) {
            return -1
        }
        return nativeStartNode(h, libPath, argv)
    }

    fun write(data: ByteArray): Int {
        val h = handle
        if (h == 0L || data.isEmpty()) {
            return 0
        }
        return nativeWrite(h, data)
    }

    fun writeUtf8(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }

    fun resize(cols: Int, rows: Int) {
        val h = handle
        if (h != 0L) {
            nativeResize(h, cols, rows)
        }
    }

    fun pid(): Int {
        val h = handle
        return if (h == 0L) -1 else nativeGetPid(h)
    }

    fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            nativeClose(h)
        }
    }

    private external fun nativeCreate(
        cwd: String,
        argv: Array<String>,
        envp: Array<String>,
        cols: Int,
        rows: Int,
        callback: Callback
    ): Long

    private external fun nativeSetupTty(
        cwd: String,
        envp: Array<String>,
        cols: Int,
        rows: Int,
        callback: Callback
    ): Long

    private external fun nativeStartNode(handle: Long, libPath: String, argv: Array<String>): Int

    private external fun nativeWrite(handle: Long, data: ByteArray): Int
    private external fun nativeResize(handle: Long, cols: Int, rows: Int)
    private external fun nativeClose(handle: Long)
    private external fun nativeGetPid(handle: Long): Int

    companion object {
        init {
            System.loadLibrary("opencode_terminal")
        }
    }
}
