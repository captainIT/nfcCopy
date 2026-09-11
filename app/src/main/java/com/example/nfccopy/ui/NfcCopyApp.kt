package com.example.nfccopy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nfccopy.AppMode
import com.example.nfccopy.MainViewModel
import com.example.nfccopy.model.CardDump
import com.example.nfccopy.nfc.CardWriter
import com.example.nfccopy.util.toHex

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcCopyApp(vm: MainViewModel, onExport: (String) -> Unit, onImport: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("NFC Copy · 门禁卡读写") }) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val selected = if (vm.mode == AppMode.READ) 0 else 1
            TabRow(selectedTabIndex = selected) {
                Tab(
                    selected = selected == 0,
                    onClick = { vm.switchMode(AppMode.READ) },
                    text = { Text("读卡") },
                    icon = { Icon(Icons.Default.CreditCard, null) },
                )
                Tab(
                    selected = selected == 1,
                    onClick = { vm.switchMode(AppMode.WRITE) },
                    text = { Text("写卡") },
                    icon = { Icon(Icons.Default.Edit, null) },
                )
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusCard(status = vm.status, busy = vm.busy)
                when (vm.mode) {
                    AppMode.READ -> ReadScreen(vm, onExport, onImport)
                    AppMode.WRITE -> WriteScreen(vm, onImport)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: String, busy: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(22.dp))
            Text(status, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ReadScreen(vm: MainViewModel, onExport: (String) -> Unit, onImport: () -> Unit) {
    val dump = vm.lastDump

    if (dump == null) {
        HintCard("贴卡到手机背面读取，读到的卡会自动加入下方『读取列表』。也可以导入小米 MCT / JSON dump。")
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FileOpen, contentDescription = null)
            Text("  导入 MCT / JSON dump")
        }
        SavedList(vm)
        return
    }

    CardInfoCard(dump)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.useLastAsSource(); vm.switchMode(AppMode.WRITE) }) {
            Icon(Icons.Default.ContentCopy, null)
            Text("  用于写入")
        }
        OutlinedButton(onClick = { onExport(dump.toJson().toString(2)) }) {
            Icon(Icons.Default.Share, null)
            Text("  导出")
        }
        OutlinedButton(onClick = onImport) {
            Icon(Icons.Default.FileOpen, contentDescription = null)
            Text("  导入")
        }
    }

    SavedList(vm)

    if (dump.isMifareClassic) {
        SectorDump(dump)
    }
}

@Composable
private fun WriteScreen(vm: MainViewModel, onImport: () -> Unit) {
    val source = vm.sourceForWrite
    WarningCard(
        "只有『魔术卡 / CUID / UID 可改卡』才能被写入 UID。普通空白 M1 卡的 Block0 出厂只读，无法克隆卡号。"
    )

    Text("① 从读取列表选择源卡", fontWeight = FontWeight.Bold)
    if (vm.savedCards.isEmpty()) {
        HintCard("读取列表为空。请到『读卡』页贴卡，或导入 MCT / JSON dump。")
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FileOpen, contentDescription = null)
            Text("  导入 MCT / JSON dump")
        }
        return
    }
    SavedList(vm, selectable = true)

    if (source == null) {
        HintCard("请在上方列表点选一张卡作为源卡。")
        return
    }

    Spacer(Modifier.height(4.dp))
    Text("② 写入设置", fontWeight = FontWeight.Bold)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("已选源卡", fontWeight = FontWeight.Bold)
            Kv("类型", source.typeName)
            Kv("UID", source.uidHex)
            Kv("可读块", "${source.readableBlocks} / ${source.blockCount}")
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = vm.writeTrailers, onCheckedChange = { vm.updateWriteTrailers(it) })
                Text("  同时写入扇区尾块(密钥/权限) — 有锁死扇区风险，谨慎开启")
            }
            Text(
                "把空白魔术卡贴到手机背面即可开始写入。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    vm.writeResult?.let { WriteResultCard(it) }
}

@Composable
private fun SavedList(vm: MainViewModel, selectable: Boolean = false) {
    val cards = vm.savedCards
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "读取列表 (${cards.size})",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (cards.isNotEmpty()) {
            OutlinedButton(onClick = { vm.clearSavedCards() }) { Text("全部清空") }
        }
    }
    if (cards.isEmpty()) {
        Text("（空）暂无已读取/导入的卡。", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val selectedId = vm.sourceForWrite?.id
    for (card in cards) {
        SavedCardRow(
            card = card,
            selected = selectable && card.id == selectedId,
            selectable = selectable,
            onSelect = { vm.selectForWrite(card) },
            onDelete = { vm.removeCard(card) },
        )
    }
}

@Composable
private fun SavedCardRow(
    card: CardDump,
    selected: Boolean,
    selectable: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    card.uidHex,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
                Text(
                    "${card.typeName} · ${card.readableBlocks}/${card.blockCount} 块",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (selectable) {
                if (selected) {
                    Text("已选", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                } else {
                    OutlinedButton(onClick = onSelect) { Text("选择") }
                }
            }
            OutlinedButton(onClick = onDelete) { Text("删除") }
        }
    }
}

@Composable
private fun CardInfoCard(dump: CardDump) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("卡片信息", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Kv("类型", dump.typeName)
            Kv("UID", dump.uidHex)
            dump.atqa?.let { Kv("ATQA", it.toHex(" ")) }
            dump.sak?.let { Kv("SAK", "0x%02X".format(it)) }
            Kv("技术", dump.techList.joinToString(", "))
            if (dump.isMifareClassic) {
                Kv("容量", "${dump.sizeBytes} B / ${dump.sectorCount} 扇区 / ${dump.blockCount} 块")
                Kv("已解密块", "${dump.readableBlocks} / ${dump.blockCount}")
            } else {
                Text(
                    "该卡不是 MIFARE Classic，无法进行扇区读取/克隆。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SectorDump(dump: CardDump) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("扇区数据", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            for (sector in dump.sectors) {
                val keyInfo = buildString {
                    append("Sector ${sector.index}")
                    sector.keyA?.let { append("  KeyA=${it.toHex()}") }
                    sector.keyB?.let { append("  KeyB=${it.toHex()}") }
                    if (sector.keyA == null && sector.keyB == null) append("  (未破解)")
                }
                Text(
                    keyInfo,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                for (block in sector.blocks) {
                    val text = block.data?.toHex(" ") ?: "<未读出 / 认证失败>"
                    Text(
                        "  [%3d] %s".format(block.index, text),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun WriteResultCard(result: CardWriter.WriteResult) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (result.success)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.errorContainer
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(result.summary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            for (b in result.blocks) {
                Text(
                    "[%3d] %s  %s".format(b.block, if (b.ok) "OK " else "FAIL", b.message),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun Kv(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text("$key: ", fontWeight = FontWeight.SemiBold)
        Text(value, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun HintCard(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun WarningCard(text: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
