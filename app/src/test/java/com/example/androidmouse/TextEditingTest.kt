package com.example.androidmouse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextEditingTest {

    @Test
    fun insertAtCaret() {
        assertEquals(TextEditing.Result("heXllo", 3), TextEditing.insert("hello", 2, 2, "X"))
    }

    @Test
    fun insertReplacesSelection() {
        assertEquals(TextEditing.Result("hXo", 2), TextEditing.insert("hello", 1, 4, "X"))
    }

    @Test
    fun insertWithInvalidSelectionAppends() {
        assertEquals(TextEditing.Result("helloX", 6), TextEditing.insert("hello", -1, -1, "X"))
    }

    @Test
    fun insertIntoEmpty() {
        assertEquals(TextEditing.Result("ab", 2), TextEditing.insert("", 0, 0, "ab"))
    }

    @Test
    fun backspaceDeletesSelection() {
        assertEquals(TextEditing.Result("ho", 1), TextEditing.backspace("hello", 1, 4))
    }

    @Test
    fun backspaceDeletesCharBeforeCaret() {
        assertEquals(TextEditing.Result("hllo", 1), TextEditing.backspace("hello", 2, 2))
    }

    @Test
    fun backspaceAtStartIsNoOp() {
        assertNull(TextEditing.backspace("hello", 0, 0))
    }

    @Test
    fun backspaceRemovesSurrogatePair() {
        val s = "a😀"
        assertEquals(TextEditing.Result("a", 1), TextEditing.backspace(s, 3, 3))
    }

    @Test
    fun backspaceWithInvalidSelectionUsesEnd() {
        assertEquals(TextEditing.Result("hell", 4), TextEditing.backspace("hello", -1, -1))
    }
}
