package com.adrianramos.catprint

import java.io.ByteArrayOutputStream
import java.util.UUID

/** Printer dots per row. */
const val PRINT_WIDTH = 384

/** Byte-level protocols, ported from catprinter/cmds.py and catprinter/mxw01.py. */
object Protocol {
    val SERVICE_UUIDS = listOf(uuid16(0xae30), uuid16(0xaf30))
    val TX_UUID: UUID = uuid16(0xae01)   // commands
    val RX_UUID: UUID = uuid16(0xae02)   // notifications
    val DATA_UUID: UUID = uuid16(0xae03) // MXW01 image data
    val CCCD_UUID: UUID = uuid16(0x2902)

    val MXW01_NAMES = setOf("MXW01")
    val LEGACY_NAMES = setOf("GB01", "GB02", "GB03", "GT01")

    fun uuid16(v: Int): UUID = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))

    private val CHECKSUM_TABLE = intArrayOf(
        0, 7, 14, 9, 28, 27, 18, 21, 56, 63, 54, 49, 36, 35, 42, 45, 112, 119, 126, 121,
        108, 107, 98, 101, 72, 79, 70, 65, 84, 83, 90, 93, 224, 231, 238, 233, 252, 251,
        242, 245, 216, 223, 214, 209, 196, 195, 202, 205, 144, 151, 158, 153, 140,
        139, 130, 133, 168, 175, 166, 161, 180, 179, 186, 189, 199, 192, 201, 206,
        219, 220, 213, 210, 255, 248, 241, 246, 227, 228, 237, 234, 183, 176, 185, 190,
        171, 172, 165, 162, 143, 136, 129, 134, 147, 148, 157, 154, 39, 32, 41,
        46, 59, 60, 53, 50, 31, 24, 17, 22, 3, 4, 13, 10, 87, 80, 89, 94, 75, 76, 69, 66,
        111, 104, 97, 102, 115, 116, 125, 122, 137, 142, 135, 128, 149, 146, 155,
        156, 177, 182, 191, 184, 173, 170, 163, 164, 249, 254, 247, 240, 229, 226, 235, 236,
        193, 198, 207, 200, 221, 218, 211, 212, 105, 110, 103, 96, 117, 114, 123, 124, 81,
        86, 95, 88, 77, 74, 67, 68, 25, 30, 23, 16, 5, 2, 11, 12, 33, 38, 47, 40, 61, 58,
        51, 52, 78, 73, 64, 71, 82, 85, 92, 91, 118, 113, 120, 127, 106, 109, 100, 99, 62,
        57, 48, 55, 34, 37, 44, 43, 6, 1, 8, 15, 26, 29, 20, 19, 174, 169, 160, 167, 178,
        181, 188, 187, 150, 145, 152, 159, 138, 141, 132, 131, 222, 217, 208, 215,
        194, 197, 204, 203, 230, 225, 232, 239, 250, 253, 244, 243,
    )

    fun crc8(data: ByteArray, from: Int = 0, len: Int = data.size - from): Int {
        var crc = 0
        for (i in from until from + len) crc = CHECKSUM_TABLE[(crc xor data[i].toInt()) and 0xff]
        return crc
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Packs a row of pixels (true = black) into 1 bpp, least significant bit = leftmost pixel. */
    fun packRow(row: BooleanArray): ByteArray {
        val out = ByteArray(row.size / 8)
        for (i in row.indices) if (row[i]) out[i / 8] = (out[i / 8].toInt() or (1 shl (i % 8))).toByte()
        return out
    }

    // ---- GB01 / GB02 / GB03 / GT01 ------------------------------------------------------------

    object Legacy {
        val GET_DEV_STATE = bytes(0x51, 0x78, 0xa3, 0, 1, 0, 0, 0, 0xff)
        val SET_QUALITY_200_DPI = bytes(0x51, 0x78, 0xa4, 0, 1, 0, 0x32, 0x9e, 0xff)
        val LATTICE_START = bytes(0x51, 0x78, 0xa6, 0, 11, 0, 0xaa, 0x55, 0x17,
            0x38, 0x44, 0x5f, 0x5f, 0x5f, 0x44, 0x38, 0x2c, 0xa1, 0xff)
        val LATTICE_END = bytes(0x51, 0x78, 0xa6, 0, 11, 0, 0xaa, 0x55,
            0x17, 0, 0, 0, 0, 0, 0, 0, 0x17, 0x11, 0xff)
        val SET_PAPER = bytes(0x51, 0x78, 0xa1, 0, 2, 0, 0x30, 0, 0xf9, 0xff)
        val PRINTER_READY = bytes(0x51, 0x78, 0xae, 0x01, 0x01, 0x00, 0x00, 0x00, 0xff)

        /** 0x51 0x78 <cmd> 0x00 <len> 0x00 <payload> <crc8(payload)> 0xff */
        private fun cmd(id: Int, payload: ByteArray): ByteArray {
            val out = ByteArray(payload.size + 8)
            out[0] = 0x51; out[1] = 0x78; out[2] = id.toByte(); out[4] = payload.size.toByte()
            payload.copyInto(out, 6)
            out[out.size - 2] = crc8(payload).toByte()
            out[out.size - 1] = 0xff.toByte()
            return out
        }

        fun feedPaper(n: Int) = cmd(0xbd, bytes(n and 0xff))
        fun setEnergy(v: Int) = cmd(0xaf, bytes((v shr 8) and 0xff, v and 0xff))
        fun applyEnergy() = cmd(0xbe, bytes(1))

        private fun runLength(row: BooleanArray): ByteArray {
            val out = ByteArrayOutputStream()
            fun emit(count: Int, black: Boolean) {
                var n = count
                val bit = if (black) 0x80 else 0
                while (n > 0x7f) { out.write(bit or 0x7f); n -= 0x7f }
                if (n > 0) out.write(bit or n)
            }
            var count = 0
            var last = false
            for ((i, v) in row.withIndex()) {
                if (i > 0 && v == last) count++ else { if (count > 0) emit(count, last); count = 1 }
                last = v
            }
            if (count > 0) emit(count, last)
            return out.toByteArray()
        }

        /** Uses run-length compression when it is shorter, plain 1 bpp otherwise. */
        fun printRow(row: BooleanArray): ByteArray {
            val rle = runLength(row)
            return if (rle.size > PRINT_WIDTH / 8) cmd(0xa2, packRow(row)) else cmd(0xbf, rle)
        }

        /** The whole job as one byte stream; it is sent in MTU-sized chunks. */
        fun job(rows: List<BooleanArray>, energy: Int): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(GET_DEV_STATE)
            out.write(SET_QUALITY_200_DPI)
            out.write(setEnergy(energy))
            out.write(applyEnergy())
            out.write(LATTICE_START)
            for (r in rows) out.write(printRow(r))
            out.write(feedPaper(25))
            repeat(3) { out.write(SET_PAPER) }
            out.write(LATTICE_END)
            out.write(GET_DEV_STATE)
            return out.toByteArray()
        }
    }

    // ---- MXW01 --------------------------------------------------------------------------------

    object Mxw01 {
        const val CMD_GET_STATUS = 0xa1
        const val CMD_PRINT_INTENSITY = 0xa2
        const val CMD_PRINT = 0xa9
        const val CMD_PRINT_COMPLETE = 0xaa
        const val CMD_PRINT_DATA_FLUSH = 0xad

        const val MODE_MONOCHROME = 0x00
        const val MODE_GRAYSCALE = 0x02 // 4 bits per dot

        /** The printer seems to ignore jobs that are too short. */
        const val MIN_ROWS = 90
        const val MAX_ROWS = 0xffff

        val STATUS_ERRORS = mapOf(0x01 to "sin papel", 0x09 to "sin papel",
            0x04 to "sobrecalentada", 0x08 to "batería baja")

        /** 0x22 0x21 <cmd> 0x00 <len lo> <len hi> <payload> <crc8(payload)> 0xff */
        fun cmd(id: Int, vararg payload: Int): ByteArray {
            val p = bytes(*payload)
            val out = ByteArray(p.size + 8)
            out[0] = 0x22; out[1] = 0x21; out[2] = id.toByte()
            out[4] = (p.size and 0xff).toByte(); out[5] = (p.size shr 8).toByte()
            p.copyInto(out, 6)
            out[out.size - 2] = crc8(p).toByte()
            out[out.size - 1] = 0xff.toByte()
            return out
        }

        fun monoRows(rows: List<BooleanArray>): List<ByteArray> =
            pad(rows.map { packRow(it) }, PRINT_WIDTH / 8)

        /** Burn levels 0 (white) .. 15 (black); even pixels in the high nibble. */
        fun grayRows(levels: List<ByteArray>): List<ByteArray> = pad(levels.map { row ->
            ByteArray(row.size / 2) { i -> ((row[2 * i].toInt() shl 4) or row[2 * i + 1].toInt()).toByte() }
        }, PRINT_WIDTH / 2)

        private fun pad(rows: List<ByteArray>, rowBytes: Int): List<ByteArray> =
            rows + List(maxOf(0, MIN_ROWS - rows.size)) { ByteArray(rowBytes) }
    }
}
