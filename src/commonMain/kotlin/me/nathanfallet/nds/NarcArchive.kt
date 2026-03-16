package me.nathanfallet.nds

/**
 * Reads and writes Nintendo DS NARC container files.
 *
 * NARC binary layout (all fields little-endian):
 *
 *   [Main header – 16 bytes]
 *     0x00  magic      "NARC"
 *     0x04  BOM        FE FF
 *     0x06  version    00 01
 *     0x08  file size  (u32)
 *     0x0C  hdr size   10 00
 *     0x0E  sections   03 00
 *
 *   [BTAF – file allocation table block]
 *     0x00  magic      "BTAF"
 *     0x04  size       (u32, includes this 8-byte header)
 *     0x08  num files  (u32)
 *     0x0C  entries    numFiles × { start u32, end u32 }   (relative to GMIF data start)
 *
 *   [BTNF – file name table block]
 *     0x00  magic      "BTNF"
 *     0x04  size       (u32)
 *     0x08  FNT data   (minimal single-root entry for anonymous NARCs)
 *
 *   [GMIF – file image block]
 *     0x00  magic      "GMIF"
 *     0x04  size       (u32, includes this 8-byte header)
 *     0x08  file data  (concatenated)
 */
object NarcArchive {

    // -------------------------------------------------------------------------
    // Unpack
    // -------------------------------------------------------------------------

    /**
     * Extracts all files from a NARC container.
     * Returns the files in their original order (index = file ID).
     */
    fun unpack(data: ByteArray): List<ByteArray> {
        require(data.size >= 16) { "NARC too small" }
        require(data.decodeToString(0, 4) == "NARC") { "Not a NARC file" }

        // Main header
        val headerSize = u16(data, 0x0C)
        check(headerSize == 16) { "Unexpected NARC header size: $headerSize" }

        // Find sections
        var pos = headerSize
        var btafData: ByteArray? = null
        var gmifDataStart = -1

        repeat(3) {
            val magic = data.decodeToString(pos, pos + 4)
            val size = u32(data, pos + 4).toInt()
            when (magic) {
                "BTAF" -> btafData = data.copyOfRange(pos + 8, pos + size)
                "GMIF" -> gmifDataStart = pos + 8
            }
            pos += size
        }

        val fat = checkNotNull(btafData) { "BTAF section not found" }
        check(gmifDataStart >= 0) { "GMIF section not found" }

        val numFiles = u32(fat, 0).toInt()
        return List(numFiles) { i ->
            val start = u32(fat, 4 + i * 8).toInt()
            val end = u32(fat, 4 + i * 8 + 4).toInt()
            if (start >= end) ByteArray(0)
            else data.copyOfRange(gmifDataStart + start, gmifDataStart + end)
        }
    }

    // -------------------------------------------------------------------------
    // Pack
    // -------------------------------------------------------------------------

    /**
     * Packs a list of files into a NARC container.
     * Files are stored in the given order; they are accessed by index (no names).
     */
    fun pack(files: List<ByteArray>): ByteArray {
        val numFiles = files.size

        // Build GMIF data (files concatenated, each 4-byte aligned)
        val gmifData = buildGmifData(files)

        // Build FAT entries (offsets into GMIF data)
        val fatEntries = ByteArray(numFiles * 8)
        var offset = 0
        for ((i, file) in files.withIndex()) {
            writeU32(fatEntries, i * 8, offset.toLong())
            writeU32(fatEntries, i * 8 + 4, (offset + file.size).toLong())
            offset += align4(file.size)
        }

        // BTAF section
        val btafSize = 8 + 4 + fatEntries.size
        val btaf = ByteArray(btafSize)
        btaf[0] = 'B'.code.toByte(); btaf[1] = 'T'.code.toByte()
        btaf[2] = 'A'.code.toByte(); btaf[3] = 'F'.code.toByte()
        writeU32(btaf, 4, btafSize.toLong())
        writeU32(btaf, 8, numFiles.toLong())
        fatEntries.copyInto(btaf, 12)

        // BTNF section — minimal anonymous FNT
        val fnt = buildMinimalFnt()
        val btnfSize = 8 + fnt.size
        val btnf = ByteArray(btnfSize)
        btnf[0] = 'B'.code.toByte(); btnf[1] = 'T'.code.toByte()
        btnf[2] = 'N'.code.toByte(); btnf[3] = 'F'.code.toByte()
        writeU32(btnf, 4, btnfSize.toLong())
        fnt.copyInto(btnf, 8)

        // GMIF section
        val gmifSize = 8 + gmifData.size
        val gmif = ByteArray(gmifSize)
        gmif[0] = 'G'.code.toByte(); gmif[1] = 'M'.code.toByte()
        gmif[2] = 'I'.code.toByte(); gmif[3] = 'F'.code.toByte()
        writeU32(gmif, 4, gmifSize.toLong())
        gmifData.copyInto(gmif, 8)

        // Main header
        val totalSize = 16 + btafSize + btnfSize + gmifSize
        val header = ByteArray(16)
        header[0] = 'N'.code.toByte(); header[1] = 'A'.code.toByte()
        header[2] = 'R'.code.toByte(); header[3] = 'C'.code.toByte()
        header[4] = 0xFE.toByte(); header[5] = 0xFF.toByte()   // BOM
        header[6] = 0x00; header[7] = 0x01             // version
        writeU32(header, 8, totalSize.toLong())
        header[12] = 0x10; header[13] = 0x00            // header size = 16
        header[14] = 0x03; header[15] = 0x00            // 3 sections

        // Assemble
        val out = ByteArray(totalSize)
        var cur = 0
        for (part in listOf(header, btaf, btnf, gmif)) {
            part.copyInto(out, cur); cur += part.size
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Concatenates file data, padding each to a 4-byte boundary. */
    private fun buildGmifData(files: List<ByteArray>): ByteArray {
        val totalSize = files.sumOf { align4(it.size) }
        val buf = ByteArray(totalSize)
        var pos = 0
        for (file in files) {
            file.copyInto(buf, pos)
            pos += align4(file.size)
        }
        return buf
    }

    /**
     * Returns a minimal 12-byte FNT for an anonymous (index-only) NARC:
     *   - 8-byte root directory entry pointing to a single-byte subtable
     *   - 1-byte end-of-subtable marker (0x00)
     *   - 3 bytes padding to maintain 4-byte alignment
     */
    private fun buildMinimalFnt(): ByteArray {
        val fnt = ByteArray(12)
        // Root entry: subtable offset = 8 (after the 8-byte root entry), firstFileId = 0, totalDirs = 1
        writeU32(fnt, 0, 8L)     // subtable offset (from FNT start)
        fnt[4] = 0x00; fnt[5] = 0x00  // first file ID = 0
        fnt[6] = 0x01; fnt[7] = 0x00  // total directories = 1
        // subtable: just end-of-subtable marker
        fnt[8] = 0x00
        // fnt[9..11] = 0xFF padding
        fnt[9] = 0xFF.toByte(); fnt[10] = 0xFF.toByte(); fnt[11] = 0xFF.toByte()
        return fnt
    }

    private fun align4(n: Int) = (n + 3) and -4

    private fun u16(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(buf: ByteArray, off: Int): Long =
        (buf[off].toLong() and 0xFF) or
                ((buf[off + 1].toLong() and 0xFF) shl 8) or
                ((buf[off + 2].toLong() and 0xFF) shl 16) or
                ((buf[off + 3].toLong() and 0xFF) shl 24)

    private fun writeU32(buf: ByteArray, off: Int, v: Long) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = (v.ushr(8) and 0xFF).toByte()
        buf[off + 2] = (v.ushr(16) and 0xFF).toByte()
        buf[off + 3] = (v.ushr(24) and 0xFF).toByte()
    }
}
