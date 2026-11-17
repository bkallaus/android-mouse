package com.example.androidmouse

/** Pure text-splicing helpers used for injecting text into a focused field. */
object TextEditing {

    data class Result(val text: String, val caret: Int)

    /** Replaces the selection (or inserts at the caret) with [insert]. */
    fun insert(current: String, selStart: Int, selEnd: Int, insert: String): Result {
        val (start, end) = normalize(current, selStart, selEnd)
        val text = current.substring(0, start) + insert + current.substring(end)
        return Result(text, start + insert.length)
    }

    /** Deletes the selection, or the char before the caret. Null when nothing can be deleted. */
    fun backspace(current: String, selStart: Int, selEnd: Int): Result? {
        val (start, end) = normalize(current, selStart, selEnd)
        if (start != end) {
            return Result(current.substring(0, start) + current.substring(end), start)
        }
        if (start == 0) return null
        var from = start - 1
        if (from > 0 && current[from].isLowSurrogate() && current[from - 1].isHighSurrogate()) {
            from--
        }
        return Result(current.substring(0, from) + current.substring(start), from)
    }

    private fun normalize(text: String, selStart: Int, selEnd: Int): Pair<Int, Int> {
        val len = text.length
        if (selStart < 0 || selEnd < 0) return len to len
        val a = selStart.coerceAtMost(len)
        val b = selEnd.coerceAtMost(len)
        return minOf(a, b) to maxOf(a, b)
    }
}
