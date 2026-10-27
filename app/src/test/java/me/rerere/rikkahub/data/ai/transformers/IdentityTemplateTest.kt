package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.*
import org.junit.Test

class IdentityTemplateTest {
    @Test fun onlyExactIdentityTemplateCanSkipRendering() {
        assertTrue(isIdentityMessageTemplate("{{ message }}"))
        assertFalse(isIdentityMessageTemplate("prefix {{ message }}"))
        assertFalse(isIdentityMessageTemplate("{{ message }}\n"))
        assertFalse(isIdentityMessageTemplate("{{ role }}: {{ message }}"))
    }
}
