package com.termux.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellQuoteTest {
    @Test fun handlesSingleQuote() {
        assertEquals("'a'\\''b'", sshQuote("a'b"))
    }

    @Test fun keepsShellSyntaxLiteral() {
        assertEquals("'host; $(id) `id` & space'", sshQuote("host; $(id) `id` & space"))
    }
}
