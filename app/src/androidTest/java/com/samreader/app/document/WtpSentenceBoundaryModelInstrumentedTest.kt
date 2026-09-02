package com.samreader.app.document

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.system.measureTimeMillis
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WtpSentenceBoundaryModelInstrumentedTest {
    @Test
    fun scientificExamplesUseSemanticBoundariesOnDevice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scorer = WtpSentenceBoundaryModel(context)
        val examples = listOf(
            "This is people (only paper OC 173.4.2 said that). The next sentence." to listOf(48),
            "As shown in Fig. 2, the method by J. Smith is stable. A new result follows." to listOf(52),
            "Acceleration and gravitation [Fock (1959), p. 208]. A state follows." to listOf(50),
        )
        val elapsed = measureTimeMillis {
            examples.forEach { (text, expected) ->
                val scores = scorer.probabilities(text)
                val actual = scores.indices.filter { scores[it] >= scorer.threshold }
                assertEquals(expected, actual)
            }
        }
        Log.i("SamReaderWtpTest", "examples=${examples.size} elapsedMs=$elapsed")
    }

    companion object {
        @JvmStatic
        @AfterClass
        fun releaseModel() = WtpSentenceBoundaryModel.release()
    }
}
