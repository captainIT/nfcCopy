package com.example.nfccopy

import android.app.Application
import android.nfc.Tag
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.example.nfccopy.data.CardStore
import com.example.nfccopy.model.CardDump
import com.example.nfccopy.nfc.CardReader
import com.example.nfccopy.nfc.CardWriter
import com.example.nfccopy.util.toHex
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class AppMode { READ, WRITE }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val nfcExecutor = Executors.newSingleThreadExecutor()
    private val persistExecutor = Executors.newSingleThreadExecutor()
    private val cardStore = CardStore(application)
    private val inFlight = AtomicBoolean(false)

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

    var mode by mutableStateOf(AppMode.READ)
        private set

    var status by mutableStateOf("将门禁卡贴到手机背面摄像头附近，贴紧保持 2–3 秒…")
        private set

    var busy by mutableStateOf(false)
        private set

    /** The most recently scanned card (read mode). */
    var lastDump by mutableStateOf<CardDump?>(null)
        private set

    /** All cards read or imported this session; newest first. */
    val savedCards = mutableStateListOf<CardDump>()

    /** The source data selected to be cloned onto a magic card (write mode). */
    var sourceForWrite by mutableStateOf<CardDump?>(null)
        private set

    var writeTrailers by mutableStateOf(false)
        private set

    var writeResult by mutableStateOf<CardWriter.WriteResult?>(null)
        private set

    fun switchMode(newMode: AppMode) {
        mode = newMode
        writeResult = null
        status = when (newMode) {
            AppMode.READ -> "读卡模式：贴卡到摄像头附近读取。"
            AppMode.WRITE -> when {
                sourceForWrite != null -> "写卡模式：贴上『空白魔术卡』开始克隆。"
                savedCards.isEmpty() -> "写卡模式：读取列表为空，请先到『读卡』页读取或导入一张卡。"
                else -> "写卡模式：请从下方读取列表选择一张源卡。"
            }
        }
    }

    /** Add a freshly read/imported card to the list, de-duplicating by UID+容量. */
    private fun saveCard(dump: CardDump) {
        val idx = savedCards.indexOfFirst {
            it.uidHex == dump.uidHex && it.blockCount == dump.blockCount
        }
        if (idx >= 0) savedCards[idx] = dump else savedCards.add(0, dump)
        persistSavedCards()
    }

    private fun persistSavedCards() {
        val snapshot = savedCards.toList()
        persistExecutor.execute {
            cardStore.save(snapshot)
        }
    }

    /** Pick a card from the read list as the write source. */
    fun selectForWrite(dump: CardDump) {
        sourceForWrite = dump
        writeResult = null
        status = "已选择源卡 UID=${dump.uidHex}（${dump.typeName}）用于写入。"
    }

    fun removeCard(dump: CardDump) {
        savedCards.removeAll { it.id == dump.id }
        if (sourceForWrite?.id == dump.id) sourceForWrite = null
        if (lastDump?.id == dump.id) lastDump = null
        persistSavedCards()
    }

    fun clearSavedCards() {
        savedCards.clear()
        sourceForWrite = null
        lastDump = null
        status = "已清空读取列表。"
        persistSavedCards()
    }

    fun useLastAsSource() {
        val dump = lastDump ?: return
        selectForWrite(dump)
    }

    fun updateWriteTrailers(value: Boolean) {
        writeTrailers = value
    }

    fun clearRead() {
        lastDump = null
        status = "读卡模式：贴卡到摄像头附近读取。"
    }

    fun markNfcUnavailable() = postUi {
        status = "本机不支持 NFC。"
    }

    fun markNfcDisabled() = postUi {
        status = "系统 NFC 已关闭，请到设置里打开后再贴卡。"
    }

    fun markReaderReady() = postUi {
        if (!busy && lastDump == null && writeResult == null) {
            status = when (mode) {
                AppMode.READ -> "独占读卡已开启（已关钱包侦听）。把卡中心贴在摄像头条正下方，贴紧 2–3 秒。若完全没反应，用下方『导入 dump』。"
                AppMode.WRITE -> if (sourceForWrite == null) {
                    "写卡模式：请从读取列表选择一张源卡。"
                } else {
                    "NFC 已就绪。把空白魔术卡贴到摄像头附近开始写入。"
                }
            }
        }
    }

    fun importDumpText(text: String) = postUi {
        try {
            val dump = CardDump.fromDumpText(text)
            lastDump = dump
            saveCard(dump)
            status = "已导入并加入列表：${dump.typeName}  UID=${dump.uidHex}  可读块 ${dump.readableBlocks}/${dump.blockCount}"
        } catch (e: Exception) {
            status = "导入失败：${e.message}"
        }
    }

    fun markImportFailed(reason: String) = postUi {
        status = "导入失败：$reason"
    }

    /**
     * Foreground dispatch arrives on the main thread; ReaderCallback on a Binder thread.
     * Tag I/O always runs off the UI thread; Compose state is applied on the main thread.
     */
    fun handleTag(tag: Tag) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            nfcExecutor.execute { handleTagInternal(tag) }
        } else {
            handleTagInternal(tag)
        }
    }

    private fun handleTagInternal(tag: Tag) {
        if (!inFlight.compareAndSet(false, true)) {
            Log.i(TAG, "ignored tag, already busy")
            return
        }
        postUi {
            busy = true
            status = if (mode == AppMode.READ) "已发现卡片，读取中…" else "已发现卡片，写入中…"
        }
        try {
            when (mode) {
                AppMode.READ -> {
                    val dump = CardReader.read(tag)
                    Log.i(TAG, "read ok type=${dump.typeName} uid=${dump.uidHex}")
                    postUi {
                        lastDump = dump
                        saveCard(dump)
                        status = "读取成功并加入列表：${dump.typeName}  UID=${dump.uidHex}"
                    }
                }
                AppMode.WRITE -> {
                    val source = sourceForWrite
                    if (source == null) {
                        postUi { status = "尚未选择源卡数据，无法写入。" }
                        return
                    }
                    val result = CardWriter.writeDump(tag, source, writeTrailers)
                    Log.i(TAG, "write done success=${result.success} ${result.summary}")
                    postUi {
                        writeResult = result
                        status = result.summary
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "tag op failed uid=${tag.id?.toHex()}", e)
            postUi { status = "操作失败：${e.message}" }
        } finally {
            inFlight.set(false)
            postUi { busy = false }
        }
    }

    override fun onCleared() {
        nfcExecutor.shutdownNow()
        persistExecutor.shutdown()
        super.onCleared()
    }

    private fun postUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        private const val TAG = "NfcCopy"
    }
}
