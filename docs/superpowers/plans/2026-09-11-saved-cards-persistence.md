# Saved Cards Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist the in-app「读取列表」(`savedCards`) to an app-private JSON file so read/imported cards survive process death.

**Architecture:** Introduce a file-backed `CardStore` that serializes a `JSONArray` of existing `CardDump.toJson()` objects. Convert `MainViewModel` to `AndroidViewModel`, load the list on init, and rewrite the file after every list mutation on a background executor.

**Tech Stack:** Kotlin, Android `filesDir`, `org.json` (already used by `CardDump`), AndroidX `AndroidViewModel` / `ViewModelProvider`, JUnit4 for JVM unit tests of `CardStore`.

## Global Constraints

- Persist **only** `savedCards` (not `lastDump`, `sourceForWrite`, mode, write settings).
- File path: `context.filesDir/saved_cards.json`.
- Reuse `CardDump.toJson()` / `CardDump.fromJson()`; no Room / DataStore / new serialization libs.
- Keep existing de-dupe: same `uidHex` + `blockCount` replaces prior entry.
- Corrupt/missing file → empty list + log; do not crash.
- Git is not initialized yet; **skip `git commit` steps** until the user asks to init/commit/push to GitHub (plan still lists the intended commit messages for then).

---

## File map

| File | Role |
|------|------|
| `app/src/main/java/com/example/nfccopy/data/CardStore.kt` | Load/save `List<CardDump>` as JSON array (atomic write). |
| `app/src/main/java/com/example/nfccopy/MainViewModel.kt` | `AndroidViewModel`; load on init; persist on mutate. |
| `app/src/main/java/com/example/nfccopy/MainActivity.kt` | Provide `AndroidViewModelFactory` when creating VM. |
| `app/src/test/java/com/example/nfccopy/data/CardStoreTest.kt` | JVM unit tests for save/load/corrupt. |
| `app/build.gradle.kts` | Add `junit` test dependency if missing. |

---

### Task 1: CardStore + unit tests

**Files:**
- Create: `app/src/main/java/com/example/nfccopy/data/CardStore.kt`
- Create: `app/src/test/java/com/example/nfccopy/data/CardStoreTest.kt`
- Modify: `app/build.gradle.kts` (add JUnit if needed)

**Interfaces:**
- Consumes: `CardDump.toJson()`, `CardDump.fromJson(JSONObject)`
- Produces:
  - `class CardStore(private val file: File)`
  - `fun load(): List<CardDump>`
  - `fun save(cards: List<CardDump>)`
  - Optional convenience: `constructor(context: Context) : this(File(context.filesDir, FILE_NAME))`
  - `companion object { const val FILE_NAME = "saved_cards.json" }`

- [ ] **Step 1: Ensure JUnit is available**

In `app/build.gradle.kts` under `dependencies`, add if not present:

```kotlin
testImplementation("junit:junit:4.13.2")
```

- [ ] **Step 2: Write the failing unit test**

Create `app/src/test/java/com/example/nfccopy/data/CardStoreTest.kt`:

```kotlin
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
```

- [ ] **Step 3: Run test — expect compile/fail (CardStore missing)**

Run:

```bash
cd /Users/captain/workspace/learn/android/nfcCopy && ./gradlew :app:testDebugUnitTest --tests com.example.nfccopy.data.CardStoreTest
```

Expected: FAIL (unresolved `CardStore` or similar).

- [ ] **Step 4: Implement CardStore**

Create `app/src/main/java/com/example/nfccopy/data/CardStore.kt`:

```kotlin
package com.example.nfccopy.data

import android.content.Context
import android.util.Log
import com.example.nfccopy.model.CardDump
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the read/import card list as a JSON array of [CardDump.toJson] objects.
 * File I/O is synchronous; callers should invoke from a background thread.
 */
class CardStore(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    fun load(): List<CardDump> {
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return emptyList()
            val arr = JSONArray(text)
            buildList {
                for (i in 0 until arr.length()) {
                    add(CardDump.fromJson(arr.getJSONObject(i)))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load failed path=${file.absolutePath}", e)
            emptyList()
        }
    }

    fun save(cards: List<CardDump>) {
        try {
            val arr = JSONArray()
            for (c in cards) arr.put(c.toJson())
            val dir = file.parentFile ?: error("no parent for ${file.path}")
            if (!dir.exists()) dir.mkdirs()
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                // Fallback when rename fails across filesystems / existing target
                file.writeText(arr.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "save failed path=${file.absolutePath}", e)
        }
    }

    companion object {
        const val FILE_NAME = "saved_cards.json"
        private const val TAG = "CardStore"
    }
}
```

Note: `android.util.Log` in unit tests is fine on Roboelectric-less JVM only if tests don't hit the Log path for happy-path cases; corrupt-path catches Log — on pure JVM, `Log` may throw or no-op depending on classpath. Prefer wrapping Log usage so unit tests never require Android Log:

Replace Log calls with a no-Android-friendly approach for the load/save catch blocks used in tests — use `System.err.println` **or** avoid calling `load()` corrupt path's Log by making logging optional. Simplest fix for JVM tests: catch and return empty without Log in a way that still logs on device:

```kotlin
} catch (e: Exception) {
    try {
        Log.e(TAG, "load failed path=${file.absolutePath}", e)
    } catch (_: Throwable) {
        System.err.println("CardStore load failed: ${e.message}")
    }
    emptyList()
}
```

Apply the same pattern in `save`.

- [ ] **Step 5: Run unit tests — expect PASS**

```bash
./gradlew :app:testDebugUnitTest --tests com.example.nfccopy.data.CardStoreTest
```

Expected: `BUILD SUCCESSFUL`, all 3 tests PASS.

- [ ] **Step 6: Commit (deferred)**

Intended later:

```bash
git add app/build.gradle.kts app/src/main/java/com/example/nfccopy/data/CardStore.kt app/src/test/java/com/example/nfccopy/data/CardStoreTest.kt
git commit -m "$(cat <<'EOF'
Add CardStore for JSON persistence of saved card dumps.

EOF
)"
```

---

### Task 2: Wire MainViewModel + MainActivity

**Files:**
- Modify: `app/src/main/java/com/example/nfccopy/MainViewModel.kt`
- Modify: `app/src/main/java/com/example/nfccopy/MainActivity.kt`

**Interfaces:**
- Consumes: `CardStore(context)`, `CardStore.load()`, `CardStore.save(List<CardDump>)`
- Produces: `MainViewModel(application: Application) : AndroidViewModel(application)` that auto-loads `savedCards` and persists after `saveCard` / `removeCard` / `clearSavedCards`

- [ ] **Step 1: Convert MainViewModel to AndroidViewModel and persist**

Replace the class header and add store + persist helpers. Concrete changes to `MainViewModel.kt`:

1. Imports to add:

```kotlin
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.example.nfccopy.data.CardStore
```

2. Change:

```kotlin
class MainViewModel : ViewModel() {
```

to:

```kotlin
class MainViewModel(application: Application) : AndroidViewModel(application) {
```

3. After existing fields (`nfcExecutor`, etc.), add:

```kotlin
private val cardStore = CardStore(application)
private val persistExecutor = Executors.newSingleThreadExecutor()
```

4. In `init` block (add after field declarations):

```kotlin
init {
    persistExecutor.execute {
        val loaded = try {
            cardStore.load()
        } catch (e: Exception) {
            Log.e(TAG, "initial load failed", e)
            emptyList()
        }
        if (loaded.isEmpty()) return@execute
        postUi {
            savedCards.clear()
            savedCards.addAll(loaded)
        }
    }
}
```

5. Add:

```kotlin
private fun persistSavedCards() {
    val snapshot = savedCards.toList()
    persistExecutor.execute {
        cardStore.save(snapshot)
    }
}
```

6. Call `persistSavedCards()` at the end of:
   - `saveCard` (after list mutate)
   - `removeCard` (after mutate / clearing related refs)
   - `clearSavedCards` (after clear)

Example `saveCard`:

```kotlin
private fun saveCard(dump: CardDump) {
    val idx = savedCards.indexOfFirst {
        it.uidHex == dump.uidHex && it.blockCount == dump.blockCount
    }
    if (idx >= 0) savedCards[idx] = dump else savedCards.add(0, dump)
    persistSavedCards()
}
```

7. In `onCleared()`:

```kotlin
override fun onCleared() {
    nfcExecutor.shutdownNow()
    persistExecutor.shutdown()
    super.onCleared()
}
```

Keep the existing `ViewModel` import only if still needed — prefer removing unused `ViewModel` import after switching to `AndroidViewModel`.

- [ ] **Step 2: Update MainActivity ViewModelProvider**

In `MainActivity.onCreate`, replace:

```kotlin
viewModel = ViewModelProvider(this)[MainViewModel::class.java]
```

with:

```kotlin
viewModel = ViewModelProvider(
    this,
    ViewModelProvider.AndroidViewModelFactory.getInstance(application),
)[MainViewModel::class.java]
```

- [ ] **Step 3: Compile verify**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest --tests com.example.nfccopy.data.CardStoreTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Manual device checks**

1. Install debug APK, read or import a card → force-stop app → reopen → card still in「读取列表」.
2. Delete one card → force-stop → reopen → still deleted.
3. Clear all → force-stop → reopen → empty.
4. On write tab, select a persisted card as source (list must be non-empty).

- [ ] **Step 5: Commit (deferred)**

Intended later:

```bash
git add app/src/main/java/com/example/nfccopy/MainViewModel.kt app/src/main/java/com/example/nfccopy/MainActivity.kt
git commit -m "$(cat <<'EOF'
Persist savedCards via CardStore across process restarts.

EOF
)"
```

---

## Spec coverage checklist

| Spec requirement | Task |
|------------------|------|
| Persist only `savedCards` | Task 2 |
| `filesDir/saved_cards.json` | Task 1 (`FILE_NAME` + Context ctor) |
| JSONArray of `toJson` / `fromJson` | Task 1 |
| Load on init | Task 2 `init` |
| Save on save/remove/clear | Task 2 |
| Background I/O | Task 2 `persistExecutor` |
| Corrupt → empty + log | Task 1 |
| Atomic-ish write | Task 1 temp + rename |
| Manual test scenarios | Task 2 Step 4 |
| No Room/DataStore | Global constraint |

## Self-review notes

- No placeholders left in steps.
- `CardStore` / `persistSavedCards` names consistent across tasks.
- Commits deferred until GitHub init per user request.
