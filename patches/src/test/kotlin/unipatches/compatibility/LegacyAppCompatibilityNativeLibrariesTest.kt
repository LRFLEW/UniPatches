package unipatches.compatibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.io.File

class LegacyAppCompatibilityNativeLibrariesTest {
    @Test
    fun `detects ELF32 DT_TEXTREL and soname`() {
        val metadata = inspectElf(elf32(textRel = true))

        assertEquals(32, metadata.bits)
        assertTrue(metadata.littleEndian)
        assertEquals(40, metadata.machine)
        assertEquals("liblegacy.so", metadata.soname)
        assertTrue(metadata.hasTextRelocations)
    }

    @Test
    fun `detects ELF64 DF_TEXTREL and clean libraries`() {
        val textRel = inspectElf(elf64(textRelFlags = true))
        val clean = inspectElf(elf64(textRelFlags = false))

        assertEquals(64, textRel.bits)
        assertEquals(62, textRel.machine)
        assertTrue(textRel.hasTextRelocations)
        assertFalse(clean.hasTextRelocations)
        assertEquals("liblegacy.so", clean.soname)
    }

    @Test
    fun `recognizes only matching proxy and full library identities`() {
        val proxy = inspectElf(elf32(textRel = false, soname = "libunity_proxy.so"))
        val replacement = inspectElf(elf32(textRel = false, soname = "libunity.so"))
        val unrelated = inspectElf(elf32(textRel = false, soname = "libother.so"))
        val wrongMachine = inspectElf(elf64(textRelFlags = false))

        assertTrue(isMatchingProxyNativeLibraryPair(proxy, replacement, "libunity.so"))
        assertFalse(isMatchingProxyNativeLibraryPair(proxy, unrelated, "libunity.so"))
        assertFalse(isMatchingProxyNativeLibraryPair(proxy, wrongMachine, "libunity.so"))
        assertFalse(isMatchingProxyNativeLibraryPair(replacement, replacement, "libunity.so"))
        assertFalse(isMatchingProxyNativeLibraryPair(proxy, replacement, "not-a-library"))
    }

    @Test
    fun `target sdk boundary blocks textrel at 23 but not 22`() {
        val issue = NativeLibraryInspection(
            "lib/armeabi-v7a/libunity.so",
            inspectElf(elf32(textRel = true)),
        )
        assertTrue(nativeLibraryCompatibilityIssues(listOf(issue), 22).isEmpty())
        val issues = nativeLibraryCompatibilityIssues(listOf(issue), 23)
        assertEquals(1, issues.size)
        assertTrue(issues.single().contains("DT_TEXTREL"))
        assertTrue(issues.single().contains("targetSdk 23"))
    }

    @Test
    fun `native scan uses actual target in diagnostics and validates planned target bounds`() {
        assertEquals(16, nativeTargetSdkForInspection(16, 34, spoofEnabled = true, diagnosticsOnly = true))
        assertEquals(34, nativeTargetSdkForInspection(16, 34, spoofEnabled = true, diagnosticsOnly = false))
        assertEquals(24, nativeTargetSdkForInspection(24, 22, spoofEnabled = true, diagnosticsOnly = false))
        assertEquals(16, nativeTargetSdkForInspection(16, 22, spoofEnabled = true, diagnosticsOnly = false))
        assertEquals(16, nativeTargetSdkForInspection(16, 34, spoofEnabled = false, diagnosticsOnly = false))
        assertEquals(34, plannedNativeTargetSdk(spoofEnabled = true, requestedTargetSdk = 34))
        assertNull(plannedNativeTargetSdk(spoofEnabled = true, requestedTargetSdk = 22))
    }

    @Test
    fun `malformed and truncated elf are unknown not safe`() {
        assertThrows(IllegalArgumentException::class.java) { inspectElf(byteArrayOf(1, 2, 3)) }
        assertThrows(IllegalArgumentException::class.java) { inspectElf(elf32(textRel = false).copyOf(40)) }
        val noDynamic = elf32(textRel = false).apply {
            ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(84, 4)
        }
        assertThrows(IllegalArgumentException::class.java) { inspectElf(noDynamic) }
        val multipleDynamic = elf32(textRel = false).apply {
            ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(52, 2)
        }
        assertThrows(IllegalArgumentException::class.java) { inspectElf(multipleDynamic) }

        val malformed = File.createTempFile("not-elf-", ".so").apply { writeBytes(byteArrayOf(0, 1)) }
        val inspection = inspectNativeLibrary("lib/arm64-v8a/bad.so", malformed)
        malformed.delete()
        assertNotNull(inspection.error)
        assertTrue(nativeLibraryCompatibilityIssues(listOf(inspection), 23).single().contains("ELF inspection failed"))
    }

    @Test
    fun `diagnostics report JNI entrypoints as unknown and sanitize malformed paths`() {
        val message = formatNativeLibraryInspection(
            NativeLibraryInspection(
                "assets/libs/armeabi-v7a/evil\nlib.so",
                inspectElf(elf32(textRel = false)),
                sizeBytes = 123,
                sha256 = "a".repeat(64),
                destination = "lib/armeabi-v7a/evil.so",
                collision = true,
            ),
            24,
        )
        assertTrue(message.contains("JNI entrypoints=unknown"))
        assertTrue(message.contains("dynamic symbols and registration are not inspected"))
        assertTrue(message.contains("source=assets/libs/armeabi-v7a/evil lib.so"))
        assertTrue(message.contains("destination=lib/armeabi-v7a/evil.so"))
        assertTrue(message.contains("sizeBytes=123"))
        assertTrue(message.contains("sha256=${"a".repeat(64)}"))
        assertTrue(message.contains("relocationDestinationCollision=yes"))
        assertFalse(message.contains("evil\nlib.so"))
    }

    @Test
    fun `inspects compressed zip library entry`() {
        val obb = File.createTempFile("legacy-elf-", ".obb")
        ZipOutputStream(obb.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/libs/armeabi-v7a/libunity.so"))
            zip.write(elf32(textRel = true))
            zip.closeEntry()
        }
        try {
            ZipFile(obb).use { zip ->
                val entry = zip.getEntry("assets/libs/armeabi-v7a/libunity.so")
                val inspection = inspectNativeLibrary(zip, entry)
                assertTrue(inspection.metadata!!.hasTextRelocations)
                assertEquals("armeabi-v7a", inspection.abi)
            }
        } finally {
            obb.delete()
        }
    }

    private fun elf32(textRel: Boolean, soname: String = "liblegacy.so"): ByteArray {
        val bytes = ByteArray(512)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte(); bytes[2] = 'L'.code.toByte(); bytes[3] = 'F'.code.toByte()
        bytes[4] = 1; bytes[5] = 1
        buffer.putShort(16, 3.toShort())
        buffer.putShort(18, 40.toShort())
        buffer.putInt(28, 52)
        buffer.putShort(40, 52.toShort())
        buffer.putShort(42, 32.toShort())
        buffer.putShort(44, 2.toShort())
        buffer.putInt(52, 1); buffer.putInt(56, 0); buffer.putInt(60, 0); buffer.putInt(68, bytes.size)
        buffer.putInt(84, 2); buffer.putInt(88, 128); buffer.putInt(92, 128); buffer.putInt(100, 40)
        var dynamicOffset = 128
        fun tag(tag: Int, value: Int) {
            buffer.putInt(dynamicOffset, tag); buffer.putInt(dynamicOffset + 4, value); dynamicOffset += 8
        }
        tag(5, 400); tag(10, soname.toByteArray().size + 2); tag(14, 1)
        if (textRel) tag(22, 0)
        tag(0, 0)
        "\u0000$soname\u0000".toByteArray().copyInto(bytes, 400)
        return bytes
    }

    private fun elf64(textRelFlags: Boolean): ByteArray {
        val bytes = ByteArray(1024)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte(); bytes[2] = 'L'.code.toByte(); bytes[3] = 'F'.code.toByte()
        bytes[4] = 2; bytes[5] = 1
        buffer.putShort(16, 3.toShort())
        buffer.putShort(18, 62.toShort())
        buffer.putLong(32, 64)
        buffer.putShort(52, 64.toShort())
        buffer.putShort(54, 56.toShort())
        buffer.putShort(56, 2.toShort())
        buffer.putInt(64, 1); buffer.putLong(72, 0); buffer.putLong(80, 0); buffer.putLong(96, bytes.size.toLong())
        buffer.putInt(120, 2); buffer.putLong(128, 256); buffer.putLong(136, 256); buffer.putLong(152, if (textRelFlags) 80 else 64)
        var dynamicOffset = 256
        fun tag(tag: Long, value: Long) {
            buffer.putLong(dynamicOffset, tag); buffer.putLong(dynamicOffset + 8, value); dynamicOffset += 16
        }
        tag(5, 800); tag(10, 14); tag(14, 1)
        if (textRelFlags) tag(30, 4)
        tag(0, 0)
        "\u0000liblegacy.so\u0000".toByteArray().copyInto(bytes, 800)
        return bytes
    }
}
