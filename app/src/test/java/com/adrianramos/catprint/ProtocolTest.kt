package com.adrianramos.catprint

import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected bytes come from the Python implementation (catprinter/cmds.py, mxw01.py). */
class ProtocolTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val row = BooleanArray(PRINT_WIDTH) { (it * 7) % 13 == 0 }
    private val row2 = BooleanArray(PRINT_WIDTH) { it in 100 until 300 }

    @Test fun legacyCommands() {
        assertEquals("5178af000200ffff24ff", hex(Protocol.Legacy.setEnergy(0xffff)))
        assertEquals("5178bd000100194fff", hex(Protocol.Legacy.feedPaper(25)))
        assertEquals("5178be0001000107ff", hex(Protocol.Legacy.applyEnergy()))
    }

    @Test fun legacyRows() {
        assertEquals(
            "5178a200300001200004800010000240000800012000048000100002400008000120000480001000024000080001200004800010000200ff",
            hex(Protocol.Legacy.printRow(row)))
        assertEquals("5178bf00040064ffc954ddff", hex(Protocol.Legacy.printRow(row2)))
    }

    @Test fun legacyJob() {
        val job = Protocol.Legacy.job(listOf(row, row2), 0x8000)
        assertEquals(191, job.size)
        assertEquals(
            "4fff5178a10002003000f9ff5178a10002003000f9ff5178a10002003000f9ff5178a6000b00aa5517000000000000001711ff5178a30001000000ff",
            hex(job).takeLast(120))
    }

    @Test fun mxw01Command() {
        assertEquals("2221a900040064003002faff", hex(Protocol.Mxw01.cmd(0xa9, 100, 0, 0x30, 2)))
    }
}
