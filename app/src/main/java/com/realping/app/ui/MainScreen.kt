// app/src/main/java/com/realping/app/ui/MainScreen.kt
package com.realping.app.ui

import android.database.Cursor
import android.provider.OpenableColumns
import android.util.Base64
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.realping.app.R
import com.realping.app.data.RoutingEntry
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: MainViewModel,
    // امضاهای قدیمی برای سازگاری باقی مانده‌اند؛ استفاده نمی‌شوند
    onImportClipboard: () -> Unit = {},
    onImportPlain: () -> Unit = {},
    onImportEncrypted: () -> Unit = {},
    onExtra: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    val isConnecting by vm.isConnecting.collectAsState()
    val isConnected by vm.isConnected.collectAsState()
    val stats by vm.stats.collectAsState()
    val elapsed by vm.elapsedMs.collectAsState()
    val routes by vm.routings.collectAsState()
    val selected by vm.selectedId.collectAsState()
    val proxyEnabled by vm.proxyEnabled.collectAsState()
    val proxyBind by vm.proxyBindInfo.collectAsState()

    // ------- VPN permission launcher -------
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) vm.performConnect() else vm.onVpnDenied()
    }

    // ------- Picker فایل یک‌پارچه -------
    val launcherAny = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {}
        runCatching { vm.importFile(uri) }
            .onFailure { e ->
                val msg = e.message ?: "خطا در وارد کردن فایل روتینگ."
                scope.launch { snack.showSnackbar(msg) }
            }
    }

    var showImportSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        topBar = { BrandTopBar() },
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showImportSheet = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("افزودن روتینگ") },
            )
        },
        bottomBar = {
            BottomConnectBar(
                isConnected = isConnected,
                isConnecting = isConnecting
            ) {
                if (isConnected) {
                    vm.disconnect()
                } else {
                    val intent = VpnService.prepare(ctx)
                    if (intent != null) vpnPermissionLauncher.launch(intent) else vm.performConnect()
                }
            }
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            HeroHeader(
                isConnected = isConnected,
                elapsedMs = elapsed,
                tx = stats.first, // آپلود
                rx = stats.second  // دانلود
            )

            Spacer(Modifier.height(12.dp))

            ProxyTile(
                enabled = proxyEnabled,
                bindInfo = proxyBind,
                isConnected = isConnected,
                onToggle = { vm.setProxyEnabled(it) }
            )

            Spacer(Modifier.height(8.dp))
            SectionTitle("روتینگ‌ها")

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(routes, key = { it.id }) { entry ->
                    RoutingCard(
                        item = entry,
                        selected = entry.id == selected,
                        locked = isConnected,
                        onSelect = { if (!isConnected) vm.select(entry.id) },
                        onDelete = { vm.delete(entry.id) }
                    )
                }
            }
        }
    }

    // ----- BottomSheet: افزودن روتینگ -----
    if (showImportSheet) {
        ModalBottomSheet(
            onDismissRequest = { showImportSheet = false },
            sheetState = sheetState
        ) {
            ImportItem(
                title = "وارد کردن روتینگ از کلیپ بورد",
                subtitle = "ابتدا کلید روتینگ را کپی کنید",
                icon = { Icon(Icons.Rounded.ContentCopy, null) }
            ) {
                showImportSheet = false
                runCatching { vm.importClipboard() }.onFailure {
                    scope.launch { snack.showSnackbar("کلید روتینگ معتبر نیست") }
                }
            }

            ImportItem(
                title = "افزودن از طریق فایل",
                subtitle = "ایمپورت فایل (.realping)",
                icon = { Icon(Icons.Rounded.FileOpen, null) }
            ) {
                showImportSheet = false
                launcherAny.launch(arrayOf("*/*"))
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ---------------- Top Bar ---------------- */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrandTopBar() {
    val gradient = Brush.horizontalGradient(
        colors = listOf(Color(0xFF2C8F4A), Color(0xFFFFA000))
    )
    CenterAlignedTopAppBar(
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(brush = gradient, fontWeight = FontWeight.ExtraBold)) {
                            append("ریل‌پینگ")
                        }
                    },
                    fontSize = 22.sp
                )
                Text(
                    "اتصال پایدار و پینگ پایین",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

/* ---------------- Import Sheet item ---------------- */
@Composable
private fun ImportItem(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = icon,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 8.dp)
    )
}

/* ---------------- Hero ---------------- */
@Composable
private fun HeroHeader(
    isConnected: Boolean,
    elapsedMs: Long,
    tx: Long,
    rx: Long
) {
    val brand = listOf(
        Color(0xFF0B3A20),
        Color(0xFF2C8F4A),
        Color(0xFFFFA000),
        Color(0xFFFF6D00)
    )
    val bg = if (isConnected) brand else listOf(Color(0xFF1B1B1F), Color(0xFF1B1B1F))
    val statusColor by animateColorAsState(
        targetValue = if (isConnected) Color(0xFF2C8F4A) else Color(0xFFB00020),
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "statusColor"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(bg))
                .clip(RoundedCornerShape(24.dp))
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    tonalElevation = 0.dp,
                    shape = CircleShape,
                    color = Color.Black,
                    modifier = Modifier.size(64.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.logo_realping),
                        contentDescription = null,
                        contentScale = ContentScale.Crop
                    )
                }

                Spacer(Modifier.width(16.dp))

                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val icon = if (isConnected) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.92f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isConnected) "متصل" else "قطع",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp
                        )
                    }

                    AnimatedVisibility(visible = isConnected) {
                        Text(
                            text = "مدت اتصال: ${formatElapsed(elapsedMs)}",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
            }
        }

        if (isConnected) {
            // کارت‌های آماری بزرگ‌تر و خواناتر
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val (rxNum, rxUnit) = humanBytesParts(rx)
                MetricCard(
                    modifier = Modifier.weight(1f),
                    leading = Icons.Rounded.Download,
                    label = "دانلود",
                    valueText = rxNum,
                    unitText = rxUnit
                )
                val (txNum, txUnit) = humanBytesParts(tx)
                MetricCard(
                    modifier = Modifier.weight(1f),
                    leading = Icons.Rounded.Upload,
                    label = "آپلود",
                    valueText = txNum,
                    unitText = txUnit
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    leading = Icons.Rounded.Link,
                    label = "زمان",
                    valueText = formatElapsed(elapsedMs),
                    unitText = ""
                )
            }
        }
    }
}

/* ---------------- Metric Card (جایگزین StatChip) ---------------- */
@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    leading: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    valueText: String, // فقط عدد/زمان
    unitText: String   // واحد؛ برای زمان خالی بماند
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 8.dp,
        modifier = modifier
            .heightIn(min = 85.dp) // بزرگ‌تر از قبل
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    leading,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth()
            ) {
                // مقدار با فونت درشت، هم‌عرض و خوانا
                Text(
                    valueText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    // اعداد هم‌عرض
                    letterSpacing = 0.sp,
                    // برای اعداد tabular
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 22.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (unitText.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        unitText,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/* ---------------- Tiles & Cards ---------------- */
@Composable
private fun ProxyTile(
    enabled: Boolean,
    bindInfo: String?,
    isConnected: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("حالت پروکسی برای کنسول", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                val text = if (enabled && isConnected) {
                    val hint = bindInfo ?: "IP دستگاه"
                    "HTTP Proxy → $hint"
                } else {
                    "ابتدا به روتینگ متصل شوید و پروکسی را روشن کنید."
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { onToggle(it) },
                enabled = isConnected
            )
        }
    }
}

@Composable private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun RoutingCard(
    item: RoutingEntry,
    selected: Boolean,
    locked: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val container = if (selected)
        Brush.horizontalGradient(
            listOf(
                MaterialTheme.colorScheme.primary.copy(.10f),
                MaterialTheme.colorScheme.primary.copy(.22f)
            )
        ) else null

    val borderStroke = if (selected)
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = .65f))
    else
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .then(if (!locked) Modifier.clickable { onSelect() } else Modifier),
        shape = RoundedCornerShape(18.dp),
        border = borderStroke,
        colors = if (container == null)
            CardDefaults.cardColors()
        else
            CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .background(container ?: Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        when {
                            selected -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
            )

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold
                )
                when {
                    locked && selected -> Text(
                        "متصل",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    locked && !selected -> Text(
                        "تغییر روتینگ در حال اتصال امکان‌پذیر نیست",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    selected -> Text(
                        "فعال",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (selected) {
                AssistChip(
                    onClick = { /* no-op */ },
                    label = { Text("انتخاب‌شده") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (locked) Icons.Rounded.Lock else Icons.Rounded.CheckCircle,
                            contentDescription = null
                        )
                    }
                )
            } else {
                IconButton(
                    onClick = onDelete,
                    enabled = true
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = "حذف")
                }
            }
        }
    }
}

/* ---------------- Bottom Connect ---------------- */
@Composable
private fun BottomConnectBar(
    isConnected: Boolean,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    val label = when {
        isConnecting -> "در حال اتصال…"
        isConnected -> "قطع اتصال"
        else -> "اتصال"
    }

    val gradient = if (isConnected)
        Brush.horizontalGradient(listOf(Color(0xFFFF7043), Color(0xFFFFA000)))
    else
        Brush.horizontalGradient(listOf(Color(0xFF2C8F4A), Color(0xFF66BB6A)))

    Surface(tonalElevation = 6.dp) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(gradient)
                .clickable(enabled = !isConnecting) { onClick() }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(targetState = isConnecting, animationSpec = tween(300), label = "btn") { loading ->
                if (loading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.5.dp,
                        color = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/* ---------------- Helpers ---------------- */

/** عدد و واحد جداگانه برای نمایش استاندارد (مثلاً "12.3" + "MB") */
private fun humanBytesParts(v: Long): Pair<String, String> {
    val kb = 1024.0; val mb = kb * 1024; val gb = mb * 1024
    return when {
        v >= gb -> "%.2f".format(v / gb) to "GB"
        v >= mb -> "%.2f".format(v / mb) to "MB"
        v >= kb -> "%.2f".format(v / kb) to "KB"
        else -> v.toString() to "B"
    }
}

/** خروجی ثابت با عرض یکنواخت: همیشه HH:MM:SS */
private fun formatElapsed(ms: Long): String {
    val total = (ms / 1000).toInt().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

/* --- Validation utilities (برای ایمپورت) --- */

private fun readTextHead(ctx: Context, uri: Uri, maxChars: Int = 4096): String {
    ctx.contentResolver.openInputStream(uri).use { input ->
        if (input == null) return ""
        val reader = BufferedReader(InputStreamReader(input, detectUtf(input)))
        val sb = StringBuilder()
        var total = 0
        while (true) {
            val line = reader.readLine() ?: break
            val add = line + "\n"
            total += add.length
            if (total > maxChars) break
            sb.append(add)
        }
        return sb.toString()
    }
}

private fun detectUtf(input: java.io.InputStream): Charset {
    input.mark(3)
    val bom = ByteArray(3)
    val n = input.read(bom)
    input.reset()
    return when {
        n >= 3 && bom[0] == 0xEF.toByte() && bom[1] == 0xBB.toByte() && bom[2] == 0xBF.toByte() -> Charsets.UTF_8
        else -> Charsets.UTF_8
    }
}

private fun getDisplayName(ctx: Context, uri: Uri): String? {
    return runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c: Cursor ->
                if (c.moveToFirst()) c.getString(0) else null
            }
    }.getOrNull()
}

private fun readTextSample(ctx: Context, uri: Uri, maxBytes: Int = 64 * 1024): String {
    return ctx.contentResolver.openInputStream(uri)?.use { input ->
        val buf = ByteArray(maxBytes)
        val n = input.read(buf)
        if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
    } ?: ""
}

private fun stripBomAndClean(s: String): String {
    var t = s
    if (t.isNotEmpty() && t[0] == '\uFEFF') t = t.substring(1) // BOM
    return t.replace("\u0000", "")
}

private fun isLikelyWireGuardConfig(textRaw: String): Boolean {
    val s = stripBomAndClean(textRaw).trim()
    if (s.length < 10) return false

    val low = s.lowercase()

    val hasBlocks = Regex("""(?m)^\s*\[(interface|peer)\]\s*$""").containsMatchIn(low)
    if (hasBlocks) return true

    val hasKeys = Regex("""(?m)^\s*(privatekey|address|dns|mtu|allowedips|endpoint)\s*=""")
        .containsMatchIn(low)
    if (hasKeys) return true

    return false
}

private fun isLikelyEncryptedRealPing(textRaw: String, fileName: String? = null): Boolean {
    val s = stripBomAndClean(textRaw).trim()
    if (s.length < 16) return false

    val extOk = fileName?.endsWith(".realping", ignoreCase = true) == true

    val base64Like = Regex("""^[A-Za-z0-9+/=\s]+$""").matches(s)
    if (!base64Like) return false

    val decodedOk = runCatching {
        val bytes = Base64.decode(s, Base64.DEFAULT)
        bytes != null && bytes.size >= 32
    }.getOrDefault(false)

    return decodedOk || extOk
}
