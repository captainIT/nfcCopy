package com.example.nfccopy.model

import com.example.nfccopy.util.hexToBytesOrNull
import com.example.nfccopy.util.toHex
import org.json.JSONArray
import org.json.JSONObject

/** One 16-byte block. [data] is null when the block could not be read (auth failed). */
data class BlockData(
    val index: Int,
    val data: ByteArray?,
) {
    val isRead: Boolean get() = data != null
}

/** One MIFARE Classic sector, with whichever default keys were found to work. */
data class SectorData(
    val index: Int,
    val keyA: ByteArray?,
    val keyB: ByteArray?,
    val blocks: List<BlockData>,
) {
    val isFullyRead: Boolean get() = blocks.all { it.isRead }
}

/**
 * A complete snapshot of a scanned card. For non-MIFARE-Classic cards only the
 * identification fields are populated ([sectors] is empty).
 */
data class CardDump(
    val uid: ByteArray,
    val atqa: ByteArray?,
    val sak: Int?,
    val techList: List<String>,
    val typeName: String,
    val isMifareClassic: Boolean,
    val sizeBytes: Int,
    val sectorCount: Int,
    val blockCount: Int,
    val sectors: List<SectorData>,
    val timestamp: Long = System.currentTimeMillis(),
) {
    val uidHex: String get() = uid.toHex(":")

    /** Stable id for list keys / selection (UID + capture time). */
    val id: String get() = "$uidHex@$timestamp"

    val readableBlocks: Int get() = sectors.sumOf { s -> s.blocks.count { it.isRead } }

    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("uid", uid.toHex())
        root.put("atqa", atqa?.toHex())
        root.put("sak", sak ?: JSONObject.NULL)
        root.put("typeName", typeName)
        root.put("isMifareClassic", isMifareClassic)
        root.put("sizeBytes", sizeBytes)
        root.put("sectorCount", sectorCount)
        root.put("blockCount", blockCount)
        root.put("timestamp", timestamp)
        root.put("techList", JSONArray(techList))

        val sectorsJson = JSONArray()
        for (sector in sectors) {
            val s = JSONObject()
            s.put("index", sector.index)
            s.put("keyA", sector.keyA?.toHex())
            s.put("keyB", sector.keyB?.toHex())
            val blocksJson = JSONArray()
            for (block in sector.blocks) {
                val b = JSONObject()
                b.put("index", block.index)
                b.put("data", block.data?.toHex() ?: JSONObject.NULL)
                blocksJson.put(b)
            }
            s.put("blocks", blocksJson)
            sectorsJson.put(s)
        }
        root.put("sectors", sectorsJson)
        return root
    }

    companion object {
        fun fromJson(root: JSONObject): CardDump {
            val techList = mutableListOf<String>()
            root.optJSONArray("techList")?.let { arr ->
                for (i in 0 until arr.length()) techList.add(arr.getString(i))
            }
            val sectors = mutableListOf<SectorData>()
            root.optJSONArray("sectors")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    val blocks = mutableListOf<BlockData>()
                    val ba = s.getJSONArray("blocks")
                    for (j in 0 until ba.length()) {
                        val b = ba.getJSONObject(j)
                        val hex = if (b.isNull("data")) null else b.getString("data")
                        blocks.add(BlockData(b.getInt("index"), hex?.hexToBytesOrNull()))
                    }
                    sectors.add(
                        SectorData(
                            index = s.getInt("index"),
                            keyA = if (s.isNull("keyA")) null else s.getString("keyA").hexToBytesOrNull(),
                            keyB = if (s.isNull("keyB")) null else s.getString("keyB").hexToBytesOrNull(),
                            blocks = blocks,
                        )
                    )
                }
            }
            return CardDump(
                uid = root.getString("uid").hexToBytesOrNull() ?: ByteArray(0),
                atqa = if (root.isNull("atqa")) null else root.getString("atqa").hexToBytesOrNull(),
                sak = if (root.isNull("sak")) null else root.getInt("sak"),
                techList = techList,
                typeName = root.optString("typeName", "Unknown"),
                isMifareClassic = root.optBoolean("isMifareClassic", false),
                sizeBytes = root.optInt("sizeBytes", 0),
                sectorCount = root.optInt("sectorCount", 0),
                blockCount = root.optInt("blockCount", 0),
                sectors = sectors,
                timestamp = root.optLong("timestamp", System.currentTimeMillis()),
            )
        }

        fun fromDumpText(text: String): CardDump {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) error("空文件")
            return if (trimmed.startsWith("{")) fromJson(JSONObject(trimmed)) else fromMct(trimmed)
        }

        /**
         * Mifare Classic Tool dump:
         * ```
         * +Sector: 0
         * 001122...  (16 bytes hex, spaces optional; '-' means unread)
         * ```
         */
        fun fromMct(text: String): CardDump {
            val sectors = mutableListOf<SectorData>()
            var sectorIndex = -1
            val blockBytes = mutableListOf<ByteArray?>()

            fun flushSector() {
                if (sectorIndex < 0 || blockBytes.isEmpty()) return
                val first = sectorToBlock(sectorIndex)
                val blocks = blockBytes.mapIndexed { i, data -> BlockData(first + i, data) }
                val trailer = blockBytes.lastOrNull()
                val keyA = trailer?.takeIf { it.size >= 6 }?.copyOfRange(0, 6)
                val keyB = trailer?.takeIf { it.size >= 16 }?.copyOfRange(10, 16)
                sectors.add(SectorData(sectorIndex, keyA, keyB, blocks))
                blockBytes.clear()
            }

            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("*")) continue
                if (line.startsWith("+Sector:", ignoreCase = true)) {
                    flushSector()
                    sectorIndex = line.substringAfter(":").trim().toInt()
                    continue
                }
                if (sectorIndex < 0) continue
                val hex = line.filter { it != ' ' && it != ':' && it != '\t' }
                val data = when {
                    hex.isEmpty() -> null
                    hex.any { it == '-' || it == '?' } -> null
                    else -> hex.hexToBytesOrNull()
                }
                blockBytes.add(data)
            }
            flushSector()
            if (sectors.isEmpty()) error("无法解析 MCT dump（缺少 +Sector 行）")

            val b0 = sectors.firstOrNull { it.index == 0 }?.blocks?.firstOrNull()?.data
            val uid = when {
                b0 == null || b0.size < 4 -> ByteArray(0)
                b0[0] == 0x88.toByte() && b0.size >= 7 -> b0.copyOfRange(0, 7)
                else -> b0.copyOfRange(0, 4)
            }
            val sak = b0?.getOrNull(5)?.toInt()?.and(0xFF)
            val atqa = b0?.takeIf { it.size >= 8 }?.copyOfRange(6, 8)
            val blockCount = sectors.sumOf { it.blocks.size }
            val sizeBytes = blockCount * 16
            val typeName = when {
                sectors.size <= 5 -> "MIFARE Classic Mini (imported)"
                sectors.size <= 16 -> "MIFARE Classic 1K (imported)"
                else -> "MIFARE Classic 4K (imported)"
            }
            return CardDump(
                uid = uid,
                atqa = atqa,
                sak = sak,
                techList = listOf("MifareClassic", "NfcA"),
                typeName = typeName,
                isMifareClassic = true,
                sizeBytes = sizeBytes,
                sectorCount = sectors.size,
                blockCount = blockCount,
                sectors = sectors.sortedBy { it.index },
            )
        }

        private fun sectorToBlock(sector: Int): Int =
            if (sector < 32) sector * 4 else 128 + (sector - 32) * 16
    }
}
