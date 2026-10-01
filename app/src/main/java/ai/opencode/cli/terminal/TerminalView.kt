package ai.opencode.cli.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.floor
import kotlin.math.max

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface InputListener {
        fun onTerminalInput(bytes: ByteArray)
        fun onTerminalSize(cols: Int, rows: Int)
    }

    val screen = TerminalScreen(80, 24)
    private val parser = AnsiParser(screen)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        color = TerminalScreen.DEFAULT_FG
        textSize = sp(12.5f)
    }
    private val bgPaint = Paint()
    private val cursorPaint = Paint().apply {
        color = 0xCC7EE7F0.toInt()
    }
    private val textBounds = Rect()

    private var cellWidth = 8f
    private var cellHeight = 16f
    private var baseline = 12f
    private var lastGen = -1L
    private var cursorVisible = true
    private var lastTouchY = 0f

    var inputListener: InputListener? = null
    var ctrlHeld = false
    var altHeld = false

    private val blinkRunnable = object : Runnable {
        override fun run() {
            cursorVisible = !cursorVisible
            invalidate()
            mainHandler.postDelayed(this, 530L)
        }
    }

    @Volatile
    private var invalidatePosted = false
    private val invalidateRunnable = Runnable {
        invalidatePosted = false
        invalidate()
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        setBackgroundColor(TerminalScreen.DEFAULT_BG)
        measureFont()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mainHandler.post(blinkRunnable)
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(blinkRunnable)
        mainHandler.removeCallbacks(invalidateRunnable)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    private fun measureFont() {
        textPaint.getTextBounds("W", 0, 1, textBounds)
        val fm = textPaint.fontMetrics
        cellWidth = textPaint.measureText("W")
        cellHeight = (fm.descent - fm.ascent) * 1.08f
        baseline = -fm.ascent + 1f
    }

    private fun relayout() {
        if (width <= 0 || height <= 0 || cellWidth <= 0f || cellHeight <= 0f) {
            return
        }
        val cols = max(2, floor(width / cellWidth).toInt())
        val rows = max(2, floor(height / cellHeight).toInt())
        if (cols != screen.cols || rows != screen.rows) {
            screen.resize(cols, rows)
            inputListener?.onTerminalSize(cols, rows)
        }
        invalidate()
    }

    fun feed(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(screen) {
            parser.feed(bytes)
        }
        if (!invalidatePosted) {
            invalidatePosted = true
            mainHandler.post(invalidateRunnable)
        }
    }

    fun feedText(text: String) {
        feed(text.toByteArray(Charsets.UTF_8))
    }

    fun send(text: String) {
        send(text.toByteArray(Charsets.UTF_8))
    }

    fun send(bytes: ByteArray) {
        inputListener?.onTerminalInput(bytes)
    }

    fun sendControl(letter: Char) {
        val c = letter.uppercaseChar().code
        if (c in 64..95) {
            send(byteArrayOf((c - 64).toByte()))
        }
    }

    fun sendKey(key: TerminalKey) {
        send(key.bytes)
    }

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(TerminalScreen.DEFAULT_BG)
        val gen: Long
        synchronized(screen) {
            gen = screen.generation
            val rows = screen.rows
            val cols = screen.cols
            for (y in 0 until rows) {
                val row = screen.buffer[y]
                var x = 0
                while (x < cols) {
                    val cell = row[x]
                    var run = 1
                    while (x + run < cols && sameStyle(row[x + run], cell) && row[x + run].char != ' ') {
                        run++
                    }
                    if (cell.char == ' ' && cell.bg == TerminalScreen.DEFAULT_BG && !cell.inverse) {
                        x++
                        continue
                    }
                    val fg = if (cell.inverse) cell.bg else cell.fg
                    val bg = if (cell.inverse) cell.fg else cell.bg
                    val left = x * cellWidth
                    val top = y * cellHeight
                    if (bg != TerminalScreen.DEFAULT_BG || cell.inverse) {
                        bgPaint.color = bg
                        canvas.drawRect(left, top, left + run * cellWidth, top + cellHeight, bgPaint)
                    }
                    textPaint.color = fg
                    textPaint.isFakeBoldText = cell.bold
                    textPaint.isUnderlineText = cell.underline
                    if (run == 1) {
                        if (cell.char != ' ') {
                            canvas.drawText(cell.char.toString(), left, top + baseline, textPaint)
                        }
                    } else {
                        val sb = StringBuilder(run)
                        for (i in 0 until run) sb.append(row[x + i].char)
                        canvas.drawText(sb.toString(), left, top + baseline, textPaint)
                    }
                    x += run
                }
            }
            if (screen.showCursor && cursorVisible) {
                val cx = screen.cursorX.coerceIn(0, cols - 1)
                val cy = screen.cursorY.coerceIn(0, rows - 1)
                val left = cx * cellWidth
                val top = cy * cellHeight
                canvas.drawRect(left, top, left + cellWidth, top + cellHeight, cursorPaint)
                val ch = screen.buffer[cy][cx]
                if (ch.char != ' ') {
                    textPaint.color = TerminalScreen.DEFAULT_BG
                    canvas.drawText(ch.char.toString(), left, top + baseline, textPaint)
                }
            }
        }
        lastGen = gen
    }

    private fun sameStyle(a: TerminalScreen.Cell, b: TerminalScreen.Cell): Boolean {
        return a.fg == b.fg && a.bg == b.bg && a.bold == b.bold &&
            a.underline == b.underline && a.inverse == b.inverse
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (kotlin.math.abs(event.y - lastTouchY) < 24f) {
                    showKeyboard()
                    performClick()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return super.onKeyDown(keyCode, event)
        }
        if (handleSpecialKey(keyCode, event)) {
            return true
        }
        val unicode = event.getUnicodeChar(event.metaState)
        if (unicode != 0) {
            val ch = unicode
            if (ctrlHeld || event.isCtrlPressed) {
                val letter = ch.toChar().uppercaseChar().code
                if (letter in 64..95) {
                    send(byteArrayOf((letter - 64).toByte()))
                    ctrlHeld = false
                    return true
                }
            }
            if (altHeld || event.isAltPressed) {
                send(byteArrayOf(0x1B, ch.toByte()))
                altHeld = false
                return true
            }
            send(String(Character.toChars(ch)))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    fun handleSpecialKey(keyCode: Int, event: KeyEvent?): Boolean {
        val bytes = when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> byteArrayOf(0x0D)
            KeyEvent.KEYCODE_DEL -> byteArrayOf(0x7F)
            KeyEvent.KEYCODE_TAB -> byteArrayOf(0x09)
            KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(0x1B)
            KeyEvent.KEYCODE_DPAD_UP -> TerminalKey.UP.bytes
            KeyEvent.KEYCODE_DPAD_DOWN -> TerminalKey.DOWN.bytes
            KeyEvent.KEYCODE_DPAD_RIGHT -> TerminalKey.RIGHT.bytes
            KeyEvent.KEYCODE_DPAD_LEFT -> TerminalKey.LEFT.bytes
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_HOME -> TerminalKey.HOME.bytes
            KeyEvent.KEYCODE_MOVE_END -> TerminalKey.END.bytes
            KeyEvent.KEYCODE_PAGE_UP -> TerminalKey.PAGE_UP.bytes
            KeyEvent.KEYCODE_PAGE_DOWN -> TerminalKey.PAGE_DOWN.bytes
            KeyEvent.KEYCODE_F1 -> TerminalKey.F1.bytes
            KeyEvent.KEYCODE_F2 -> TerminalKey.F2.bytes
            KeyEvent.KEYCODE_F3 -> TerminalKey.F3.bytes
            KeyEvent.KEYCODE_F4 -> TerminalKey.F4.bytes
            else -> null
        }
        if (bytes != null) {
            send(bytes)
            return true
        }
        if (event != null && keyCode == KeyEvent.KEYCODE_C && event.isCtrlPressed) {
            sendControl('C')
            return true
        }
        return false
    }

    private fun sp(value: Float): Float {
        return value * resources.displayMetrics.scaledDensity
    }

    private class TerminalInputConnection(private val view: TerminalView) :
        BaseInputConnection(view, true) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (!text.isNullOrEmpty()) {
                view.send(text.toString())
            }
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength > 0) {
                view.send(byteArrayOf(0x7F))
            }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (view.handleSpecialKey(event.keyCode, event)) {
                    return true
                }
                val ch = event.getUnicodeChar(event.metaState)
                if (ch != 0) {
                    view.send(String(Character.toChars(ch)))
                    return true
                }
            }
            return super.sendKeyEvent(event)
        }
    }
}

enum class TerminalKey(val bytes: ByteArray) {
    UP("\u001b[A".toByteArray()),
    DOWN("\u001b[B".toByteArray()),
    RIGHT("\u001b[C".toByteArray()),
    LEFT("\u001b[D".toByteArray()),
    HOME("\u001b[H".toByteArray()),
    END("\u001b[F".toByteArray()),
    PAGE_UP("\u001b[5~".toByteArray()),
    PAGE_DOWN("\u001b[6~".toByteArray()),
    ESC("\u001b".toByteArray()),
    TAB("\t".toByteArray()),
    ENTER("\r".toByteArray()),
    F1("\u001bOP".toByteArray()),
    F2("\u001bOQ".toByteArray()),
    F3("\u001bOR".toByteArray()),
    F4("\u001bOS".toByteArray())
}
