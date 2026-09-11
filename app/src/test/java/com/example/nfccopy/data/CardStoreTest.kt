package com.example.nfccopy.data

import com.example.nfccopy.model.BlockData
import com.example.nfccopy.model.CardDump
import com.example.nfccopy.model.SectorData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CardStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sampleDump(uidHexNoColons: String = "AABBCCDD"): CardDump {
        val uid = uidHexNoColons.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return CardDump(
            uid = uid,
            atqa = byteArrayOf(0x00, 0x04),
            sak = 0x08,
            techList = listOf("android.nfc.tech.MifareClassic"),
            typeName = "MIFARE Classic 1K",
            isMifareClassic = true,
            sizeBytes = 16,
            sectorCount = 1,
            blockCount = 1,
            sectors = listOf(
                SectorData(
                    index = 0,
                    keyA = ByteArray(6) { 0xFF.toByte() },
                    keyB = null,
                    blocks = listOf(BlockData(0, ByteArray(16) { 0 })),
                )
            ),
            timestamp = 1_700_000_000_000L,
        )
    }

    @Test
    fun saveThenLoad_roundTripsList() {
        val file = tmp.newFile("saved_cards.json")
        val store = CardStore(file)
        val dump = sampleDump()
        store.save(listOf(dump))
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals(dump.uidHex, loaded[0].uidHex)
        assertEquals(dump.blockCount, loaded[0].blockCount)
        assertEquals(dump.timestamp, loaded[0].timestamp)
        assertEquals(dump.readableBlocks, loaded[0].readableBlocks)
    }

    @Test
    fun load_missingFile_returnsEmpty() {
        val file = File(tmp.root, "missing.json")
        assertTrue(!file.exists())
        assertTrue(CardStore(file).load().isEmpty())
    }

    @Test
    fun load_corruptFile_returnsEmpty() {
        val file = tmp.newFile("saved_cards.json")
        file.writeText("NOT-JSON{{{")
        assertTrue(CardStore(file).load().isEmpty())
    }
}
