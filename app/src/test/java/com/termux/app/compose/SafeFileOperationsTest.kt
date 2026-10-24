package com.termux.app.compose

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SafeFileOperationsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Operation should have failed") } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) { }
    }

    @Test fun copyingIntoSelfOrDescendantIsRejected() {
        val source = temporary.newFolder("source")
        File(source, "keep").writeText("original")
        rejected { SafeFileOperations.transfer(source, source, false) }
        rejected { SafeFileOperations.transfer(source, File(source, "child"), false) }
        assertFalse(File(source, "child").exists())
    }

    @Test fun existingDestinationIsPreserved() {
        val source = temporary.newFile("source").apply { writeText("new") }
        val destination = temporary.newFile("destination").apply { writeText("old") }
        rejected { SafeFileOperations.transfer(source, destination, true) }
        assertEquals("old", destination.readText())
        assertEquals("new", source.readText())
    }

    @Test fun copiesNestedDirectories() {
        val source = temporary.newFolder("source")
        File(source, "nested").mkdir()
        File(source, "nested/data").writeText("data")
        val destination = File(temporary.root, "destination")
        SafeFileOperations.transfer(source, destination, false)
        assertEquals("data", File(destination, "nested/data").readText())
        assertTrue(source.exists())
    }

    @Test fun movesWithoutLosingContents() {
        val source = temporary.newFile("source").apply { writeText("data") }
        val destination = File(temporary.root, "destination")
        SafeFileOperations.transfer(source, destination, true)
        assertFalse(source.exists())
        assertEquals("data", destination.readText())
    }
}
