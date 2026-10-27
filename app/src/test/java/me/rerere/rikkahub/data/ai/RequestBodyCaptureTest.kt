package me.rerere.rikkahub.data.ai

import org.junit.Assert.*
import org.junit.Test

class RequestBodyCaptureTest {
    @Test fun smallReplayableBodyIsCaptured() {
        assertTrue(shouldCaptureRequestBody(0, false, false))
        assertTrue(shouldCaptureRequestBody(MAX_LOG_BODY_BYTES, false, false))
    }
    @Test fun largeAndUnknownBodiesNotConsumed() {
        assertFalse(shouldCaptureRequestBody(MAX_LOG_BODY_BYTES + 1, false, false))
        assertFalse(shouldCaptureRequestBody(-1, false, false))
        assertFalse(shouldCaptureRequestBody(Long.MAX_VALUE, false, false))
    }
    @Test fun oneShotAndDuplexNotConsumed() {
        assertFalse(shouldCaptureRequestBody(10, true, false))
        assertFalse(shouldCaptureRequestBody(10, false, true))
    }
}
