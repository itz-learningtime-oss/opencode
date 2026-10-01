package ai.opencode.cli.ui

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import ai.opencode.cli.terminal.TerminalKey
import ai.opencode.cli.terminal.TerminalView

class KeyBarController(
    private val bar: LinearLayout,
    private val terminal: TerminalView
) {
    private data class KeySpec(
        val label: String,
        val sticky: Boolean = false,
        val onTap: (Button) -> Unit
    )

    fun bind() {
        bar.removeAllViews()
        val keys = listOf(
            KeySpec("CTRL", sticky = true) { btn ->
                terminal.ctrlHeld = !terminal.ctrlHeld
                updateSticky(btn, terminal.ctrlHeld)
            },
            KeySpec("ALT", sticky = true) { btn ->
                terminal.altHeld = !terminal.altHeld
                updateSticky(btn, terminal.altHeld)
            },
            KeySpec("ESC") { terminal.sendKey(TerminalKey.ESC) },
            KeySpec("TAB") { terminal.sendKey(TerminalKey.TAB) },
            KeySpec("^C") { terminal.sendControl('C') },
            KeySpec("^D") { terminal.sendControl('D') },
            KeySpec("^Z") { terminal.sendControl('Z') },
            KeySpec("^L") { terminal.sendControl('L') },
            KeySpec("HOME") { terminal.sendKey(TerminalKey.HOME) },
            KeySpec("END") { terminal.sendKey(TerminalKey.END) },
            KeySpec("PGUP") { terminal.sendKey(TerminalKey.PAGE_UP) },
            KeySpec("PGDN") { terminal.sendKey(TerminalKey.PAGE_DOWN) },
            KeySpec("↑") { terminal.sendKey(TerminalKey.UP) },
            KeySpec("↓") { terminal.sendKey(TerminalKey.DOWN) },
            KeySpec("←") { terminal.sendKey(TerminalKey.LEFT) },
            KeySpec("→") { terminal.sendKey(TerminalKey.RIGHT) },
            KeySpec("KB") { terminal.showKeyboard() }
        )
        val density = bar.resources.displayMetrics.density
        val hPad = (10 * density).toInt()
        val vPad = (6 * density).toInt()
        val margin = (4 * density).toInt()
        for (spec in keys) {
            val button = Button(bar.context).apply {
                text = spec.label
                isAllCaps = false
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(hPad, vPad, hPad, vPad)
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                gravity = Gravity.CENTER
                setBackgroundResource(ai.opencode.cli.R.drawable.bg_key)
                setTextColor(0xFFE8EEF4.toInt())
                setOnClickListener { spec.onTap(this) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(margin, 0, margin, 0)
            bar.addView(button, lp)
        }
    }

    private fun updateSticky(button: Button, active: Boolean) {
        button.alpha = if (active) 1f else 0.85f
        button.setBackgroundResource(
            if (active) ai.opencode.cli.R.drawable.bg_key_active else ai.opencode.cli.R.drawable.bg_key
        )
    }
}
