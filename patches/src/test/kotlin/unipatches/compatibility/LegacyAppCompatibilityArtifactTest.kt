package unipatches.compatibility

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils.applyTo
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import app.morphe.patcher.patch.resourcePatch
import helpers.manifest.NS_ANDROID
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.xml.parsers.DocumentBuilderFactory

/** Opt-in real-APK coverage: LEGACY_TEST_APK=/path/to/com.glu.gunbros2.apk LEGACY_TEST_OBB=/path/to/main.123.com.glu.gunbros2.obb ./gradlew :patches:test --rerun-tasks. */
class LegacyAppCompatibilityArtifactTest {
    @Test fun patchedGunBrosArtifactPreservesDownloaderAndExplicitLvlBinding() {
        val apkPath = System.getenv("LEGACY_TEST_APK")
        assumeNotNull(apkPath)
        val apk = File(apkPath!!)
        require(apk.isFile) { "LEGACY_TEST_APK must point to Gun Bros 2 APK" }
        val analyzer = findApkAnalyzer()
        org.junit.Assume.assumeTrue("Set APKANALYZER or ANDROID_HOME to inspect compiled manifest", analyzer != null)

        val patch = legacyAppCompatibilityPatch
        patch.options.values.forEach { it.reset() }
        patch.options["legacyCompatibilitySpoofTargetSdk"] = false
        patch.options["legacyCompatibilityTargetSdk"] = 24
        patch.options["legacyCompatibilityPlayStoreVisibility"] = true
        patch.options["legacyCompatibilityPackageVisibilityAllowlist"] = listOf("com.android.vending", "com.google.android.gms")
        patch.options["legacyCompatibilityRepairFileUriSharing"] = true
        val messages = mutableListOf<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { messages += record.message }
            override fun flush() {}
            override fun close() {}
        }
        val logger = Logger.getLogger("")
        logger.addHandler(handler)
        val output = File("build/legacy-artifacts").apply { mkdirs() }
        val artifact = File(output, "gun-bros2-license-preserved.apk")
        try {
            Patcher(PatcherConfig(apk, File(output, "gun-bros2-bypass-work"))).use { patcher ->
                patcher += setOf(patch)
                runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
                apk.copyTo(artifact, overwrite = true)
                patcher.get().applyTo(artifact)
            }

            val dexContainer = DexFileFactory.loadDexContainer(artifact, Opcodes.getDefault())
            val licenseChecker = dexContainer.dexEntryNames
                .mapNotNull { dexContainer.getEntry(it)?.dexFile }
                .flatMap { it.classes }
                .firstOrNull { it.type == "Lcom/google/android/vending/licensing/LicenseChecker;" }
                ?: error("Patched artifact is missing LicenseChecker")
            val checkAccess = licenseChecker.methods.firstOrNull {
                it.name == "checkAccess" && it.parameterTypes == listOf("Lcom/google/android/vending/licensing/LicenseCheckerCallback;")
            } ?: error("Patched artifact is missing LicenseChecker.checkAccess")
            val instructions = checkAccess.implementation?.instructions?.toList().orEmpty()
            assertTrue(
                "Final DEX must explicitly scope LVL binding to com.android.vending",
                hasPlayScopedLicenseServiceIntent(instructions),
            )
            val bindServiceMethods = instructions.mapNotNull { instruction ->
                (instruction as? ReferenceInstruction)?.reference as? MethodReference
            }.filter { it.name == "bindService" }
            assertTrue(
                "Final DEX must preserve Google Play license-service binding: $bindServiceMethods",
                bindServiceMethods.any { method ->
                    method.parameterTypes == listOf("Landroid/content/Intent;", "Landroid/content/ServiceConnection;", "I") &&
                        method.returnType == "Z"
                },
            )
            val allClasses = dexContainer.dexEntryNames.mapNotNull { dexContainer.getEntry(it)?.dexFile }.flatMap { it.classes }
            val unityPlayer = allClasses.firstOrNull { it.type == "Lcom/unity3d/player/UnityPlayer;" }
                ?: error("Patched artifact is missing UnityPlayer")
            val shareInstructions = unityPlayer.methods.mapNotNull { method ->
                val instructions = method.implementation?.instructions?.toList() ?: return@mapNotNull null
                if (instructions.any { instruction ->
                        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                        reference?.definingClass == "Lunipatch/compatcore/LegacyCompatRuntime;" && reference.name == "fileUriForShare"
                    }) method.name to instructions else null
            }
            assertEquals("Exactly one known Unity file URI share flow must be rewritten", 1, shareInstructions.size)
            val (shareMethodName, shareMethodInstructions) = shareInstructions.single()
            val fileUriCall = shareMethodInstructions.indexOfFirst { instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                reference?.definingClass == "Lunipatch/compatcore/LegacyCompatRuntime;" && reference.name == "fileUriForShare"
            }
            val intentDataCall = shareMethodInstructions.indexOfFirst { instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                reference?.definingClass == "Landroid/content/Intent;" && reference.name == "setDataAndType"
            }
            val grantCall = shareMethodInstructions.indexOfFirst { instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                reference?.definingClass == "Lunipatch/compatcore/LegacyCompatRuntime;" && reference.name == "grantFileUriReadPermission"
            }
            assertTrue("File URI conversion must precede Intent consumption in $shareMethodName", fileUriCall >= 0 && fileUriCall < intentDataCall)
            assertTrue("URI read grant must follow Intent data assignment in $shareMethodName", intentDataCall < grantCall)
            val fileProvider = allClasses.singleOrNull { it.type == "Lunipatch/compatcore/LegacyCompatFileProvider;" }
                ?: error("Patched artifact is missing LegacyCompatFileProvider")
            val providerOnCreate = fileProvider.methods.single { it.name == "onCreate" && it.parameterTypes.isEmpty() }
            assertTrue(
                "ContentProvider must initialize sharing context before application startup",
                providerOnCreate.implementation?.instructions?.any { instruction ->
                    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    reference?.definingClass == "Lunipatch/compatcore/LegacyCompatRuntime;" &&
                        reference.name == "init" && reference.parameterTypes == listOf("Landroid/content/Context;")
                } == true,
            )

            val manifestOutput = ProcessBuilder(analyzer!!.absolutePath, "manifest", "print", artifact.absolutePath)
                .redirectErrorStream(true)
                .start()
            val compiledManifest = manifestOutput.inputStream.use { it.readBytes() }
            assertEquals(String(compiledManifest, Charsets.UTF_8), 0, manifestOutput.waitFor())
            val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(ByteArrayInputStream(compiledManifest))
            assertTrue(hasLauncher(manifest, "com.google.android.vending.expansion.downloader_impl.DownloaderActivity"))
            assertFalse(hasLauncher(manifest, "com.glu.plugins.AUnityInstaller.UnityLauncherActivity"))
            val application = manifest.getElementsByTagName("application").item(0) as org.w3c.dom.Element
            val packageQueries = (0 until manifest.getElementsByTagName("package").length).map { index ->
                (manifest.getElementsByTagName("package").item(index) as org.w3c.dom.Element).getAttributeNS(NS_ANDROID, "name")
            }.toSet()
            assertEquals(16, originalTargetSdk(manifest))
            assertTrue(packageQueries.isEmpty())
            assertEquals(0, manifest.getElementsByTagName("uses-permission").let { permissions ->
                (0 until permissions.length).count { index ->
                    (permissions.item(index) as org.w3c.dom.Element).getAttributeNS(NS_ANDROID, "name") == "android.permission.QUERY_ALL_PACKAGES"
                }
            })
            assertApkNativeLibrariesUnchanged(apk, artifact)
            ZipFile(artifact).use { patched ->
                assertNull(patched.getEntry(expansionAssetPath("main.123.com.glu.gunbros2.obb")))
            }
            assertTrue(messages.any { it.contains("package visibility queries require effective targetSdk 30+") && it.contains("target=16") })
            assertTrue(messages.any { it.contains("JNI entrypoints=unknown") })
            assertTrue(messages.any { it.contains("runtime loader outcome remain unknown") })
            assertTrue(messages.any { it.contains("original service action retained") && it.contains("license verification remains enabled") })
            assertTrue(messages.any { it.contains("private file-sharing provider verified") })
            val provider = (0 until manifest.getElementsByTagName("provider").length)
                .map { manifest.getElementsByTagName("provider").item(it) as org.w3c.dom.Element }
                .single { it.getAttributeNS(NS_ANDROID, "name") == LEGACY_FILE_PROVIDER_CLASS }
            assertEquals("com.glu.gunbros2.legacycompat.fileprovider", provider.getAttributeNS(NS_ANDROID, "authorities"))
            assertEquals("false", provider.getAttributeNS(NS_ANDROID, "exported"))
            assertEquals("true", provider.getAttributeNS(NS_ANDROID, "grantUriPermissions"))
            val providerPathsReference = (0 until provider.getElementsByTagName("meta-data").length)
                .map { provider.getElementsByTagName("meta-data").item(it) as org.w3c.dom.Element }
                .single { it.getAttributeNS(NS_ANDROID, "name") == "android.support.FILE_PROVIDER_PATHS" }
                .getAttributeNS(NS_ANDROID, "resource")
            assertTrue("Compiled manifest must reference provider paths resource: $providerPathsReference", Regex("@(xml/$LEGACY_FILE_PROVIDER_PATHS_RESOURCE|ref/0x[0-9a-fA-F]{8})").matches(providerPathsReference))
            val providerPaths = ProcessBuilder(
                analyzer!!.absolutePath,
                "resources",
                "xml",
                "--file",
                "res/xml/$LEGACY_FILE_PROVIDER_PATHS_RESOURCE.xml",
                artifact.absolutePath,
            ).redirectErrorStream(true).start()
            val providerPathsXml = providerPaths.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            assertEquals(providerPathsXml, 0, providerPaths.waitFor())
            listOf("files-path", "cache-path", "external-files-path", "external-cache-path").forEach { tag ->
                assertTrue("Compiled provider paths missing $tag: $providerPathsXml", providerPathsXml.contains(tag))
            }
        } finally {
            logger.removeHandler(handler)
            patch.options.values.forEach { it.reset() }
        }
    }

    @Test fun targetSdk23OrNewerRejectsGunBrosTextRelocations() {
        val input = System.getenv("LEGACY_TEST_APK")
        assumeNotNull(input)
        val apk = File(input!!)
        require(apk.isFile) { "LEGACY_TEST_APK must point to Gun Bros 2 APK" }
        val patch = legacyAppCompatibilityPatch
        patch.options.values.forEach { it.reset() }
        try {
            patch.options["legacyCompatibilitySpoofTargetSdk"] = true
            patch.options["legacyCompatibilityTargetSdk"] = 24
            val failure = runPatcherFailure(apk, File("build/legacy-artifacts/textrel-target24"), patch)
            assertNotNull("TargetSdk 24 artifact must fail closed for Gun Bros DT_TEXTREL", failure)
            assertTrue(failure!!.contains("DT_TEXTREL"))
            assertTrue(failure.contains("targetSdk 24"))
        } finally {
            patch.options.values.forEach { it.reset() }
        }
    }

    @Test fun gunBrosEmbeddingObbFailsWhenItWouldSkipLicenseVerification() {
        val input = System.getenv("LEGACY_TEST_APK")
        val obbPath = System.getenv("LEGACY_TEST_OBB")
        assumeNotNull(input, obbPath)
        val apk = File(input!!)
        val obb = File(obbPath!!)
        require(apk.isFile && obb.isFile) { "Set LEGACY_TEST_APK and LEGACY_TEST_OBB to the original Gun Bros 2 artifacts" }
        val originalObbSha256 = expansionSha256(obb)
        val patch = legacyAppCompatibilityPatch
        patch.options.values.forEach { it.reset() }
        try {
            patch.options["legacyCompatibilityEmbedExpansionObb"] = true
            patch.options["legacyCompatibilityExpansionObbPath"] = obb.absolutePath
            val failure = runPatcherFailure(apk, File("build/legacy-artifacts/lvl-obb-embed"), patch)
            assertNotNull("Pre-staging OBB must not make the recognized downloader skip LVL", failure)
            assertTrue(failure!!.contains("pre-staging the OBB"))
            assertEquals(originalObbSha256, expansionSha256(obb))
        } finally {
            patch.options.values.forEach { it.reset() }
        }
    }

    @Test fun gunBrosDownloaderBypassFailsWhenItWouldSkipLicenseVerification() {
        val input = System.getenv("LEGACY_TEST_APK")
        val obbPath = System.getenv("LEGACY_TEST_OBB")
        assumeNotNull(input, obbPath)
        val apk = File(input!!)
        val obb = File(obbPath!!)
        require(apk.isFile && obb.isFile) { "Set LEGACY_TEST_APK and LEGACY_TEST_OBB to the original Gun Bros 2 artifacts" }
        val patch = legacyAppCompatibilityPatch
        patch.options.values.forEach { it.reset() }
        try {
            patch.options["legacyCompatibilityBypassExpansionDownloader"] = true
            patch.options["legacyCompatibilityEmbedExpansionObb"] = true
            patch.options["legacyCompatibilityExpansionObbPath"] = obb.absolutePath
            val failure = runPatcherFailure(apk, File("build/legacy-artifacts/lvl-bypass"), patch)
            assertNotNull("Gun Bros downloader bypass must not make LVL unreachable", failure)
            assertTrue(failure!!.contains("license verification cannot be proven"))
        } finally {
            patch.options.values.forEach { it.reset() }
        }
    }

    @Test fun gunBrosNativeRelocationRejectsExistingProxyLibrariesAndPreservesObb() {
        val input = System.getenv("LEGACY_TEST_APK")
        val obbPath = System.getenv("LEGACY_TEST_OBB")
        assumeNotNull(input, obbPath)
        val apk = File(input!!)
        val obb = File(obbPath!!)
        require(apk.isFile && obb.isFile) { "Set LEGACY_TEST_APK and LEGACY_TEST_OBB to the original Gun Bros 2 artifacts" }
        val originalObbSha256 = expansionSha256(obb)
        val patch = legacyAppCompatibilityPatch
        patch.options.values.forEach { it.reset() }
        try {
            patch.options["legacyCompatibilitySpoofTargetSdk"] = false
            patch.options["legacyCompatibilityTargetSdk"] = 24
            patch.options["legacyCompatibilityRelocateExpansionNativeLibraries"] = true
            patch.options["legacyCompatibilityExpansionObbPath"] = obb.absolutePath
            val failure = runPatcherFailure(apk, File("build/legacy-artifacts/native-collision"), patch)
            assertNotNull("Relocation must refuse Gun Bros APK proxy-library collisions", failure)
            assertTrue(failure!!.contains("overwrite existing APK libraries"))
            assertTrue(failure.contains("lib/armeabi-v7a/libunity.so") || failure.contains("lib/armeabi-v7a/libmono.so"))
            assertEquals(originalObbSha256, expansionSha256(obb))
        } finally {
            patch.options.values.forEach { it.reset() }
        }
    }

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
                Triple("source16", 16, "custom"),
                Triple("disabled", 0, "custom"),
                Triple("diagnostics", -1, "android_14_plus"),
            )) {
                patch.options.values.forEach { it.reset() }
                patch.options["legacyCompatibilityPreset"] = preset
                patch.options["legacyCompatibilitySpoofTargetSdk"] = target > 16 || target == -1
                patch.options["legacyCompatibilityTargetSdk"] = if (target > 0) target else 34
                patch.options["legacyCompatibilityRepairExportFlags"] = true
                patch.options["legacyCompatibilityDiagnosticsOnly"] = target == -1
                if (target == -1) {
                    // These must survive era selection but must not run in diagnostics-only mode.
                    patch.options["legacyCompatibilityHiddenApi"] = true
                    patch.options["legacyCompatibilityOpenIabReceiverRegistrationMode"] = OPEN_IAB_FORCE
                }
                val expected = if (target > 16) target else 16
                val verify = resourcePatch {
                    dependsOn(patch)
                    execute {
                        get("AndroidManifest.xml", false).inputStream().use { inputStream ->
                            document(inputStream).use { manifest ->
                                assertEquals("case=$name target=$target messages=$messages", expected, originalTargetSdk(manifest))
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
                val artifact = apk.copyTo(File(output, "$name.apk"), overwrite = true)
                Patcher(PatcherConfig(apk, File(output, "$name-work"))).use { patcher ->
                    patcher += setOf(verify)
                    runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
                    patcher.get().applyTo(artifact)
                }
                val options = messages.single { it.startsWith("Legacy App Compatibility options:") }
                assertFalse(options.contains("raw"))
                assertFalse(options.contains("effective"))
                assertTrue(options.contains("targetSdk=${if (target > 0) target else 34}"))
                if (target == -1) {
                    assertTrue(options.contains("bypassHiddenApi=true"))
                    assertTrue(options.contains("openIabReceiverRegistrationMode=force"))
                    assertTrue(messages.any { it.contains("diagnostics-only mode applied no APK changes") })
                    assertTrue(messages.any { it.contains("package visibility queries requires API 30") && it.contains("No newer manifest attributes were applied in diagnostics-only mode") })
                    assertTrue(messages.any { it.contains("native libraries would be unsafe at planned targetSdk 34") && it.contains("not applied in diagnostics-only mode") && it.contains("DT_TEXTREL") })
                    assertFalse(messages.any { it.contains("injected LegacyCompatRuntime") })
                }
            }
        } finally {
            logger.removeHandler(handler)
            patch.options.values.forEach { it.reset() }
        }
    }

    @Test fun sourceFrameworkPreflightRejectsModernAttributesBeforeStockPatcherEncoder() {
        val input = System.getenv("LEGACY_TEST_APK") ?: return
        val apk = File(input)
        assumeTrue(apk.isFile)
        val patch = legacyAppCompatibilityPatch
        patch.options.values.forEach { it.reset() }
        try {
            patch.options["legacyCompatibilitySpoofTargetSdk"] = true
            patch.options["legacyCompatibilityTargetSdk"] = 34
            patch.options["legacyCompatibilityPlayStoreVisibility"] = true
            patch.options["legacyCompatibilityPackageVisibilityAllowlist"] = listOf("com.android.vending")
            patch.options["legacyCompatibilityAllowSelectedCleartextDomains"] = true
            patch.options["legacyCompatibilityCleartextDomains"] = listOf("api.example.com")
            patch.options["legacyCompatibilityApplyForegroundServiceTypes"] = true
            patch.options["legacyCompatibilityForegroundServiceTypeMappings"] = listOf(
                "com.google.android.vending.expansion.downloader_impl.GDownloaderService=dataSync",
            )
            val failure = runPatcherFailure(apk, File("build/legacy-artifacts/framework-gate"), patch)
            assertNotNull("legacy framework gate must reject attributes unavailable to input framework", failure)
            assertTrue(failure!!.contains("select as low as 16"))
            assertTrue(failure.contains("package visibility queries requires API 30"))
            assertTrue(failure.contains("domain-scoped cleartext requires API 24"))
            assertTrue(failure.contains("foregroundServiceType requires API 28"))
            assertFalse(failure.contains("Unknown attribute name"))
        } finally {
            patch.options.values.forEach { it.reset() }
        }
    }

    private fun runPatcherFailure(apk: File, work: File, patch: app.morphe.patcher.patch.Patch<*>): String? {
        val failure = runCatching {
            Patcher(PatcherConfig(apk, work)).use { patcher ->
                patcher += setOf(patch)
                runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
            }
        }.exceptionOrNull() ?: return null
        return generateSequence(failure) { it.cause }.joinToString("\\n") { it.message.orEmpty() }
    }

    private fun hasLauncher(document: org.w3c.dom.Document, activityName: String): Boolean {
        val activities = document.getElementsByTagName("activity")
        return (0 until activities.length).any { index ->
            val activity = activities.item(index) as? org.w3c.dom.Element ?: return@any false
            if (activity.getAttributeNS(NS_ANDROID, "name") != activityName) return@any false
            val filters = activity.getElementsByTagName("intent-filter")
            (0 until filters.length).any { filterIndex ->
                val filter = filters.item(filterIndex) as? org.w3c.dom.Element ?: return@any false
                val hasMain = (0 until filter.getElementsByTagName("action").length).any { actionIndex ->
                    (filter.getElementsByTagName("action").item(actionIndex) as? org.w3c.dom.Element)
                        ?.getAttributeNS(NS_ANDROID, "name") == "android.intent.action.MAIN"
                }
                val hasLauncher = (0 until filter.getElementsByTagName("category").length).any { categoryIndex ->
                    (filter.getElementsByTagName("category").item(categoryIndex) as? org.w3c.dom.Element)
                        ?.getAttributeNS(NS_ANDROID, "name") == "android.intent.category.LAUNCHER"
                }
                hasMain && hasLauncher
            }
        }
    }

    private fun assertApkNativeLibrariesUnchanged(originalApk: File, patchedApk: File) {
        val nativeLibraryPaths = listOf("lib/armeabi-v7a/libmono.so", "lib/armeabi-v7a/libunity.so")
        ZipFile(originalApk).use { original ->
            ZipFile(patchedApk).use { patched ->
                nativeLibraryPaths.forEach { path ->
                    assertEquals("APK native library changed at $path", zipEntryDigest(original, path), zipEntryDigest(patched, path))
                }
            }
        }
    }

    private fun zipEntryDigest(zip: ZipFile, path: String): Pair<Long, String> {
        val entry = zip.getEntry(path) ?: error("Missing ZIP entry $path")
        return zip.getInputStream(entry).use(::streamDigest)
    }

    private fun streamDigest(input: java.io.InputStream): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            digest.update(buffer, 0, count)
        }
        val hex = "0123456789abcdef"
        val bytes = digest.digest()
        val hash = buildString(bytes.size * 2) {
            for (byte in bytes) {
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
        return total to hash
    }

    private fun findApkAnalyzer(): File? {
        val sdkRoots = listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
        val candidates = buildList {
            System.getenv("APKANALYZER")?.let { add(File(it)) }
            sdkRoots.forEach { root -> add(File(root, "cmdline-tools/latest/bin/apkanalyzer")) }
            add(File(System.getProperty("user.home"), "Library/Android/sdk/cmdline-tools/latest/bin/apkanalyzer"))
            add(File(System.getProperty("user.home"), "Android/Sdk/cmdline-tools/latest/bin/apkanalyzer"))
        }
        return candidates.firstOrNull { it.isFile }
    }
}
