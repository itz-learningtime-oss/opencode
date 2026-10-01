package ai.opencode.cli.terminal

class AnsiParser(private val screen: TerminalScreen) {

    private enum class State {
        GROUND,
        ESCAPE,
        CSI,
        OSC,
        OSC_ESC,
        CHARSET
    }

    private var state = State.GROUND
    private val params = StringBuilder()
    private val osc = StringBuilder()
    private var utf8Need = 0
    private var utf8Acc = 0

    fun feed(bytes: ByteArray) {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            when {
                utf8Need > 0 -> {
                    if (b and 0xC0 == 0x80) {
                        utf8Acc = (utf8Acc shl 6) or (b and 0x3F)
                        utf8Need--
                        if (utf8Need == 0) {
                            consumeCodePoint(utf8Acc)
                        }
                    } else {
                        utf8Need = 0
                        consumeByte(b)
                    }
                }
                b and 0x80 == 0 -> consumeByte(b)
                b and 0xE0 == 0xC0 -> {
                    utf8Need = 1
                    utf8Acc = b and 0x1F
                }
                b and 0xF0 == 0xE0 -> {
                    utf8Need = 2
                    utf8Acc = b and 0x0F
                }
                b and 0xF8 == 0xF0 -> {
                    utf8Need = 3
                    utf8Acc = b and 0x07
                }
                else -> consumeByte(b)
            }
            i++
        }
    }

    private fun consumeByte(b: Int) {
        when (state) {
            State.GROUND -> {
                when (b) {
                    0x1B -> state = State.ESCAPE
                    0x07 -> screen.bell()
                    0x08 -> screen.backspace()
                    0x09 -> screen.tab()
                    0x0A, 0x0B, 0x0C -> screen.lineFeed()
                    0x0D -> screen.carriageReturn()
                    0x0E, 0x0F -> { }
                    else -> if (b >= 0x20) screen.putChar(b.toChar())
                }
            }
            State.ESCAPE -> {
                when (b.toChar()) {
                    '[' -> {
                        params.setLength(0)
                        state = State.CSI
                    }
                    ']' -> {
                        osc.setLength(0)
                        state = State.OSC
                    }
                    '(' , ')' , '*' , '+' -> state = State.CHARSET
                    '7' -> {
                        screen.saveCursor()
                        state = State.GROUND
                    }
                    '8' -> {
                        screen.restoreCursor()
                        state = State.GROUND
                    }
                    'c' -> {
                        screen.reset()
                        state = State.GROUND
                    }
                    'D' -> {
                        screen.lineFeed()
                        state = State.GROUND
                    }
                    'E' -> {
                        screen.carriageReturn()
                        screen.lineFeed()
                        state = State.GROUND
                    }
                    'M' -> {
                        screen.reverseIndex()
                        state = State.GROUND
                    }
                    else -> state = State.GROUND
                }
            }
            State.CHARSET -> state = State.GROUND
            State.CSI -> {
                if (b in 0x30..0x3F) {
                    params.append(b.toChar())
                } else if (b in 0x20..0x2F) {
                    params.append(b.toChar())
                } else {
                    handleCsi(b.toChar())
                    state = State.GROUND
                }
            }
            State.OSC -> {
                when (b) {
                    0x07 -> {
                        handleOsc()
                        state = State.GROUND
                    }
                    0x1B -> state = State.OSC_ESC
                    else -> osc.append(b.toChar())
                }
            }
            State.OSC_ESC -> {
                if (b == 0x5C) {
                    handleOsc()
                }
                state = State.GROUND
            }
        }
    }

    private fun consumeCodePoint(cp: Int) {
        if (state == State.GROUND) {
            screen.putChar(if (cp > 0xFFFF) '?' else cp.toChar())
        }
    }

    private fun handleOsc() {
        osc.setLength(0)
    }

    private fun parseInts(): IntArray {
        if (params.isEmpty()) {
            return intArrayOf()
        }
        val parts = params.toString().split(';')
        val out = IntArray(parts.size)
        for (i in parts.indices) {
            out[i] = parts[i].filter { it.isDigit() }.toIntOrNull() ?: 0
        }
        return out
    }

    private fun handleCsi(finalChar: Char) {
        val p = parseInts()
        fun n(index: Int, default: Int): Int {
            val v = if (index < p.size) p[index] else 0
            return if (v == 0) default else v
        }
        when (finalChar) {
            'A' -> screen.moveCursor(0, -n(0, 1))
            'B' -> screen.moveCursor(0, n(0, 1))
            'C' -> screen.moveCursor(n(0, 1), 0)
            'D' -> screen.moveCursor(-n(0, 1), 0)
            'E' -> {
                screen.cursorY += n(0, 1)
                screen.cursorX = 0
                screen.clampCursor()
            }
            'F' -> {
                screen.cursorY -= n(0, 1)
                screen.cursorX = 0
                screen.clampCursor()
            }
            'G' -> {
                screen.cursorX = n(0, 1) - 1
                screen.clampCursor()
            }
            'H', 'f' -> {
                val row = n(0, 1) - 1
                val col = n(1, 1) - 1
                screen.setCursor(col, row)
            }
            'J' -> screen.eraseInDisplay(if (p.isEmpty()) 0 else p[0])
            'K' -> screen.eraseInLine(if (p.isEmpty()) 0 else p[0])
            'L' -> screen.insertLines(n(0, 1))
            'M' -> screen.deleteLines(n(0, 1))
            'P' -> screen.deleteChars(n(0, 1))
            '@' -> screen.insertChars(n(0, 1))
            'S' -> screen.scrollUp(n(0, 1))
            'T' -> screen.scrollDown(n(0, 1))
            'd' -> {
                screen.cursorY = n(0, 1) - 1
                screen.clampCursor()
            }
            'm' -> screen.applySgr(if (p.isEmpty()) intArrayOf(0) else p)
            'n' -> { }
            'h', 'l' -> { }
            'r' -> {
                val top = n(0, 1) - 1
                val bottom = if (p.size > 1) p[1] - 1 else screen.rows - 1
                screen.setScrollRegion(top, bottom)
            }
            's' -> screen.saveCursor()
            'u' -> screen.restoreCursor()
        }
        params.setLength(0)
    }
}
