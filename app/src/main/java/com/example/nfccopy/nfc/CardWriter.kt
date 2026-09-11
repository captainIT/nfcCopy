package com.example.nfccopy.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import com.example.nfccopy.model.CardDump
import com.example.nfccopy.util.toHex
import java.io.IOException

/**
 * Writes a previously read [CardDump] onto a *magic* MIFARE Classic card
 * (a.k.a. CUID / FUID / UID-changeable / Gen2 or Gen1a card).
 *
 * A normal blank MIFARE Classic card has a **read-only manufacturer block 0** and its
 * UID is fixed at the factory — you can never clone a UID onto it. Only magic cards let
 * you rewrite block 0. This is why the target MUST be a magic card.
 */
object CardWriter {

    data class BlockResult(val block: Int, val ok: Boolean, val message: String)

    data class WriteResult(
        val success: Boolean,
        val blocks: List<BlockResult>,
        val summary: String,
    )

    /**
     * @param writeTrailers if true, copies each sector trailer (keys + access bits) from
     *   the source. Leave false to keep the target's default keys and only clone the data
     *   payload — safer, avoids bricking a sector with bad access bits.
     */
    fun writeDump(tag: Tag, dump: CardDump, writeTrailers: Boolean = false): WriteResult {
        if (!dump.isMifareClassic) {
            return WriteResult(false, emptyList(), "源卡不是 MIFARE Classic，无法写入。")
        }
        val mc = MifareClassic.get(tag)
            ?: return WriteResult(false, emptyList(), "目标卡不是 MIFARE Classic（可能不是魔术卡）。")

        val results = mutableListOf<BlockResult>()
        try {
            mc.connect()
            mc.timeout = 1500

            for (sector in dump.sectors) {
                val firstBlock = mc.sectorToBlock(sector.index)
                val trailerBlock = firstBlock + mc.getBlockCountInSector(sector.index) - 1

                if (!authenticateTarget(mc, sector.index)) {
                    for (b in sector.blocks) {
                        results.add(BlockResult(b.index, false, "扇区 ${sector.index} 认证失败，跳过"))
                    }
                    continue
                }

                for (b in sector.blocks) {
                    val isTrailer = b.index == trailerBlock
                    val isBlock0 = b.index == 0
                    val data = b.data

                    if (data == null) {
                        results.add(BlockResult(b.index, false, "源数据缺失（未读出），跳过"))
                        continue
                    }
                    if (isTrailer && !writeTrailers) {
                        results.add(BlockResult(b.index, true, "尾块(密钥)保留目标默认值，跳过"))
                        continue
                    }
                    if (isBlock0) {
                        results.add(writeBlockZero(tag, mc, sector.index, data))
                        continue
                    }
                    results.add(writeData(mc, b.index, data))
                }
            }
        } catch (e: IOException) {
            results.add(BlockResult(-1, false, "IO 异常: ${e.message}"))
        } finally {
            try {
                mc.close()
            } catch (_: IOException) {
            }
        }

        val okCount = results.count { it.ok }
        val fail = results.count { !it.ok }
        val success = fail == 0 && okCount > 0
        val summary = if (success) {
            "写入完成：$okCount 块成功。请重新读卡验证 UID 与数据。"
        } else {
            "写入结束：$okCount 块成功，$fail 块失败。目标可能不是魔术卡或密钥不匹配。"
        }
        return WriteResult(success, results, summary)
    }

    private fun authenticateTarget(mc: MifareClassic, sectorIndex: Int): Boolean {
        for (key in MifareKeys.DEFAULT_KEYS) {
            try {
                if (mc.authenticateSectorWithKeyA(sectorIndex, key)) return true
            } catch (_: IOException) {
                reconnect(mc)
            }
        }
        for (key in MifareKeys.DEFAULT_KEYS) {
            try {
                if (mc.authenticateSectorWithKeyB(sectorIndex, key)) return true
            } catch (_: IOException) {
                reconnect(mc)
            }
        }
        return false
    }

    private fun writeData(mc: MifareClassic, block: Int, data: ByteArray): BlockResult = try {
        mc.writeBlock(block, data.ensure16())
        BlockResult(block, true, "OK ${data.toHex()}")
    } catch (e: IOException) {
        BlockResult(block, false, "写入失败: ${e.message}")
    }

    /**
     * Block 0 holds the UID. On a Gen2/CUID card it is writable after normal auth, so we
     * try that first. If it fails we fall back to the Gen1a backdoor sequence over NfcA.
     */
    private fun writeBlockZero(tag: Tag, mc: MifareClassic, sectorIndex: Int, data: ByteArray): BlockResult {
        try {
            mc.writeBlock(0, data.ensure16())
            return BlockResult(0, true, "Block0(UID) 写入成功 [Gen2]")
        } catch (_: IOException) {
            // fall through to Gen1a
        }
        return try {
            gen1aWriteBlockZero(tag, data.ensure16())
            BlockResult(0, true, "Block0(UID) 写入成功 [Gen1a 后门]")
        } catch (e: IOException) {
            BlockResult(0, false, "Block0(UID) 写入失败：目标可能不是可改 UID 的魔术卡 (${e.message})")
        }
    }

    /**
     * Gen1a "magic backdoor": HALT, then the unofficial 0x40 / 0x43 wake commands, then
     * write block 0 with 0xA0. Best-effort — not all NFC controllers can emit these frames.
     */
    private fun gen1aWriteBlockZero(tag: Tag, block0: ByteArray) {
        val nfcA = NfcA.get(tag) ?: throw IOException("目标不支持 NfcA")
        nfcA.use { a ->
            a.connect()
            a.timeout = 1500
            runCatching { a.transceive(byteArrayOf(0x50, 0x00)) } // HALT
            a.transceive(byteArrayOf(0x40))                       // magic wakeup 1
            a.transceive(byteArrayOf(0x43))                       // magic wakeup 2
            a.transceive(byteArrayOf(0xA0.toByte(), 0x00))        // write cmd, block 0
            a.transceive(block0)                                  // 16 bytes payload
        }
    }

    private fun reconnect(mc: MifareClassic) {
        try {
            mc.close()
        } catch (_: IOException) {
        }
        try {
            mc.connect()
        } catch (_: IOException) {
        }
    }

    private fun ByteArray.ensure16(): ByteArray = when {
        size == 16 -> this
        size > 16 -> copyOf(16)
        else -> copyOf(16)
    }
}
