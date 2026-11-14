package com.moldyhub.clonehub

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.security.MessageDigest

data class CveApkInspectionReport(
    val sha256: String,
    val bytesRead: Long,
    val entryCount: Int,
    val hasManifest: Boolean,
    val dexFileCount: Int,
    val nativeAbis: List<String>
)

/**
 * Inspects APK ZIP metadata through a seekable descriptor.
 *
 * It reads the ZIP central directory instead of inflating every entry. APK payloads
 * can contain hundreds of megabytes of assets; decompressing those merely to list
 * filenames is unnecessary. The complete file is still streamed once for SHA-256.
 */
object CveApkArchiveInspector {
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50
    private const val EOCD_MIN_SIZE = 22
    private const val MAX_COMMENT_LENGTH = 0xffff
    private val UTF8: Charset = Charsets.UTF_8
    private val CP437: Charset = Charset.forName("Cp437")

    fun inspect(channel: FileChannel): CveApkInspectionReport {
        val fileSize = channel.size()
        require(fileSize >= EOCD_MIN_SIZE) { "APK is too small to be a ZIP archive." }

        val tailSize = minOf(fileSize, (EOCD_MIN_SIZE + MAX_COMMENT_LENGTH).toLong()).toInt()
        val tailStart = fileSize - tailSize
        val tail = readBuffer(channel, tailStart, tailSize)
        val eocdIndex = findEocd(tail)
        require(eocdIndex >= 0) { "ZIP end-of-central-directory record was not found." }

        val eocd = tail.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        eocd.position(eocdIndex)
        val diskNumber = eocd.getShort(4).toInt() and 0xffff
        val centralDisk = eocd.getShort(6).toInt() and 0xffff
        val entriesOnDisk = eocd.getShort(8).toInt() and 0xffff
        val totalEntries = eocd.getShort(10).toInt() and 0xffff
        val centralSize = eocd.getInt(12).toLong() and 0xffffffffL
        val centralOffset = eocd.getInt(16).toLong() and 0xffffffffL

        require(diskNumber == 0 && centralDisk == 0 && entriesOnDisk == totalEntries) {
            "Multi-disk ZIP archives are not supported."
        }
        require(totalEntries != 0xffff && centralSize != 0xffffffffL && centralOffset != 0xffffffffL) {
            "ZIP64 APK archives are not supported by this scanner."
        }
        require(centralOffset + centralSize <= fileSize) {
            "ZIP central directory points outside the APK."
        }

        var offset = centralOffset
        val centralEnd = centralOffset + centralSize
        var entryCount = 0
        var hasManifest = false
        var dexFileCount = 0
        val abis = linkedSetOf<String>()

        while (offset < centralEnd) {
            require(centralEnd - offset >= 46L) { "Truncated ZIP central directory." }
            val header = readBuffer(channel, offset, 46).order(ByteOrder.LITTLE_ENDIAN)
            require(header.int == CENTRAL_DIRECTORY_SIGNATURE) {
                "Invalid ZIP central directory entry signature."
            }
            header.position(28)
            val nameLength = header.short.toInt() and 0xffff
            val extraLength = header.short.toInt() and 0xffff
            val commentLength = header.short.toInt() and 0xffff
            val recordLength = 46L + nameLength + extraLength + commentLength
            require(offset + recordLength <= centralEnd) { "Truncated ZIP entry metadata." }

            val nameBuffer = readBuffer(channel, offset + 46L, nameLength)
            val nameBytes = ByteArray(nameLength)
            nameBuffer.get(nameBytes)
            val flagsBuffer = readBuffer(channel, offset + 8L, 2).order(ByteOrder.LITTLE_ENDIAN)
            val flags = flagsBuffer.short.toInt() and 0xffff
            val name = String(nameBytes, if ((flags and (1 shl 11)) != 0) UTF8 else CP437)

            entryCount++
            when {
                name == "AndroidManifest.xml" -> hasManifest = true
                name.matches(Regex("classes(\\d*)\\.dex")) -> dexFileCount++
                name.startsWith("lib/") -> {
                    val parts = name.split('/')
                    if (parts.size >= 3 && parts[1].isNotBlank()) abis.add(parts[1])
                }
            }
            offset += recordLength
        }
        require(offset == centralEnd) { "ZIP central directory size did not match its entries." }

        val digest = MessageDigest.getInstance("SHA-256")
        val hashBuffer = ByteBuffer.allocate(256 * 1024)
        var position = 0L
        while (position < fileSize) {
            hashBuffer.clear()
            hashBuffer.limit(minOf(hashBuffer.capacity().toLong(), fileSize - position).toInt())
            val count = channel.read(hashBuffer, position)
            require(count > 0) { "Could not read the complete APK for SHA-256." }
            digest.update(hashBuffer.array(), 0, count)
            position += count
        }

        return CveApkInspectionReport(
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            bytesRead = fileSize,
            entryCount = entryCount,
            hasManifest = hasManifest,
            dexFileCount = dexFileCount,
            nativeAbis = abis.sorted()
        )
    }

    private fun findEocd(buffer: ByteBuffer): Int {
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (index in view.limit() - EOCD_MIN_SIZE downTo 0) {
            if (view.getInt(index) == EOCD_SIGNATURE) {
                val commentLength = view.getShort(index + 20).toInt() and 0xffff
                if (index + EOCD_MIN_SIZE + commentLength == view.limit()) return index
            }
        }
        return -1
    }

    private fun readBuffer(channel: FileChannel, position: Long, size: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(size)
        var offset = position
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, offset)
            require(count > 0) { "Unexpected end of APK while reading ZIP metadata." }
            offset += count
        }
        buffer.flip()
        return buffer
    }
}
