package com.example.nfccopy

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import com.example.nfccopy.ui.NfcCopyApp
import com.example.nfccopy.ui.theme.NfcCopyTheme
import com.example.nfccopy.util.toHex
import java.nio.charset.Charset

/**
 * Pixel ST 控制器上 [NfcAdapter.setDiscoveryTechnology] 关不掉钱包侦听，
 * 且与 [NfcAdapter.enableReaderMode] 互斥。全程只用独占 ReaderMode，
 * 关掉 Host Routing，并把 NDEF 检查跳过。
 */
class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var viewModel: MainViewModel

    private val readerFlags = NfcAdapter.FLAG_READER_NFC_A or
        NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_NFC_F or
        NfcAdapter.FLAG_READER_NFC_V or
        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

    private val techLists = arrayOf(
        arrayOf(NfcA::class.java.name),
        arrayOf(NfcB::class.java.name),
        arrayOf(NfcF::class.java.name),
        arrayOf(NfcV::class.java.name),
        arrayOf(IsoDep::class.java.name),
        arrayOf(MifareClassic::class.java.name),
        arrayOf(MifareUltralight::class.java.name),
        arrayOf(Ndef::class.java.name),
        arrayOf(NdefFormatable::class.java.name),
    )

    private val importDump = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = try {
            contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charset.forName("UTF-8")).readText()
            }
        } catch (e: Exception) {
            Log.e(TAG, "import dump", e)
            null
        }
        if (text == null) {
            viewModel.markImportFailed("无法读取所选文件")
        } else {
            viewModel.importDumpText(text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application),
        )[MainViewModel::class.java]
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
            NfcCopyTheme {
                NfcCopyApp(
                    vm = viewModel,
                    onExport = ::exportJson,
                    onImport = ::importDumpFile,
                )
            }
        }

        if (nfcAdapter == null) {
            viewModel.markNfcUnavailable()
            Toast.makeText(this, "本机不支持 NFC，无法使用。", Toast.LENGTH_LONG).show()
        }
        dispatchTagFrom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchTagFrom(intent)
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startNfcPolling()
        dispatchTagFrom(intent)
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopNfcPolling()
    }

    override fun onTagDiscovered(tag: Tag) {
        deliverTag(tag, "reader")
    }

    private fun startNfcPolling() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) {
            viewModel.markNfcDisabled()
            Toast.makeText(this, "请在系统设置中打开 NFC。", Toast.LENGTH_LONG).show()
            return
        }
        try {
            adapter.disableForegroundDispatch(this)
        } catch (_: Exception) {
        }
        val options = Bundle().apply {
            // Longer delay keeps weak Fudan M1 clones in the field instead of dropping them.
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 2500)
        }
        try {
            adapter.enableReaderMode(this, this, readerFlags, options)
            Log.i(TAG, "reader mode flags=$readerFlags")
        } catch (e: Exception) {
            Log.w(TAG, "enableReaderMode, fallback to dispatch", e)
            enableForegroundDispatch(adapter)
        }
        viewModel.markReaderReady()
    }

    private fun stopNfcPolling() {
        val adapter = nfcAdapter ?: return
        try {
            adapter.disableForegroundDispatch(this)
        } catch (e: Exception) {
            Log.w(TAG, "disableForegroundDispatch", e)
        }
        try {
            adapter.disableReaderMode(this)
        } catch (e: Exception) {
            Log.w(TAG, "disableReaderMode", e)
        }
        Log.i(TAG, "nfc polling stopped")
    }

    private fun enableForegroundDispatch(adapter: NfcAdapter) {
        val launch = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        val pending = PendingIntent.getActivity(this, 0, launch, flags)
        val ndef = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            try {
                addDataType("*/*")
            } catch (_: IntentFilter.MalformedMimeTypeException) {
            }
        }
        adapter.enableForegroundDispatch(
            this,
            pending,
            arrayOf(
                ndef,
                IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
                IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
            ),
            techLists,
        )
    }

    private fun dispatchTagFrom(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_NDEF_DISCOVERED
        ) {
            return
        }
        if (intent.getBooleanExtra(EXTRA_CONSUMED, false)) return
        val tag = extractTag(intent) ?: return
        intent.putExtra(EXTRA_CONSUMED, true)
        deliverTag(tag, action ?: "intent")
    }

    private fun deliverTag(tag: Tag, source: String) {
        val uid = tag.id?.toHex() ?: "?"
        val techs = tag.techList.joinToString { it.substringAfterLast('.') }
        Log.i(TAG, "tag source=$source uid=$uid techs=$techs")
        viewModel.handleTag(tag)
    }

    private fun extractTag(intent: Intent): Tag? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

    private fun importDumpFile() {
        importDump.launch(arrayOf("*/*", "text/plain", "application/json", "application/octet-stream"))
    }

    private fun exportJson(json: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "NFC card dump")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(send, "导出卡片 JSON"))
    }

    companion object {
        private const val TAG = "NfcCopy"
        private const val EXTRA_CONSUMED = "com.example.nfccopy.TAG_CONSUMED"
    }
}
