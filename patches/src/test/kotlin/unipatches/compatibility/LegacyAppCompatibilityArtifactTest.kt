package unipatches.compatibility

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patcher.patch.resourcePatch
import helpers.manifest.NS_ANDROID
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

/** Opt-in real-APK coverage: LEGACY_TEST_APK=/path/to/com.glu.gunbros2.apk ./gradlew :patches:test --rerun-tasks. */
class LegacyAppCompatibilityArtifactTest {
    @Test fun selectedOptionsReachDependenciesAndCompiledArtifacts() {
        val input = System.getenv("LEGACY_TEST_APK")
        assumeNotNull(input)
        val apk = File(input!!)
        require(apk.isFile) { "LEGACY_TEST_APK must point to Gun Bros 2 APK" }
        val output = File("build/legacy-artifacts").apply { mkdirs() }
        val patch = legacyAppCompatibilityPatch // Construct before assigning options, as Manager does.
        val messages = mutableListOf<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { messages += record.message }
            override fun flush() {}
            override fun close() {}
        }
        val logger = Logger.getLogger("")
        logger.addHandler(handler)
        try {
            for ((name, target, preset) in listOf(
                Triple("target24", 24, "custom"),
                Triple("target34", 34, "android_4_x"),
                Triple("disabled", 0, "custom"),
                Triple("diagnostics", -1, "android_14_plus"),
            )) {
                patch.options.values.forEach { it.reset() }
                patch.options["legacyCompatibilityPreset"] = preset
                patch.options["legacyCompatibilitySpoofTargetSdk"] = target != 0
                patch.options["legacyCompatibilityTargetSdk"] = if (target > 0) target else 34
                patch.options["legacyCompatibilityRepairExportFlags"] = true
                patch.options["legacyCompatibilityDiagnosticsOnly"] = target == -1
                if (target == -1) {
                    // These must survive era selection but must not run in diagnostics-only mode.
                    patch.options["legacyCompatibilityEmbedExpansionObb"] = true
                    patch.options["legacyCompatibilityRelocateExpansionNativeLibraries"] = true
                    patch.options["legacyCompatibilityRemoveRelocatedNativeLibrariesFromObb"] = true
                    patch.options["legacyCompatibilityBypassExpansionDownloader"] = true
                    patch.options["legacyCompatibilityExpansionObbPath"] = "/missing/main.123.com.glu.gunbros2.obb"
                    patch.options["legacyCompatibilityHiddenApi"] = true
                    patch.options["legacyCompatibilityOpenIabReceiverRegistrationMode"] = OPEN_IAB_FORCE
                }
                val expected = if (target > 0) target else 16
                val verify = resourcePatch {
                    dependsOn(patch)
                    execute {
                        get("AndroidManifest.xml", false).inputStream().use { inputStream ->
                            document(inputStream).use { manifest ->
                                assertEquals(expected, originalTargetSdk(manifest))
                                if (expected >= 31) for (tag in exportedComponentTags) {
                                    val components = manifest.getElementsByTagName(tag)
                                    for (i in 0 until components.length) {
                                        val component = components.item(i) as org.w3c.dom.Element
                                        if (component.getElementsByTagName("intent-filter").length > 0)
                                            assertTrue("$tag missing exported", component.hasAttributeNS(NS_ANDROID, "exported") || component.hasAttribute("android:exported"))
                                    }
                                }
                            }
                        }
                    }
                }
                messages.clear()
                Patcher(PatcherConfig(apk, File(output, "$name-work"))).use { patcher ->
                    patcher += setOf(verify)
                    runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
                    val artifact = apk.copyTo(File(output, "$name.apk"), overwrite = true)
                    patcher.get().applyTo(artifact)
                }
                val options = messages.single { it.startsWith("Legacy App Compatibility options:") }
                assertFalse(options.contains("raw"))
                assertFalse(options.contains("effective"))
                assertTrue(options.contains("targetSdk=${if (target > 0) target else 34}"))
                if (target == -1) {
                    for (name in listOf("embedExpansionObb", "relocateExpansionNativeLibraries", "removeRelocatedNativeLibrariesFromObb", "bypassExpansionDownloader"))
                        assertTrue(options.contains("$name=true"))
                    assertTrue(options.contains("expansionObbPath=main.123.com.glu.gunbros2.obb"))
                    assertTrue(options.contains("openIabReceiverRegistrationMode=force"))
                    assertFalse(messages.any { it.contains("injected LegacyCompatRuntime") })
                }
            }
        } finally {
            logger.removeHandler(handler)
            patch.options.values.forEach { it.reset() }
        }
    }
}
