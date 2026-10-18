package unipatches.compatibility

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class LegacyAppCompatibilityFrameworkRequirementsTest {
    private fun manifest(targetSdk: String?, compileSdk: String? = null, platformBuild: Int? = null): Document {
        val sdk = listOfNotNull(
            targetSdk?.let { "android:targetSdkVersion=\"$it\"" },
            compileSdk?.let { "android:compileSdkVersion=\"$it\"" },
        ).joinToString(" ")
        val xml = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"${platformBuild?.let { " platformBuildVersionCode=\"$it\"" }.orEmpty()}>
              <uses-sdk $sdk />
              <application />
            </manifest>
        """.trimIndent()
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
    }

    @Test
    fun `legacy source target fails optional newer manifest attributes even if spoof was planned`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            validateInputManifestFramework(manifest(targetSdk = "16"), listOf(ManifestFrameworkRequirement("domain-scoped cleartext", 24)))
        }
        assertTrue(failure.message!!.contains("targetSdkVersion=16"))
        assertTrue(failure.message!!.contains("requires API 24"))
    }

    @Test
    fun `input framework at required api is accepted`() {
        validateInputManifestFramework(manifest(targetSdk = "24"), listOf(ManifestFrameworkRequirement("domain cleartext", 24)))
    }

    @Test
    fun `compile sdk marker takes precedence over legacy target sdk fallback`() {
        validateInputManifestFramework(
            manifest(targetSdk = "16", compileSdk = "35"),
            listOf(ManifestFrameworkRequirement("foregroundServiceType", 28)),
        )
    }

    @Test
    fun `lower platform build marker remains fail closed`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            validateInputManifestFramework(
                manifest(targetSdk = "16", compileSdk = "35", platformBuild = 16),
                listOf(ManifestFrameworkRequirement("foregroundServiceType", 28)),
            )
        }
        assertTrue(failure.message!!.contains("select as low as 16"))
    }

    @Test
    fun `no optional framework features means no constraint`() {
        validateInputManifestFramework(manifest(targetSdk = "16"), emptyList())
    }

    @Test
    fun `missing numeric framework metadata fails closed for optional attributes`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            validateInputManifestFramework(manifest(targetSdk = null), listOf(ManifestFrameworkRequirement("FGS type", 28)))
        }
        assertTrue(failure.message!!.contains("no numeric framework API is declared"))
    }
}
