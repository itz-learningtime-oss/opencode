package ai.opencode.cli.terminal

class TerminalScreen(
    var cols: Int = 80,
    var rows: Int = 24
) {
    data class Cell(
        var char: Char = ' ',
        var fg: Int = DEFAULT_FG,
        var bg: Int = DEFAULT_BG,
        var bold: Boolean = false,
        var underline: Boolean = false,
        var inverse: Boolean = false
    ) {
        fun copyFrom(other: Cell) {
            char = other.char
            fg = other.fg
            bg = other.bg
            bold = other.bold
            underline = other.underline
            inverse = other.inverse
        }

        fun reset() {
            char = ' '
            fg = DEFAULT_FG
            bg = DEFAULT_BG
            bold = false
            underline = false
            inverse = false
        }
    }

    var cursorX = 0
    var cursorY = 0
    var showCursor = true
    var scrollTop = 0
    var scrollBottom = rows - 1

    private var savedX = 0
    private var savedY = 0
    private var curFg = DEFAULT_FG
    private var curBg = DEFAULT_BG
    private var curBold = false
    private var curUnderline = false
    private var curInverse = false

    var buffer: Array<Array<Cell>> = createBuffer(cols, rows)
        private set

    @Volatile
    var generation: Long = 0L
        private set

    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceAtLeast(2)
        val r = newRows.coerceAtLeast(2)
        if (c == cols && r == rows) {
            return
        }
        val next = createBuffer(c, r)
        val copyCols = minOf(cols, c)
        val copyRows = minOf(rows, r)
        for (y in 0 until copyRows) {
            for (x in 0 until copyCols) {
                next[y][x].copyFrom(buffer[y][x])
            }
        }
        cols = c
        rows = r
        buffer = next
        scrollTop = 0
        scrollBottom = rows - 1
        clampCursor()
        bump()
    }

    fun putChar(ch: Char) {
        if (ch == '\n') {
            lineFeed()
            return
        }
        if (ch == '\r') {
            carriageReturn()
            return
        }
        if (cursorX >= cols) {
            cursorX = 0
            lineFeed()
        }
        val cell = buffer[cursorY][cursorX]
        cell.char = ch
        cell.fg = curFg
        cell.bg = curBg
        cell.bold = curBold
        cell.underline = curUnderline
        cell.inverse = curInverse
        cursorX++
        bump()
    }

    fun backspace() {
        if (cursorX > 0) {
            cursorX--
            bump()
        }
    }

    fun tab() {
        cursorX = ((cursorX / 8) + 1) * 8
        if (cursorX >= cols) {
            cursorX = cols - 1
        }
        bump()
    }

    fun lineFeed() {
        if (cursorY >= scrollBottom) {
            scrollUp(1)
            cursorY = scrollBottom
        } else {
            cursorY++
        }
        bump()
    }

    fun reverseIndex() {
        if (cursorY <= scrollTop) {
            scrollDown(1)
        } else {
            cursorY--
        }
        bump()
    }

    fun carriageReturn() {
        cursorX = 0
        bump()
    }

    fun moveCursor(dx: Int, dy: Int) {
        cursorX += dx
        cursorY += dy
        clampCursor()
        bump()
    }

    fun setCursor(x: Int, y: Int) {
        cursorX = x
        cursorY = y
        clampCursor()
        bump()
    }

    fun clampCursor() {
        if (cursorX < 0) cursorX = 0
        if (cursorY < 0) cursorY = 0
        if (cursorX >= cols) cursorX = cols - 1
        if (cursorY >= rows) cursorY = rows - 1
    }

    fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (y in (cursorY + 1) until rows) clearRow(y)
            }
            1 -> {
                for (y in 0 until cursorY) clearRow(y)
                eraseInLine(1)
            }
            2, 3 -> {
                for (y in 0 until rows) clearRow(y)
            }
        }
        bump()
    }

    fun eraseInLine(mode: Int) {
        when (mode) {
            0 -> for (x in cursorX until cols) buffer[cursorY][x].reset()
            1 -> for (x in 0..cursorX) buffer[cursorY][x].reset()
            2 -> clearRow(cursorY)
        }
        bump()
    }

    fun insertLines(count: Int) {
        val n = count.coerceAtLeast(1)
        for (i in 0 until n) {
            for (y in scrollBottom downTo cursorY + 1) {
                val src = buffer[y - 1]
                val dst = buffer[y]
                for (x in 0 until cols) dst[x].copyFrom(src[x])
            }
            clearRow(cursorY)
        }
        bump()
    }

    fun deleteLines(count: Int) {
        val n = count.coerceAtLeast(1)
        for (i in 0 until n) {
            for (y in cursorY until scrollBottom) {
                val src = buffer[y + 1]
                val dst = buffer[y]
                for (x in 0 until cols) dst[x].copyFrom(src[x])
            }
            clearRow(scrollBottom)
        }
        bump()
    }

    fun insertChars(count: Int) {
        val n = count.coerceAtLeast(1)
        val row = buffer[cursorY]
        for (x in (cols - 1) downTo cursorX + n) {
            row[x].copyFrom(row[x - n])
        }
        for (x in cursorX until minOf(cursorX + n, cols)) {
            row[x].reset()
        }
        bump()
    }

    fun deleteChars(count: Int) {
        val n = count.coerceAtLeast(1)
        val row = buffer[cursorY]
        var dst = cursorX
        var src = cursorX + n
        while (src < cols) {
            row[dst].copyFrom(row[src])
            dst++
            src++
        }
        while (dst < cols) {
            row[dst].reset()
            dst++
        }
        bump()
    }

    fun scrollUp(count: Int) {
        val n = count.coerceAtLeast(1)
        for (i in 0 until n) {
            for (y in scrollTop until scrollBottom) {
                val src = buffer[y + 1]
                val dst = buffer[y]
                for (x in 0 until cols) dst[x].copyFrom(src[x])
            }
            clearRow(scrollBottom)
        }
        bump()
    }

    fun scrollDown(count: Int) {
        val n = count.coerceAtLeast(1)
        for (i in 0 until n) {
            for (y in scrollBottom downTo scrollTop + 1) {
                val src = buffer[y - 1]
                val dst = buffer[y]
                for (x in 0 until cols) dst[x].copyFrom(src[x])
            }
            clearRow(scrollTop)
        }
        bump()
    }

    fun setScrollRegion(top: Int, bottom: Int) {
        scrollTop = top.coerceIn(0, rows - 1)
        scrollBottom = bottom.coerceIn(scrollTop, rows - 1)
    }

    fun saveCursor() {
        savedX = cursorX
        savedY = cursorY
    }

    fun restoreCursor() {
        cursorX = savedX
        cursorY = savedY
        clampCursor()
        bump()
    }

    fun applySgr(params: IntArray) {
        var i = 0
        while (i < params.size) {
            when (val p = params[i]) {
                0 -> {
                    curFg = DEFAULT_FG
                    curBg = DEFAULT_BG
                    curBold = false
                    curUnderline = false
                    curInverse = false
                }
                1 -> curBold = true
                4 -> curUnderline = true
                7 -> curInverse = true
                22 -> curBold = false
                24 -> curUnderline = false
                27 -> curInverse = false
                in 30..37 -> curFg = ANSI_COLORS[p - 30]
                39 -> curFg = DEFAULT_FG
                in 40..47 -> curBg = ANSI_COLORS[p - 40]
                49 -> curBg = DEFAULT_BG
                in 90..97 -> curFg = ANSI_BRIGHT[p - 90]
                in 100..107 -> curBg = ANSI_BRIGHT[p - 100]
                38, 48 -> {
                    val isFg = p == 38
                    if (i + 1 < params.size) {
                        when (params[i + 1]) {
                            5 -> if (i + 2 < params.size) {
                                val color = xterm256(params[i + 2])
                                if (isFg) curFg = color else curBg = color
                                i += 2
                            }
                            2 -> if (i + 4 < params.size) {
                                val color = rgb(params[i + 2], params[i + 3], params[i + 4])
                                if (isFg) curFg = color else curBg = color
                                i += 4
                            }
                        }
                    }
                }
            }
            i++
        }
    }

    fun reset() {
        for (y in 0 until rows) clearRow(y)
        cursorX = 0
        cursorY = 0
        curFg = DEFAULT_FG
        curBg = DEFAULT_BG
        curBold = false
        curUnderline = false
        curInverse = false
        scrollTop = 0
        scrollBottom = rows - 1
        bump()
    }

    fun bell() {
    }

    private fun clearRow(y: Int) {
        if (y !in 0 until rows) return
        for (x in 0 until cols) buffer[y][x].reset()
    }

    private fun bump() {
        generation++
    }

    private fun createBuffer(c: Int, r: Int): Array<Array<Cell>> {
        return Array(r) { Array(c) { Cell() } }
    }

    companion object {
        const val DEFAULT_FG = 0xFFD6E0EA.toInt()
        const val DEFAULT_BG = 0xFF0B1117.toInt()

        val ANSI_COLORS = intArrayOf(
            0xFF1C1C1C.toInt(),
            0xFFE06C75.toInt(),
            0xFF98C379.toInt(),
            0xFFE5C07B.toInt(),
            0xFF61AFEF.toInt(),
            0xFFC678DD.toInt(),
            0xFF56B6C2.toInt(),
            0xFFD6E0EA.toInt()
        )

        val ANSI_BRIGHT = intArrayOf(
            0xFF5C6370.toInt(),
            0xFFFF6B6B.toInt(),
            0xFFB5E890.toInt(),
            0xFFFFD67A.toInt(),
            0xFF79C0FF.toInt(),
            0xFFE0A0FF.toInt(),
            0xFF7EE7F0.toInt(),
            0xFFFFFFFF.toInt()
        )

        fun rgb(r: Int, g: Int, b: Int): Int {
            return (0xFF shl 24) or
                ((r.coerceIn(0, 255) shl 16)) or
                ((g.coerceIn(0, 255) shl 8)) or
                b.coerceIn(0, 255)
        }

        fun xterm256(index: Int): Int {
            val n = index.coerceIn(0, 255)
            if (n < 8) return ANSI_COLORS[n]
            if (n < 16) return ANSI_BRIGHT[n - 8]
            if (n < 232) {
                val v = n - 16
                val r = v / 36
                val g = (v % 36) / 6
                val b = v % 6
                fun level(x: Int) = if (x == 0) 0 else 55 + x * 40
                return rgb(level(r), level(g), level(b))
            }
            val gray = 8 + (n - 232) * 10
            return rgb(gray, gray, gray)
        }
    }
}
