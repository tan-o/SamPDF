package com.samreader.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppRouteCodecTest {
    @Test
    fun everyRouteRoundTripsThroughSavedState() {
        val routes = listOf(
            LibraryKey,
            ReaderKey("document-id"),
            SettingsKey(null),
            SettingsKey("document-id"),
            NoteCanvasKey("sentence-id"),
            VocabularyKey,
        )

        routes.forEach { route ->
            assertEquals(route, AppRouteCodec.decode(AppRouteCodec.encode(route)))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun readerRouteRequiresDocumentId() {
        AppRouteCodec.decode("reader")
    }

    @Test(expected = IllegalStateException::class)
    fun unknownRouteIsRejected() {
        AppRouteCodec.decode("legacy")
    }
}
