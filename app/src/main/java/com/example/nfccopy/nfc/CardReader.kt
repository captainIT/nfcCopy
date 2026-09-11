package com.example.nfccopy.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import com.example.nfccopy.model.BlockData
import com.example.nfccopy.model.CardDump
import com.example.nfccopy.model.SectorData
import java.io.IOException

/**
 * Reads identification data from any discovered tag, and — for MIFARE Classic —
 * attempts to dump every sector using the [MifareKeys] default-key dictionary.
 */
object CardReader {

    /** Human-readable short name of the tech list, e.g. "NfcA, MifareClassic". */
    private fun Tag.shortTechList(): List<String> =
        techList.map { it.substringAfterLast('.') }

    fun read(tag: Tag): CardDump {
        val techNames = tag.shortTechList()

        val nfcA = NfcA.get(tag)
        val atqa = nfcA?.atqa
        val sak = nfcA?.sak?.toInt()?.and(0xFF)

        val mc = MifareClassic.get(tag)
        return when {
            mc != null -> readMifareClassic(tag, mc, techNames, atqa, sak)
            nfcA != null && looksLikeClassic(sak) ->
                readClassicViaNfcA(tag, nfcA, techNames, atqa, sak)
            else -> CardDump(
                uid = tag.id,
                atqa = atqa,
                sak = sak,
                techList = techNames,
                typeName = classifyNonClassic(techNames, sak),
                isMifareClassic = false,
                sizeBytes = 0,
                sectorCount = 0,
                blockCount = 0,
                sectors = emptyList(),
            )
        }
    }

    private fun readMifareClassic(
        tag: Tag,
        mc: MifareClassic,
        techNames: List<String>,
        atqa: ByteArray?,
        sak: Int?,
    ): CardDump {
        val sectors = mutableListOf<SectorData>()
        try {
            mc.connect()
            mc.timeout = 1000
            for (sectorIndex in 0 until mc.sectorCount) {
                sectors.add(readSector(mc, sectorIndex))
            }
            return CardDump(
                uid = tag.id,
                atqa = atqa,
                sak = sak,
                techList = techNames,
                typeName = classifyMifareClassic(mc.type, mc.size),
                isMifareClassic = true,
                sizeBytes = mc.size,
                sectorCount = mc.sectorCount,
                blockCount = mc.blockCount,
                sectors = sectors,
            )
        } finally {
            try {
                mc.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun readSector(m: MifareClassic, sectorIndex: Int): SectorData {
        var usedKeyA: ByteArray? = null
        var usedKeyB: ByteArray? = null
        var authKind = 0 // 1 = key A, 2 = key B

        for (key in MifareKeys.DEFAULT_KEYS) {
            try {
                if (m.authenticateSectorWithKeyA(sectorIndex, key)) {
                    usedKeyA = key
                    authKind = 1
                    break
                }
            } catch (_: IOException) {
                // reconnect and keep trying with the next key
                reconnect(m)
            }
        }
        if (authKind == 0) {
            for (key in MifareKeys.DEFAULT_KEYS) {
                try {
                    if (m.authenticateSectorWithKeyB(sectorIndex, key)) {
                        usedKeyB = key
                        authKind = 2
                        break
                    }
                } catch (_: IOException) {
                    reconnect(m)
                }
            }
        }

        val firstBlock = m.sectorToBlock(sectorIndex)
        val blockCount = m.getBlockCountInSector(sectorIndex)
        val blocks = ArrayList<BlockData>(blockCount)
        for (i in 0 until blockCount) {
            val absBlock = firstBlock + i
            val data: ByteArray? = if (authKind != 0) {
                try {
                    m.readBlock(absBlock)
                } catch (_: IOException) {
                    reconnect(m)
                    null
                }
            } else null
            blocks.add(BlockData(absBlock, data))
        }
        return SectorData(sectorIndex, usedKeyA, usedKeyB, blocks)
    }

    private fun reconnect(m: MifareClassic) {
        try {
            m.close()
        } catch (_: IOException) {
        }
        try {
            m.connect()
        } catch (_: IOException) {
        }
    }

    private fun classifyMifareClassic(type: Int, size: Int): String {
        val family = when (type) {
            MifareClassic.TYPE_CLASSIC -> "MIFARE Classic"
            MifareClassic.TYPE_PLUS -> "MIFARE Plus"
            MifareClassic.TYPE_PRO -> "MIFARE Pro"
            else -> "MIFARE Classic (unknown)"
        }
        val capacity = when (size) {
            MifareClassic.SIZE_MINI -> "Mini 320B"
            MifareClassic.SIZE_1K -> "1K"
            MifareClassic.SIZE_2K -> "2K"
            MifareClassic.SIZE_4K -> "4K"
            else -> "$size B"
        }
        return "$family $capacity"
    }

    /**
     * Pixel ST HAL sometimes omits [MifareClassic] even when SAK says M1.
     * Probe an unauthenticated READ of block 0 so the UI at least shows UID/ATQA.
     */
    private fun readClassicViaNfcA(
        tag: Tag,
        nfcA: NfcA,
        techNames: List<String>,
        atqa: ByteArray?,
        sak: Int?,
    ): CardDump {
        var block0: ByteArray? = null
        try {
            nfcA.connect()
            nfcA.timeout = 2000
            val rsp = nfcA.transceive(byteArrayOf(0x30, 0x00))
            if (rsp != null && rsp.size >= 16) block0 = rsp.copyOf(16)
        } catch (_: IOException) {
        } catch (_: Exception) {
        } finally {
            try {
                nfcA.close()
            } catch (_: IOException) {
            }
        }
        val blocks = listOf(
            BlockData(0, block0),
            BlockData(1, null),
            BlockData(2, null),
            BlockData(3, null),
        )
        return CardDump(
            uid = tag.id,
            atqa = atqa,
            sak = sak,
            techList = techNames,
            typeName = "疑似 MIFARE Classic（本机未暴露 MifareClassic，仅 UID）",
            isMifareClassic = false,
            sizeBytes = 0,
            sectorCount = 0,
            blockCount = 0,
            sectors = if (block0 != null) listOf(SectorData(0, null, null, blocks)) else emptyList(),
        )
    }

    private fun looksLikeClassic(sak: Int?): Boolean =
        sak != null && sak and 0x08 != 0

    private fun classifyNonClassic(techNames: List<String>, sak: Int?): String = when {
        "MifareUltralight" in techNames -> "MIFARE Ultralight / NTAG"
        "IsoDep" in techNames -> "ISO 14443-4 (CPU / bank / ID card)"
        "NfcF" in techNames -> "FeliCa (NfcF)"
        "NfcV" in techNames -> "ISO 15693 (NfcV)"
        "NfcB" in techNames -> "ISO 14443-B"
        "Ndef" in techNames -> "NDEF tag"
        else -> "Unknown 13.56MHz tag (SAK=${sak ?: "?"})"
    }
}
