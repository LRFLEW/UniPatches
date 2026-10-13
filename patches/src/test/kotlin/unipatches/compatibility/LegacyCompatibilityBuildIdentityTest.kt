package unipatches.compatibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LegacyCompatibilityBuildIdentityTest {
    @Test
    fun fingerprintsKnownArtifactBytesWithSha256() {
        val artifact = File.createTempFile("legacy-patch-id-", ".jar")
        try {
            artifact.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", LegacyCompatibilityBuildIdentity.sha256(artifact))
        } finally {
            artifact.delete()
        }
    }

    @Test
    fun loadedImplementationIdentityHasAnExplicitArtifactScope() {
        val identity = LegacyCompatibilityBuildIdentity.current()
        assertTrue(identity.scope == "code-source-file" || identity.scope == "class-file")
        assertTrue(identity.sha256.matches(Regex("[0-9a-f]{64}")))
    }
}
