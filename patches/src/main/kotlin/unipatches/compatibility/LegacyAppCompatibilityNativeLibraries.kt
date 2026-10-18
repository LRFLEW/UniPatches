package unipatches.compatibility

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal data class ElfNativeMetadata(
    val bits: Int,
    val littleEndian: Boolean,
    val machine: Int,
    val soname: String?,
    val hasTextRelocations: Boolean,
)

internal data class NativeLibraryInspection(
    val path: String,
    val metadata: ElfNativeMetadata?,
    val error: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val destination: String? = null,
    val collision: Boolean? = null,
) {
    val abi: String get() = path.split('/').dropLast(1).lastOrNull().orEmpty()
}

private data class ElfProgramHeader(
    val type: Long,
    val offset: Long,
    val virtualAddress: Long,
    val fileSize: Long,
)

private const val ELF_MAGIC = "\u007fELF"
private const val PT_LOAD = 1L
private const val PT_DYNAMIC = 2L
private const val DT_NULL = 0L
private const val DT_STRTAB = 5L
private const val DT_STRSZ = 10L
private const val DT_SONAME = 14L
private const val DT_TEXTREL = 22L
private const val DT_FLAGS = 30L
private const val DF_TEXTREL = 0x4L
private const val MAX_PROGRAM_HEADERS = 1024
private const val MAX_DYNAMIC_BYTES = 1 shl 20
private const val MAX_STRING_BYTES = 512

internal fun isMatchingProxyNativeLibraryPair(
    existingProxy: ElfNativeMetadata,
    obbLibrary: ElfNativeMetadata,
    fileName: String,
): Boolean {
    if (!fileName.startsWith("lib") || !fileName.endsWith(".so")) return false
    val proxySoname = "${fileName.removeSuffix(".so")}_proxy.so"
    return existingProxy.soname == proxySoname && obbLibrary.soname == fileName &&
        existingProxy.bits == obbLibrary.bits && existingProxy.littleEndian == obbLibrary.littleEndian &&
        existingProxy.machine == obbLibrary.machine
}

internal fun inspectElf(bytes: ByteArray): ElfNativeMetadata =
    inspectElf(bytes.size.toLong()) { offset, size ->
        require(offset >= 0 && size >= 0 && offset <= bytes.size.toLong() - size) { "ELF read outside fixture" }
        bytes.copyOfRange(offset.toInt(), offset.toInt() + size)
    }

internal fun inspectElfFile(file: File): ElfNativeMetadata = RandomAccessFile(file, "r").use { input ->
    inspectElf(input.length()) { offset, size ->
        val bytes = ByteArray(size)
        input.seek(offset)
        input.readFully(bytes)
        bytes
    }
}

internal fun inspectElfZipEntry(zip: ZipFile, entry: ZipEntry): ElfNativeMetadata =
    withNativeEntryFile(zip, entry, ::inspectElfFile)

private fun inspectElf(size: Long, readAt: (Long, Int) -> ByteArray): ElfNativeMetadata {
    require(size >= 52) { "truncated ELF header" }
    val ident = readAt(0, 16)
    require(ident.copyOfRange(0, 4).toString(Charsets.ISO_8859_1) == ELF_MAGIC) { "invalid ELF magic" }
    val elfClass = ident[4].toInt() and 0xff
    val dataEncoding = ident[5].toInt() and 0xff
    require(elfClass == 1 || elfClass == 2) { "unsupported ELF class $elfClass" }
    require(dataEncoding == 1 || dataEncoding == 2) { "unsupported ELF byte order $dataEncoding" }
    val bits = if (elfClass == 1) 32 else 64
    val littleEndian = dataEncoding == 1
    val order = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
    val headerSize = if (bits == 32) 52 else 64
    require(size >= headerSize) { "truncated ELF${bits} header" }
    val header = ByteBuffer.wrap(readAt(0, headerSize)).order(order)
    require(unsignedShort(header.getShort(16)) == 3) { "ELF file is not a shared object" }
    val machine = unsignedShort(header.getShort(18))
    val programOffset = if (bits == 32) unsignedInt(header.getInt(28)) else unsignedLong(header.getLong(32))
    val elfHeaderSize = unsignedShort(header.getShort(if (bits == 32) 40 else 52))
    val programEntrySize = unsignedShort(header.getShort(if (bits == 32) 42 else 54))
    val programCount = unsignedShort(header.getShort(if (bits == 32) 44 else 56))
    val minimumEntrySize = if (bits == 32) 32 else 56
    require(elfHeaderSize >= headerSize && programCount in 1..MAX_PROGRAM_HEADERS) { "invalid ELF program-header table" }
    require(programEntrySize in minimumEntrySize..4096) { "invalid ELF program-header entry size" }
    val tableSize = programEntrySize.toLong() * programCount
    require(tableSize <= MAX_DYNAMIC_BYTES) { "oversized ELF program-header table" }
    requireRange(programOffset, tableSize, size, "program-header table")
    val table = ByteBuffer.wrap(readAt(programOffset, tableSize.toInt())).order(order)
    val headers = (0 until programCount).map { index ->
        val base = index * programEntrySize
        val type = unsignedInt(table.getInt(base))
        val offset = if (bits == 32) unsignedInt(table.getInt(base + 4)) else unsignedLong(table.getLong(base + 8))
        val virtualAddress = if (bits == 32) unsignedInt(table.getInt(base + 8)) else unsignedLong(table.getLong(base + 16))
        val fileSize = if (bits == 32) unsignedInt(table.getInt(base + 16)) else unsignedLong(table.getLong(base + 32))
        if (type == PT_LOAD || type == PT_DYNAMIC) requireRange(offset, fileSize, size, "program segment")
        ElfProgramHeader(type, offset, virtualAddress, fileSize)
    }
    val dynamicHeaders = headers.filter { it.type == PT_DYNAMIC }
    require(dynamicHeaders.size == 1) { "ELF has missing or multiple PT_DYNAMIC segments" }
    val dynamic = dynamicHeaders.single()
    val entrySize = if (bits == 32) 8 else 16
    require(dynamic.fileSize in entrySize.toLong()..MAX_DYNAMIC_BYTES.toLong() && dynamic.fileSize % entrySize == 0L) {
        "invalid or oversized ELF dynamic segment"
    }
    val dynamicBytes = ByteBuffer.wrap(readAt(dynamic.offset, dynamic.fileSize.toInt())).order(order)
    var textRelocations = false
    var sonameIndex: Long? = null
    var stringTableAddress: Long? = null
    var stringTableSize: Long? = null
    var terminated = false
    for (offset in 0 until dynamicBytes.capacity() step entrySize) {
        val tag = if (bits == 32) unsignedInt(dynamicBytes.getInt(offset)) else unsignedLong(dynamicBytes.getLong(offset))
        val value = if (bits == 32) unsignedInt(dynamicBytes.getInt(offset + 4)) else unsignedLong(dynamicBytes.getLong(offset + 8))
        when (tag) {
            DT_NULL -> { terminated = true; break }
            DT_TEXTREL -> textRelocations = true
            DT_FLAGS -> if (value and DF_TEXTREL != 0L) textRelocations = true
            DT_SONAME -> sonameIndex = value
            DT_STRTAB -> stringTableAddress = value
            DT_STRSZ -> stringTableSize = value
        }
    }
    require(terminated) { "unterminated ELF dynamic segment" }
    val soname = sonameIndex?.let { index ->
        val stringAddress = stringTableAddress ?: return@let null
        val stringsSize = stringTableSize ?: return@let null
        if (index >= stringsSize) return@let null
        val load = headers.firstOrNull { segment ->
            segment.type == PT_LOAD && stringAddress >= segment.virtualAddress &&
                stringAddress - segment.virtualAddress < segment.fileSize
        } ?: return@let null
        val stringDelta = stringAddress - load.virtualAddress
        if (stringsSize > load.fileSize - stringDelta) return@let null
        val stringTableOffset = load.offset + stringDelta
        if (stringTableOffset < load.offset) return@let null
        requireRange(stringTableOffset, stringsSize, size, "string table")
        val stringOffset = stringTableOffset + index
        val available = minOf(MAX_STRING_BYTES.toLong(), stringsSize - index, size - stringOffset).toInt()
        if (available <= 0) return@let null
        val nameBytes = readAt(stringOffset, available)
        val terminator = nameBytes.indexOf(0)
        if (terminator <= 0) null else nameBytes.copyOfRange(0, terminator).toString(Charsets.UTF_8)
    }
    return ElfNativeMetadata(bits, littleEndian, machine, soname, textRelocations)
}

internal fun inspectNativeLibrary(
    path: String,
    file: File,
    destination: String = path,
    collision: Boolean? = false,
): NativeLibraryInspection {
    val size = file.length()
    val sha256 = try {
        expansionSha256(file)
    } catch (error: Exception) {
        return NativeLibraryInspection(path, null, safeNativeError("SHA-256 failed: ${error.message}"), size, destination = destination, collision = collision)
    }
    return try {
        NativeLibraryInspection(path, inspectElfFile(file), sizeBytes = size, sha256 = sha256, destination = destination, collision = collision)
    } catch (error: Exception) {
        NativeLibraryInspection(path, null, safeNativeError(error.message), size, sha256, destination, collision)
    }
}

internal fun inspectNativeLibrary(
    zip: ZipFile,
    entry: ZipEntry,
    destination: String? = expansionNativeDestination(entry.name),
    collision: Boolean? = null,
): NativeLibraryInspection = try {
    withNativeEntryFile(zip, entry) { file ->
        NativeLibraryInspection(
            entry.name,
            inspectElfFile(file),
            sizeBytes = file.length(),
            sha256 = expansionSha256(file),
            destination = destination,
            collision = collision,
        )
    }
} catch (error: Exception) {
    NativeLibraryInspection(entry.name, null, safeNativeError(error.message), entry.size.takeIf { it >= 0 }, destination = destination, collision = collision)
}

private fun <T> withNativeEntryFile(zip: ZipFile, entry: ZipEntry, action: (File) -> T): T {
    require(entry.size >= 0) { "ELF ZIP entry has unknown length" }
    val temporary = Files.createTempFile("unipatch-native-scan-", ".so").toFile()
    try {
        var copied = 0L
        zip.getInputStream(entry).use { input ->
            temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(read.toLong() <= entry.size - copied) { "ELF ZIP entry exceeds its declared length" }
                    copied += read
                    output.write(buffer, 0, read)
                }
            }
        }
        require(copied == entry.size) { "ELF ZIP entry length does not match its declaration" }
        return action(temporary)
    } finally {
        if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit()
    }
}

internal fun nativeTargetSdkForInspection(
    sourceTargetSdk: Int?,
    requestedTargetSdk: Int?,
    spoofEnabled: Boolean,
    diagnosticsOnly: Boolean,
): Int? {
    if (!spoofEnabled || diagnosticsOnly) return sourceTargetSdk
    val selected = requestedTargetSdk ?: 24
    return selected.takeIf { it in MIN_SUPPORTED_TARGET_SDK..MAX_SUPPORTED_TARGET_SDK } ?: sourceTargetSdk
}

internal fun plannedNativeTargetSdk(spoofEnabled: Boolean, requestedTargetSdk: Int?): Int? {
    if (!spoofEnabled) return null
    val selected = requestedTargetSdk ?: 24
    return selected.takeIf { it in MIN_SUPPORTED_TARGET_SDK..MAX_SUPPORTED_TARGET_SDK }
}

internal fun nativeLibraryCompatibilityIssues(
    inspections: List<NativeLibraryInspection>,
    targetSdk: Int?,
): List<String> = inspections.mapNotNull { inspection ->
    val details = nativeLibraryDetails(inspection, targetSdk)
    val metadata = inspection.metadata
    when {
        metadata == null && (targetSdk == null || targetSdk >= 23) -> "$details ELF inspection failed: ${safeNativeText(inspection.error ?: "unknown format")}"
        metadata != null && metadata.hasTextRelocations && (targetSdk == null || targetSdk >= 23) ->
            "$details SONAME=${safeNativeText(metadata.soname ?: "unknown")} DT_TEXTREL is incompatible with targetSdk ${targetSdk ?: "unknown"} on Android 6.0/API 23 and newer."
        else -> null
    }
}

internal fun formatNativeLibraryInspection(inspection: NativeLibraryInspection, targetSdk: Int?): String {
    val metadata = inspection.metadata
    val details = nativeLibraryDetails(inspection, targetSdk)
    return if (metadata == null) {
        "Legacy compatibility native library: $details ELF=unknown JNI entrypoints=unknown reason=${safeNativeText(inspection.error ?: "unknown format")}."
    } else {
        "Legacy compatibility native library: $details bits=${metadata.bits} SONAME=${safeNativeText(metadata.soname ?: "unknown")} DT_TEXTREL=${metadata.hasTextRelocations} JNI entrypoints=unknown (dynamic symbols and registration are not inspected)."
    }
}

private fun nativeLibraryDetails(inspection: NativeLibraryInspection, targetSdk: Int?): String {
    val collision = when (inspection.collision) {
        true -> "yes"
        false -> "no"
        null -> "not-evaluated"
    }
    return "source=${safeNativeText(inspection.path)} destination=${safeNativeText(inspection.destination ?: "unknown")} abi=${safeNativeText(inspection.abi)} sizeBytes=${inspection.sizeBytes ?: "unknown"} sha256=${safeNativeText(inspection.sha256 ?: "unknown")} relocationDestinationCollision=$collision targetSdk=${targetSdk ?: "unknown"}"
}

private fun requireRange(offset: Long, length: Long, total: Long, label: String) {
    require(offset >= 0 && length >= 0 && offset <= total && length <= total - offset) { "ELF $label is outside file bounds" }
}

private fun unsignedInt(value: Int): Long = Integer.toUnsignedLong(value)
private fun unsignedShort(value: Short): Int = value.toInt() and 0xffff
private fun unsignedLong(value: Long): Long {
    require(value >= 0) { "ELF uses an unsupported unsigned 64-bit offset" }
    return value
}

private fun safeNativeError(message: String?): String = safeNativeText(message.orEmpty()).take(160).ifEmpty { "invalid ELF" }

private fun safeNativeText(value: String): String = value.map { if (Character.isISOControl(it)) ' ' else it }.joinToString("").take(300)
