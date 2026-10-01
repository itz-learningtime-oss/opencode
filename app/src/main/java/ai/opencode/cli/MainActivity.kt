package ai.opencode.cli

import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import ai.opencode.cli.node.NodeExecutor
import ai.opencode.cli.settings.OpenCodeSettings
import ai.opencode.cli.terminal.TerminalView
import ai.opencode.cli.ui.KeyBarController
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity(), TerminalView.InputListener, NodeExecutor.Listener {

    private lateinit var terminalView: TerminalView
    private lateinit var statusView: TextView
    private lateinit var keyBar: LinearLayout
    private lateinit var settings: OpenCodeSettings
    private lateinit var executor: NodeExecutor
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        settings = OpenCodeSettings(this)
        executor = NodeExecutor(this, settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        terminalView = findViewById(R.id.terminal)
        statusView = findViewById(R.id.status)
        keyBar = findViewById(R.id.key_bar)

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    showSettingsDialog()
                    true
                }
                R.id.action_keyboard -> {
                    terminalView.showKeyboard()
                    true
                }
                R.id.action_restart -> {
                    restartSession()
                    true
                }
                else -> false
            }
        }

        terminalView.inputListener = this
        KeyBarController(keyBar, terminalView).bind()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            insets
        }

        terminalView.feedText("\u001b[36mOpenCode CLI\u001b[0m for armeabi-v7a\r\n")
        terminalView.feedText("No root. No Termux. OpenCode Server only.\r\n\r\n")

        if (!settings.isConfigured()) {
            statusView.text = getString(R.string.status_need_server)
            showSettingsDialog(autoStart = true)
        } else {
            startSession()
        }
    }

    private fun startSession() {
        if (started) {
            return
        }
        started = true
        statusView.text = getString(R.string.status_starting)
        executor.cols = terminalView.screen.cols
        executor.rows = terminalView.screen.rows
        executor.start(this)
    }

    private fun restartSession() {
        started = false
        executor.stop()
        terminalView.screen.reset()
        terminalView.invalidate()
        terminalView.feedText("Restarting OpenCode…\r\n")
        startSession()
    }

    override fun onTerminalInput(bytes: ByteArray) {
        executor.write(bytes)
    }

    override fun onTerminalSize(cols: Int, rows: Int) {
        executor.resize(cols, rows)
    }

    override fun onOutput(data: ByteArray) {
        runOnUiThread { terminalView.feed(data) }
    }

    override fun onExit(code: Int) {
        runOnUiThread {
            started = false
            statusView.text = getString(R.string.status_exited, code)
            terminalView.feedText("\r\n\u001b[33m[process exited $code]\u001b[0m\r\n")
        }
    }

    override fun onStatus(message: String) {
        runOnUiThread { statusView.text = message }
    }

    private fun showSettingsDialog(autoStart: Boolean = false) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val urlLabel = TextView(this).apply {
            text = getString(R.string.label_server_url)
            setTextColor(0xFFD6E0EA.toInt())
        }
        val urlInput = EditText(this).apply {
            hint = "http://192.168.1.10:8080"
            setText(settings.serverUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setHintTextColor(0xFF6B7280.toInt())
            setTextColor(0xFFEEF3F8.toInt())
        }

        val tokenLabel = TextView(this).apply {
            text = getString(R.string.label_auth_token)
            setTextColor(0xFFD6E0EA.toInt())
        }
        val tokenInput = EditText(this).apply {
            hint = getString(R.string.hint_token)
            setText(settings.apiKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            setHintTextColor(0xFF6B7280.toInt())
            setTextColor(0xFFEEF3F8.toInt())
        }

        val help = TextView(this).apply {
            text = getString(R.string.settings_help)
            setPadding(0, pad / 2, 0, 0)
            setTextColor(0xFF9CA3AF.toInt())
        }

        container.addView(urlLabel)
        container.addView(urlInput)
        container.addView(tokenLabel)
        container.addView(tokenInput)
        container.addView(help)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = urlInput.text?.toString()?.trim().orEmpty()
                val token = tokenInput.text?.toString()?.trim().orEmpty()
                if (url.isBlank()) {
                    urlInput.error = getString(R.string.error_url_required)
                    return@setOnClickListener
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    urlInput.error = getString(R.string.error_url_scheme)
                    return@setOnClickListener
                }
                settings.serverUrl = url.trimEnd('/')
                settings.apiKey = token
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                if (autoStart || !executor.isRunning()) {
                    restartSession()
                }
            }
        }
        dialog.show()
    }

    override fun onDestroy() {
        executor.stop()
        super.onDestroy()
    }
}
