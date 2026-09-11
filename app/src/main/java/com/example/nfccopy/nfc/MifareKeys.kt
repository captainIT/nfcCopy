package com.example.nfccopy.nfc

import com.example.nfccopy.util.hexToBytesOrNull

/**
 * Dictionary of well-known MIFARE Classic keys. These are the factory / transport
 * default keys shipped by many access-control and transit systems that never bothered
 * to change them. Trying this list is exactly what tools like MIFARE Classic Tool do.
 *
 * This only helps when a card still uses a default key. Properly configured cards use
 * diversified secret keys that are NOT in this list and cannot be recovered on a phone.
 */
object MifareKeys {

    val DEFAULT_KEYS: List<ByteArray> = listOf(
        "FFFFFFFFFFFF", // factory default
        "A0A1A2A3A4A5", // MAD / NDEF default key A
        "D3F7D3F7D3F7", // NDEF default key B
        "000000000000",
        "B0B1B2B3B4B5",
        "4D3A99C351DD",
        "1A982C7E459A",
        "AABBCCDDEEFF",
        "714C5C886E97",
        "587EE5F9350F",
        "A0478CC39091",
        "533CB6C723F6",
        "8FD0A4F256E9",
        "0000014B5C31",
        "B578F38A5C61",
        "96A301BCE267",
    ).mapNotNull { it.hexToBytesOrNull() }
}
