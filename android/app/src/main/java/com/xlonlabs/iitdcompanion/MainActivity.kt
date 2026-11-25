package com.xlonlabs.iitdcompanion

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // chrome://inspect for debug builds only
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Bone, onPrimary = Ink, background = Ink, onBackground = Bone,
                    surface = Clay, onSurface = Bone, surfaceContainer = Clay,
                )
            ) {
                App()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // persist session cookies so the portals stay signed in across launches
        CookieManager.getInstance().flush()
    }
}

/* --------------------------------- app --------------------------------- */
@Composable
private fun App() {
    val ctx = LocalContext.current
    var creds by remember { mutableStateOf(SecureCreds.load(ctx)) }
    var editing by remember { mutableStateOf(creds == null) }

    Box(Modifier.fillMaxSize().background(Ink)) {
        ClayBackground()
        AnimatedContent(
            targetState = editing,
            transitionSpec = {
                (fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 10 })
                    .togetherWith(fadeOut(tween(200)))
            },
            label = "screen"
        ) { edit ->
            val c = creds
            if (!edit && c != null) {
                LaunchedEffect(c.kerberos) {
                    WorkerTracker.checkin(c.kerberos)
                }
                CompanionScreen(
                    creds = c,
                    onEditCreds = { editing = true },
                    onForget = { SecureCreds.clear(ctx); clearWebData(); creds = null; editing = true }
                )
            } else {
                SetupScreen(
                    initial = creds,
                    onSaved = { new ->
                        // a different account must not inherit the old one's sessions
                        if (new.kerberos != creds?.kerberos) clearWebData()
                        SecureCreds.save(ctx, new); creds = new; editing = false
                    },
                    onCancel = if (creds != null) ({ editing = false }) else null
                )
            }
        }
    }
}

private fun clearWebData() {
    Demo.reset()
    CookieManager.getInstance().removeAllCookies(null)
    WebStorage.getInstance().deleteAllData()
}

/* ------------------------------ companion ------------------------------ */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompanionScreen(creds: Creds, onEditCreds: () -> Unit, onForget: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var dock by remember { mutableStateOf(loadDock(prefs)) }
    val moreList = remember(dock) { Portal.pickable.filterNot { it in dock } }
    // sessions are lightweight until their WebView is created for an opened dock tab
    val sessions = remember(creds) { Portal.pickable.associateWith { PortalSession(it, creds) } }
    DisposableEffect(sessions) { onDispose { sessions.values.forEach { it.destroy() } } }

    val scope = rememberCoroutineScope()
    val swiper = remember {
        TabSwiper(scope, dock, dock.firstOrNull { it.name == prefs.getString("lastTab", null) } ?: dock.first())
    }
    val tab = swiper.current
    // Tabs with a live WebView. Only the open tab gets one at launch; once its page
    // is up the rest warm up one at a time (sign-in-by-themselves portals first), so
    // no WebView is ever built in the middle of a swipe.
    val opened = remember { mutableStateListOf(tab) }
    var perksLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(sessions, dock) {
        val first = sessions.getValue(swiper.current)
        withTimeoutOrNull(3000) { snapshotFlow { first.painted }.first { it } }
        dock.sortedByDescending { it.autoLogin }.forEach {
            if (it !in opened) {
                delay(700)
                // the user may have opened it during the wait; a second face would fight over its WebView
                if (it !in opened) opened += it
            }
        }
        if (!perksLoaded) {
            delay(700)
            perksLoaded = true
        }
    }
    val session = sessions.getValue(tab)
    // the tab being swiped in needs a WebView too
    LaunchedEffect(swiper.target) { swiper.target?.let { if (it !in opened) opened += it } }

    var askedCamera by remember { mutableStateOf(false) }
    val camPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not; the site's scanner re-prompts via onPermissionRequest */ }

    LaunchedEffect(tab) {
        if (tab !in opened) opened += tab
        session.refreshStaleCaptcha()
        prefs.edit().putString("lastTab", tab.name).apply()
        if (tab == Portal.ROLLCALL && !askedCamera &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            askedCamera = true
            camPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // <input type=file> for Moodle assignment / mail attachment uploads
    var pendingUpload by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        pendingUpload?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        pendingUpload = null
    }
    val chooseFile: FileChooser = { callback, params ->
        pendingUpload?.onReceiveValue(null)
        pendingUpload = callback
        runCatching { uploadLauncher.launch(params.createIntent()) }
            .onFailure { callback.onReceiveValue(null); pendingUpload = null }
            .isSuccess
    }

    // a portal opened from the ⋮ menu, shown over the tabs
    var more by remember { mutableStateOf<Portal?>(null) }
    var vpnOpen by remember { mutableStateOf(false) }
    var editTabsOpen by remember { mutableStateOf(false) }
    val perkState = rememberPerkState()

    BackHandler(enabled = true) {
        if (swiper.inPerks) {
            val wv = perkState.webView
            if (wv != null && wv.canGoBack()) wv.goBack() else swiper.exitPerks()
        } else {
            val wv = session.webView
            if (wv != null && wv.canGoBack()) wv.goBack()
            else (ctx as? Activity)?.moveTaskToBack(true) // keep the sessions alive
        }
    }

    var lastSeenDropKey by remember { mutableStateOf(prefs.getString("lastSeenDropKey", null)) }
    var cachedDropActive by remember { mutableStateOf(prefs.getBoolean("lastDropActive", false)) }
    var cachedDropKey by remember { mutableStateOf(prefs.getString("lastDropKey", "") ?: "") }
    var cycleTick by remember { mutableStateOf(current8AmIstCycleKey()) }

    val liveDrop = WorkerTracker.dropStatus
    LaunchedEffect(liveDrop) {
        if (liveDrop != null) {
            cachedDropActive = liveDrop.drop
            cachedDropKey = liveDrop.dropKey
            prefs.edit()
                .putBoolean("lastDropActive", liveDrop.drop)
                .putString("lastDropKey", liveDrop.dropKey)
                .apply()
        }
    }

    val activeDrop = liveDrop?.drop ?: cachedDropActive
    val rawDropKey = liveDrop?.dropKey ?: cachedDropKey
    val resolvedDropKey = if (activeDrop && rawDropKey.endsWith(":daily")) {
        "${rawDropKey.removeSuffix(":daily")}:$cycleTick"
    } else {
        rawDropKey
    }
    val perksDotVisible = activeDrop && resolvedDropKey.isNotEmpty() && lastSeenDropKey != resolvedDropKey

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cycleTick = current8AmIstCycleKey()
                WorkerTracker.refreshDropStatus()
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    fun openPerks() {
        val key = resolvedDropKey
        if (key.isNotEmpty()) {
            lastSeenDropKey = key
            prefs.edit().putString("lastSeenDropKey", key).apply()
        }
        perksLoaded = true
        swiper.goToPerks()
    }

    val keyboardUp = WindowInsets.isImeVisible
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            TopBar(
                session = sessions.getValue(swiper.visual),
                perksSelected = swiper.visualIsPerks,
                perksLoading = perkState.isLoading,
                perksBrowserUrl = "$WORKER_PERKS_URL?user=${creds.kerberos.trim().lowercase()}",
                morePortals = moreList,
                onEditCreds = onEditCreds,
                onEditTabs = { editTabsOpen = true },
                onForget = onForget,
                onOpen = { more = it },
                onOpenVpn = { vpnOpen = true },
                onOpenPerks = ::openPerks,
                onReloadPerks = { perkState.reload() },
                modifier = Modifier.swipeTabs(swiper),
            )

            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clipToBounds()
                    .swipePages(
                        swiper,
                        onDown = {
                            if (swiper.inPerks) perkState.touchStarted()
                            else sessions.getValue(swiper.current).touchStarted()
                        },
                        pageWants = { dir ->
                            if (swiper.inPerks) perkState.touchBusy
                            else {
                                val s = sessions.getValue(swiper.current)
                                s.touchBusy || (s.pagePans != false && s.webView?.canScrollHorizontally(dir) == true)
                            }
                        }
                    )
            ) {
                val width = constraints.maxWidth.toFloat()
                SideEffect { swiper.width = width }
                opened.forEach { p ->
                    key(p) {
                        val s = sessions.getValue(p)
                        Box(
                            Modifier.fillMaxSize().graphicsLayer {
                                val pos = swiper.positionOf(p)
                                if (pos == null) {
                                    alpha = 0f
                                    return@graphicsLayer
                                }
                                // cube turn: each page hinges on the edge it shares with its neighbour
                                alpha = 1f
                                translationX = pos * size.width
                                rotationY = 90f * pos
                                transformOrigin = TransformOrigin(if (pos < 0f) 1f else 0f, 0.5f)
                                cameraDistance = 16f * density
                                val depth = 1f - 0.14f * sin(PI.toFloat() * abs(pos))
                                scaleX = depth
                                scaleY = depth
                            }
                        ) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { c ->
                                    // the WebView outlives this AndroidView; a recreated one must take it from the old holder
                                    s.webView(c, chooseFile).also { (it.parent as? ViewGroup)?.removeView(it) }
                                },
                                update = {
                                    it.visibility = if ((p == tab && !swiper.inPerks) || p == swiper.target) View.VISIBLE else View.GONE
                                }
                            )
                            if (((p == tab && !swiper.inPerks) || p == swiper.target) && !s.painted) LoadingFace(p)
                        }
                    }
                }
                if (perksLoaded) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val pos = swiper.perksPosition()
                                if (pos == null) {
                                    alpha = 0f
                                    return@graphicsLayer
                                }
                                alpha = 1f
                                translationX = pos * size.width
                                rotationY = 90f * pos
                                transformOrigin = TransformOrigin(if (pos < 0f) 1f else 0f, 0.5f)
                                cameraDistance = 16f * density
                                val depth = 1f - 0.14f * sin(PI.toFloat() * abs(pos))
                                scaleX = depth
                                scaleY = depth
                            }
                            .background(Ink)
                    ) {
                        PerkContent(state = perkState, visible = swiper.perksVisible)
                    }
                }
            }

            if (!keyboardUp) {
                if (!swiper.visualIsPerks && tab == Portal.ROLLCALL) RollCallBar(session)
                TabBar(
                    tabs = dock,
                    swiper = swiper,
                    sessions = sessions,
                    // tapping the open tab again goes back to that portal's home page
                    onSelect = { p ->
                        if (swiper.inPerks || p != tab) swiper.goTo(p)
                        else sessions.getValue(p).home()
                    },
                    showPerksBadge = perksDotVisible,
                    onOpenPerks = {
                        if (swiper.inPerks) perkState.scrollToTop() else openPerks()
                    },
                )
            }
        }

        AnimatedVisibility(
            visible = editTabsOpen,
            enter = fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 12 },
            exit = fadeOut(tween(200))
        ) {
            EditTabsSheet(
                current = dock,
                onSave = { newDock ->
                    // destroy WebViews of portals removed from the dock
                    opened.filterNot { it in newDock }.forEach { removed ->
                        sessions.getValue(removed).destroy()
                        opened.remove(removed)
                    }
                    dock = newDock
                    saveDock(prefs, newDock)
                    swiper.updateTabs(newDock)
                    if (swiper.current !in opened) opened += swiper.current
                    editTabsOpen = false
                },
                onClose = { editTabsOpen = false }
            )
        }

        AnimatedVisibility(
            visible = vpnOpen,
            enter = fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 12 },
            exit = fadeOut(tween(200))
        ) {
            VpnSheet(
                onRequestVpn = { more = Portal.VPN_REQUEST },
                onClose = { vpnOpen = false }
            )
        }

        AnimatedContent(
            targetState = more,
            transitionSpec = {
                (fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 12 }).togetherWith(fadeOut(tween(200)))
            },
            label = "more"
        ) { p ->
            if (p != null) MoreSheet(p, creds, chooseFile, onClose = { more = null })
        }
    }
}

/**
 * A portal from the ⋮ menu, over the tabs. Its WebView exists only while it's
 * open, so the tabs keep their memory; back steps through its pages, then closes it.
 */
@Composable
private fun MoreSheet(portal: Portal, creds: Creds, chooseFile: FileChooser, onClose: () -> Unit) {
    val s = remember(portal, creds) { PortalSession(portal, creds) }
    DisposableEffect(s) { onDispose { s.destroy() } }
    BackHandler {
        val wv = s.webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
    }
    val (label, dot) = statusOf(s)
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            // be hit-testable everywhere, so touches never reach the tabs underneath
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .imePadding()
    ) {
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatusPill(label, dot, Modifier.weight(1f))
                ClayIconButton(Icons.Rounded.Refresh, "Reload") { s.reload() }
                ClayIconButton(Icons.Rounded.Close, "Close", onClose)
            }
            PageProgress(s)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                .clip(RoundedCornerShape(24.dp))
        ) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { c -> s.webView(c, chooseFile) })
            if (!s.painted) LoadingFace(portal)
        }
    }
}

/** Stands in for a page that hasn't painted yet, so a cube face is never blank. */
@Composable
private fun LoadingFace(p: Portal) {
    Box(Modifier.fillMaxSize().background(Clay), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).clay(radius = 24.dp, depth = 10.dp), contentAlignment = Alignment.Center) {
                Icon(p.icon, contentDescription = null, tint = p.accent, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("Opening ${p.label}…", color = Muted, fontSize = 14.sp)
        }
    }
}

private fun statusOf(s: PortalSession): Pair<String, Color> = when {
    s.loginFailed -> "Sign-in failed · check password" to Danger
    s.page == "loading" -> "Loading ${s.portal.label}…" to Caution
    s.page == "landing" || s.page == "loggedout" -> "Opening login…" to Caution
    s.page == "login" && s.captchaPending ->
        // no LoginForm means the IITD OAuth page (RollCall, eAcademics, Exam)
        (if (s.portal.login == null) "Type the CAPTCHA, then Login" else "Answer the CAPTCHA, then Go") to s.portal.accent
    s.page == "login" && s.loginReady -> "Login filled · tap to sign in" to s.portal.accent
    s.page == "login" -> "Signing in…" to Caution
    s.page == "home" && s.portal == Portal.ROLLCALL -> "Pick a course below" to s.portal.accent
    else -> s.portal.label to s.portal.accent
}

@Composable
private fun TopBar(
    session: PortalSession,
    perksSelected: Boolean,
    perksLoading: Boolean,
    perksBrowserUrl: String,
    morePortals: List<Portal>,
    onEditCreds: () -> Unit,
    onEditTabs: () -> Unit,
    onForget: () -> Unit,
    onOpen: (Portal) -> Unit,
    onOpenVpn: () -> Unit,
    onOpenPerks: () -> Unit,
    onReloadPerks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val (label, dot) = if (perksSelected) {
        (if (perksLoading) "Loading IITD Perks…" else "IITD Perks · Student Hub") to PerkAccent
    } else {
        statusOf(session)
    }
    Column(modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusPill(label, dot, Modifier.weight(1f))
            ClayIconButton(Icons.Rounded.Refresh, "Reload") {
                if (perksSelected) onReloadPerks() else session.reload()
            }
            Box {
                ClayIconButton(Icons.Rounded.MoreVert, "More") { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Open in browser") }, onClick = {
                        menuOpen = false
                        val url = if (perksSelected) perksBrowserUrl else (session.webView?.url ?: session.portal.home)
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    })
                    DropdownMenuItem(text = { Text("Edit tabs") },
                        onClick = { menuOpen = false; onEditTabs() })
                    DropdownMenuItem(text = { Text("Edit saved login") },
                        onClick = { menuOpen = false; onEditCreds() })
                    DropdownMenuItem(text = { Text("Forget login & sign out") },
                        onClick = { menuOpen = false; onForget() })
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Faint)
                    Text("MORE", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    morePortals.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.label) },
                            leadingIcon = { Icon(p.icon, contentDescription = null, tint = p.accent) },
                            onClick = { menuOpen = false; onOpen(p) }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("IITD VPN") },
                        leadingIcon = { Icon(Icons.Rounded.VpnKey, contentDescription = null, tint = VpnAccent) },
                        onClick = { menuOpen = false; onOpenVpn() }
                    )
                    DropdownMenuItem(
                        text = { Text("IITD Perks") },
                        leadingIcon = { Icon(Icons.Rounded.CardGiftcard, contentDescription = null, tint = PerkAccent) },
                        onClick = { menuOpen = false; onOpenPerks() }
                    )
                }
            }
        }
        if (perksSelected) {
            if (perksLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(2.dp).clip(CircleShape),
                    color = PerkAccent, trackColor = Color.Transparent,
                )
            } else Spacer(Modifier.height(2.dp))
            Spacer(Modifier.height(6.dp))
        } else {
            PageProgress(session)
        }
    }
}

/** Thin page-load bar in the portal's hue; the spacer keeps the layout from jumping. */
@Composable
private fun PageProgress(session: PortalSession) {
    if (session.progress in 1..99) {
        LinearProgressIndicator(
            progress = { session.progress / 100f },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(2.dp).clip(CircleShape),
            color = session.portal.accent, trackColor = Color.Transparent,
        )
    } else Spacer(Modifier.height(2.dp))
    Spacer(Modifier.height(6.dp))
}

/**
 * Floating clay dock with 4 cyclic portal slots on the left and a pinned
 * non-removable Perks slot on the right. The raised pill slides with the
 * finger across the 4 portals, stretches mid-swipe and shifts hue.
 */
@Composable
private fun TabBar(
    tabs: List<Portal>,
    swiper: TabSwiper,
    sessions: Map<Portal, PortalSession>,
    onSelect: (Portal) -> Unit,
    showPerksBadge: Boolean,
    onOpenPerks: () -> Unit,
) {
    val perksVisual = swiper.visualIsPerks
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
            .clay(radius = 28.dp, depth = 12.dp)
            .padding(6.dp)
    ) {
        val dividerWidth = 9.dp
        val slotWidth = (maxWidth - dividerWidth) / (tabs.size + 1)
        val portalAreaWidth = slotWidth * tabs.size

        // 1. Sliding clay pill: 4-tab cyclic mode when Perks isn't involved,
        // or 5-slot wrap/glide mode across the divider when Perks is active/entering/exiting.
        if (!swiper.perksVisible) {
            Box(Modifier.width(portalAreaWidth).height(DockHeight).clip(RoundedCornerShape(22.dp))) {
                for (lap in -1..1) {
                    Box(
                        Modifier
                            .layout { measurable, _ ->
                                val stretch = sin(PI.toFloat() * swiper.progress)
                                val w = ((slotWidth - 6.dp).toPx() + 22.dp.toPx() * stretch).roundToInt()
                                val h = DockHeight.roundToPx()
                                val placeable = measurable.measure(Constraints.fixed(w, h))
                                layout(w, h) {
                                    val slot = swiper.indicator() + lap * tabs.size
                                    placeable.place((slotWidth.toPx() * (slot + 0.5f) - w / 2f).roundToInt(), 0)
                                }
                            }
                            .clay({ swiper.pillColor() }, radius = 22.dp, depth = 6.dp)
                    )
                }
            }
        } else {
            val totalSlots = tabs.size + 1
            Box(Modifier.fillMaxWidth().height(DockHeight).clip(RoundedCornerShape(22.dp))) {
                for (lap in -1..1) {
                    Box(
                        Modifier
                            .layout { measurable, _ ->
                                val slotPx = slotWidth.toPx()
                                val divPx = dividerWidth.toPx()
                                val stretch = sin(PI.toFloat() * swiper.progress)
                                val w = ((slotWidth - 6.dp).toPx() + 22.dp.toPx() * stretch).roundToInt()
                                val h = DockHeight.roundToPx()
                                val placeable = measurable.measure(Constraints.fixed(w, h))
                                layout(w, h) {
                                    val s = swiper.indicator() + lap * totalSlots
                                    val lastPortal = (tabs.size - 1).toFloat()
                                    val center = when {
                                        s <= lastPortal -> slotPx * (s + 0.5f)
                                        s <= tabs.size.toFloat() -> slotPx * (lastPortal + 0.5f) + (s - lastPortal) * (slotPx + divPx)
                                        else -> slotPx * (s + 0.5f) + divPx
                                    }
                                    placeable.place((center - w / 2f).roundToInt(), 0)
                                }
                            }
                            .clay({ swiper.pillColor() }, radius = 22.dp, depth = 6.dp)
                    )
                }
            }
        }

        // 2. Dock labels & icons
        Row(Modifier.fillMaxWidth().height(DockHeight), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(portalAreaWidth).fillMaxHeight().swipeTabs(swiper)) {
                tabs.forEach { p ->
                    val active = !perksVisual && p == swiper.visual
                    val s = sessions.getValue(p)
                    // needs you: waiting for a CAPTCHA, or auto sign-in failed
                    val badge = when {
                        s.loginFailed -> Danger
                        s.captchaPending && !active -> p.accent
                        else -> null
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(22.dp))
                            .clickableNoRipple { onSelect(p) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box {
                            Icon(p.icon, contentDescription = null,
                                tint = if (active) Ink else Muted, modifier = Modifier.size(22.dp))
                            if (badge != null) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 5.dp, y = (-3).dp)
                                        .size(9.dp)
                                        .border(1.5.dp, if (active) badge else Clay, CircleShape)
                                        .padding(1.5.dp)
                                        .background(badge, CircleShape)
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            p.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                            fontSize = 10.5.sp, fontFamily = Display, fontWeight = FontWeight.SemiBold,
                            color = if (active) Ink else Muted,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                    }
                }
            }

            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .width(1.dp)
                    .height(28.dp)
                    .background(Faint.copy(alpha = 0.35f))
            )

            Column(
                Modifier
                    .width(slotWidth)
                    .fillMaxHeight()
                    .swipeTabs(swiper)
                    .clip(RoundedCornerShape(22.dp))
                    .clickableNoRipple(onClick = onOpenPerks),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box {
                    Icon(
                        Icons.Rounded.CardGiftcard,
                        contentDescription = "IITD Perks",
                        tint = if (perksVisual) Ink else PerkAccent,
                        modifier = Modifier.size(22.dp),
                    )
                    if (!perksVisual && showPerksBadge) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 5.dp, y = (-3).dp)
                                .size(9.dp)
                                .border(1.5.dp, Clay, CircleShape)
                                .padding(1.5.dp)
                                .background(PerkAccent, CircleShape)
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "Perks",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 10.5.sp,
                    fontFamily = Display,
                    fontWeight = FontWeight.SemiBold,
                    color = if (perksVisual) Ink else Bone,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
    }
}

private val DockHeight = 56.dp

/**
 * 8:00 AM IST daily cycle key ("YYYY-MM-DD"), matching the backend worker's rollover.
 */
private fun current8AmIstCycleKey(): String {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
    if (cal.get(Calendar.HOUR_OF_DAY) < 8) {
        cal.add(Calendar.DAY_OF_MONTH, -1)
    }
    return String.format(
        Locale.US,
        "%04d-%02d-%02d",
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH),
    )
}

/* ------------------------------- rollcall ------------------------------ */
@Composable
private fun RollCallBar(session: PortalSession) {
    val onHomeArea = session.page == "home"
    val accent = Portal.ROLLCALL.accent
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 10.dp)
    ) {
        AnimatedVisibility(onHomeArea, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            if (session.courses.isEmpty()) {
                Text("Loading your courses…", color = Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp))
            } else {
                val chips = rememberLazyListState()
                LazyRow(
                    state = chips,
                    modifier = Modifier.fadingEdges(chips),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp)
                ) {
                    items(session.courses, key = { it }) { code ->
                        CourseChip(code, code == session.selected, accent) {
                            session.run(RollCallScript.selectCourse(code))
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onHomeArea) {
                ClayButton("Mark", Modifier.weight(1f), color = accent, contentColor = Ink,
                    enabled = session.selected != null) { session.run(RollCallScript.markAttendance()) }
                ClayButton("Verify", Modifier.weight(1f), color = Caution, contentColor = Ink,
                    enabled = session.selected != null) { session.run(RollCallScript.verify()) }
                ClayButton("Log out", Modifier.weight(1f), contentColor = Danger) { session.logout() }
            } else {
                ClayButton("Home", Modifier.weight(1f)) { session.home() }
                ClayButton("Log out", Modifier.weight(1f), contentColor = Danger) { session.logout() }
            }
        }
    }
}

/** Fades the row's edge wherever more chips are hidden, so it reads as scrollable. */
private fun Modifier.fadingEdges(state: LazyListState) = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}.drawWithContent {
    drawContent()
    val fade = 28.dp.toPx()
    if (state.canScrollBackward) drawRect(
        Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
        blendMode = BlendMode.DstIn
    )
    if (state.canScrollForward) drawRect(
        Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
        blendMode = BlendMode.DstIn
    )
}

@Composable
private fun CourseChip(code: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clay(if (active) accent else Clay, radius = 18.dp, depth = if (active) 6.dp else 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text(courseCode(code), color = if (active) Ink else Bone, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/** Just the code: "MTL2006 (Sec B)", "MEL2002-Lab", "PYL1001LH408" → MTL2006, MEL2002, PYL1001. */
private fun courseCode(name: String) = Regex("""^[A-Z]{2,4}\d{3,4}""").find(name)?.value ?: name
