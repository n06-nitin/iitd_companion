package com.xlonlabs.iitdcompanion

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

internal val VpnAccent = Color(0xFF56B8D6)
private const val OPENVPN_CONNECT_PKG = "net.openvpn.openvpn"
private const val OPENVPN_PLAY_URL = "https://play.google.com/store/apps/details?id=$OPENVPN_CONNECT_PKG"
private const val MAX_OVPN_BYTES = 512 * 1024

@Composable
fun VpnSheet(onRequestVpn: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val prefs = remember { ctx.getSharedPreferences("ui", Context.MODE_PRIVATE) }

    var installed by remember { mutableStateOf(isOpenVpnInstalled(ctx)) }
    var vpnActive by remember { mutableStateOf(checkVpnActive(ctx)) }
    var lastOvpn by remember { mutableStateOf(prefs.getString("lastOvpn", null)) }
    var stepsOpen by remember { mutableStateOf(lastOvpn == null) }
    var showInstallPrompt by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                installed = isOpenVpnInstalled(ctx)
                vpnActive = checkVpnActive(ctx)
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    DisposableEffect(ctx) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { vpnActive = true }
            override fun onLost(network: Network) { vpnActive = checkVpnActive(ctx) }
        }
        val registered = runCatching {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm?.registerNetworkCallback(req, cb)
            cm != null
        }.getOrDefault(false)
        onDispose { if (registered) runCatching { cm?.unregisterNetworkCallback(cb) } }
    }

    val pickOvpn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (!isOpenVpnInstalled(ctx)) {
            installed = false
            showInstallPrompt = true
            return@rememberLauncherForActivityResult
        }
        val imported = importOvpnToOpenVpn(ctx, uri)
        if (imported != null) {
            lastOvpn = imported
            prefs.edit().putString("lastOvpn", imported).apply()
            stepsOpen = false
        }
    }

    BackHandler(onBack = onClose)

    val (statusLabel, statusDot) = when {
        vpnActive -> "VPN connected" to Portal.ROLLCALL.accent
        else -> "IITD VPN" to VpnAccent
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
    ) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusPill(statusLabel, statusDot, Modifier.weight(1f))
            ClayIconButton(Icons.Rounded.Close, "Close", onClose)
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier.size(72.dp).clay(radius = 24.dp, depth = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.VpnKey, contentDescription = null,
                    tint = if (vpnActive) Portal.ROLLCALL.accent else VpnAccent,
                    modifier = Modifier.size(34.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "IITD VPN", color = Bone, fontSize = 26.sp,
                fontFamily = Display, fontWeight = FontWeight.ExtraBold
            )
            if (lastOvpn != null) {
                Spacer(Modifier.height(4.dp))
                Text(lastOvpn!!, color = Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(24.dp))

            ClayCard {
                ClayButton(
                    text = if (installed) "Open OpenVPN" else "Install OpenVPN",
                    color = if (vpnActive) Portal.ROLLCALL.accent else if (installed) VpnAccent else Bone,
                    contentColor = Ink,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (installed) openOpenVpnConnect(ctx) else openPlayStoreForOpenVpn(ctx)
                }

                Spacer(Modifier.height(12.dp))
                ClayButton(
                    text = "Add .ovpn",
                    modifier = Modifier.fillMaxWidth()
                ) {
                    installed = isOpenVpnInstalled(ctx)
                    if (!installed) showInstallPrompt = true
                    else runCatching { pickOvpn.launch(arrayOf("*/*")) }
                        .onFailure { Toast.makeText(ctx, "Couldn't open files", Toast.LENGTH_SHORT).show() }
                }

                Spacer(Modifier.height(12.dp))
                ClayButton(
                    text = "Request VPN",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRequestVpn
                )
            }

            Spacer(Modifier.height(18.dp))

            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickableNoRipple { stepsOpen = !stepsOpen }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Setup", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (stepsOpen) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(
                visible = stepsOpen,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("1. Request VPN", color = Muted, fontSize = 13.sp)
                    Text("2. Download .ovpn from Webmail", color = Muted, fontSize = 13.sp)
                    Text("3. Add .ovpn & connect in OpenVPN", color = Muted, fontSize = 13.sp)
                }
            }
        }
    }

    if (showInstallPrompt) {
        AlertDialog(
            onDismissRequest = { showInstallPrompt = false },
            containerColor = Clay,
            titleContentColor = Bone,
            textContentColor = Muted,
            title = { Text("Install OpenVPN", fontWeight = FontWeight.SemiBold) },
            text = { Text("Install OpenVPN Connect from Google Play to import your .ovpn file.") },
            confirmButton = {
                TextButton(onClick = {
                    showInstallPrompt = false
                    openPlayStoreForOpenVpn(ctx)
                }) { Text("Install", color = VpnAccent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showInstallPrompt = false }) { Text("Cancel", color = Muted) }
            }
        )
    }
}

private fun isOpenVpnInstalled(ctx: Context): Boolean = runCatching {
    ctx.packageManager.getPackageInfo(OPENVPN_CONNECT_PKG, 0)
    true
}.getOrDefault(false)

private fun checkVpnActive(ctx: Context): Boolean = runCatching {
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
    cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
}.getOrDefault(false)

private fun openOpenVpnConnect(ctx: Context) {
    val launch = runCatching { ctx.packageManager.getLaunchIntentForPackage(OPENVPN_CONNECT_PKG) }.getOrNull()
    if (launch != null) {
        runCatching { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { openPlayStoreForOpenVpn(ctx) }
    } else {
        openPlayStoreForOpenVpn(ctx)
    }
}

private fun openPlayStoreForOpenVpn(ctx: Context) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$OPENVPN_CONNECT_PKG"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = Intent(Intent.ACTION_VIEW, Uri.parse(OPENVPN_PLAY_URL))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(market) }
        .recoverCatching { ctx.startActivity(web) }
        .onFailure { Toast.makeText(ctx, "Couldn't open Play Store", Toast.LENGTH_SHORT).show() }
}

private fun importOvpnToOpenVpn(ctx: Context, uri: Uri): String? {
    val rawName = queryDisplayName(ctx, uri) ?: "iitd.ovpn"
    val bytes = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { stream ->
            val buf = ByteArray(MAX_OVPN_BYTES + 1)
            var total = 0
            while (total < buf.size) {
                val n = stream.read(buf, total, buf.size - total)
                if (n <= 0) break
                total += n
            }
            if (total > MAX_OVPN_BYTES) null else buf.copyOf(total)
        }
    }.getOrNull()

    if (bytes == null || bytes.isEmpty()) {
        Toast.makeText(ctx, "Pick a .ovpn file", Toast.LENGTH_SHORT).show()
        return null
    }

    val text = bytes.toString(Charsets.UTF_8)
    val trimmed = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
    if (trimmed.startsWith("<html", ignoreCase = true) || trimmed.startsWith("<!doctype", ignoreCase = true)) {
        Toast.makeText(ctx, "Invalid .ovpn — re-download it", Toast.LENGTH_LONG).show()
        return null
    }
    val looksLikeOvpn = text.contains("remote ", ignoreCase = true) &&
        (text.contains("BEGIN CERTIFICATE") || text.contains("dev tun", ignoreCase = true) || text.contains("client"))
    if (!looksLikeOvpn) {
        Toast.makeText(ctx, "Pick a valid .ovpn file", Toast.LENGTH_SHORT).show()
        return null
    }

    val safeName = rawName.replace(Regex("""[^A-Za-z0-9._-]"""), "_").let {
        if (it.endsWith(".ovpn", ignoreCase = true)) it else "$it.ovpn"
    }

    val contentUri = runCatching {
        val dir = File(ctx.cacheDir, "vpn").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, safeName).apply { writeBytes(bytes) }
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", out)
    }.getOrNull()

    if (contentUri == null) {
        Toast.makeText(ctx, "Couldn't read .ovpn file", Toast.LENGTH_SHORT).show()
        return null
    }

    val direct = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(contentUri, "application/x-openvpn-profile")
        setPackage(OPENVPN_CONNECT_PKG)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val opened = runCatching { ctx.startActivity(direct); true }.getOrElse {
        val generic = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/x-openvpn-profile")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            ctx.startActivity(Intent.createChooser(generic, "Open in OpenVPN")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    if (!opened) {
        Toast.makeText(ctx, "Couldn't open OpenVPN", Toast.LENGTH_SHORT).show()
        return null
    }
    return rawName
}

private fun queryDisplayName(ctx: Context, uri: Uri): String? = runCatching {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()
