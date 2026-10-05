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
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
    CookieManager.getInstance().removeAllCookies(null)
    WebStorage.getInstance().deleteAllData()
}

/* ------------------------------ companion ------------------------------ */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompanionScreen(creds: Creds, onEditCreds: () -> Unit, onForget: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    val sessions = remember(creds) { Portal.entries.associateWith { PortalSession(it, creds) } }
    DisposableEffect(sessions) { onDispose { sessions.values.forEach { it.destroy() } } }

    val scope = rememberCoroutineScope()
    val swiper = remember {
        TabSwiper(scope, Portal.entries.firstOrNull { it.name == prefs.getString("lastTab", null) } ?: Portal.entries.first())
    }
    val tab = swiper.current
    // Tabs with a live WebView. Only the open tab gets one at launch; once its page
    // is up the rest warm up one at a time (sign-in-by-themselves portals first), so
    // no WebView is ever built in the middle of a swipe.
    val opened = remember { mutableStateListOf(tab) }
    LaunchedEffect(sessions) {
        val first = sessions.getValue(tab)
        withTimeoutOrNull(3000) { snapshotFlow { first.painted }.first { it } }
        Portal.entries.sortedByDescending { it.autoLogin }.forEach {
            if (it !in opened) {
                delay(700)
                opened += it
            }
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

    BackHandler(enabled = true) {
        val wv = session.webView
        if (wv != null && wv.canGoBack()) wv.goBack()
        else (ctx as? Activity)?.moveTaskToBack(true) // keep the sessions alive
    }

    val keyboardUp = WindowInsets.isImeVisible
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(
            session = sessions.getValue(swiper.visual),
            onEditCreds = onEditCreds,
            onForget = onForget,
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
                    onDown = { sessions.getValue(swiper.current).touchStarted() },
                    pageWants = { dir ->
                        val s = sessions.getValue(swiper.current)
                        s.touchBusy || (s.pagePans != false && s.webView?.canScrollHorizontally(dir) == true)
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
                            // cube turn: each page hinges on the edge it shares with its neighbour
                            val pos = swiper.positionOf(p)
                            if (pos == null) {
                                alpha = 0f
                                return@graphicsLayer
                            }
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
                                it.visibility = if (p == tab || p == swiper.target) View.VISIBLE else View.GONE
                            }
                        )
                        if (!s.painted) LoadingFace(p)
                    }
                }
            }
        }

        if (!keyboardUp) {
            if (tab == Portal.ROLLCALL) RollCallBar(session)
            TabBar(
                swiper = swiper,
                sessions = sessions,
                // tapping the open tab again goes back to that portal's home page
                onSelect = { p -> if (p == tab) sessions.getValue(p).home() else swiper.goTo(p) }
            )
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
        (if (s.portal == Portal.ROLLCALL) "Type the CAPTCHA, then Login" else "Answer the CAPTCHA, then Go") to s.portal.accent
    s.page == "login" -> "Signing in…" to Caution
    s.page == "home" -> "Pick a course below" to s.portal.accent
    else -> s.portal.label to s.portal.accent
}

@Composable
private fun TopBar(
    session: PortalSession,
    onEditCreds: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val (label, dot) = statusOf(session)
    Column(modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusPill(label, dot, Modifier.weight(1f))
            ClayIconButton(Icons.Rounded.Refresh, "Reload") { session.reload() }
            Box {
                ClayIconButton(Icons.Rounded.MoreVert, "More") { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Open in browser") }, onClick = {
                        menuOpen = false
                        val url = session.webView?.url ?: session.portal.home
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    })
                    DropdownMenuItem(text = { Text("Edit saved login") },
                        onClick = { menuOpen = false; onEditCreds() })
                    DropdownMenuItem(text = { Text("Forget login & sign out") },
                        onClick = { menuOpen = false; onForget() })
                }
            }
        }
        // thin page-load bar in the portal's hue; the spacer keeps the layout from jumping
        if (session.progress in 1..99) {
            LinearProgressIndicator(
                progress = { session.progress / 100f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(2.dp).clip(CircleShape),
                color = session.portal.accent, trackColor = Color.Transparent,
            )
        } else Spacer(Modifier.height(2.dp))
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * Floating clay dock. The raised pill slides with the finger, stretches
 * mid-swipe and shifts from one portal's hue to the next.
 */
@Composable
private fun TabBar(swiper: TabSwiper, sessions: Map<Portal, PortalSession>, onSelect: (Portal) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
            .clay(radius = 28.dp, depth = 12.dp)
            .swipeTabs(swiper)
            .padding(6.dp)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))) {
            val itemWidth = maxWidth / Portal.entries.size
            // one pill per lap: when the ring wraps, it slides off one end and in at the other
            for (lap in -1..1) {
                Box(
                    Modifier
                        .layout { measurable, _ ->
                            val stretch = sin(PI.toFloat() * swiper.progress)
                            val w = ((itemWidth - 6.dp).toPx() + 22.dp.toPx() * stretch).roundToInt()
                            val h = DockHeight.roundToPx()
                            val placeable = measurable.measure(Constraints.fixed(w, h))
                            layout(w, h) {
                                val slot = swiper.indicator() + lap * Portal.entries.size
                                placeable.place((itemWidth.toPx() * (slot + 0.5f) - w / 2f).roundToInt(), 0)
                            }
                        }
                        .clay({ lerp(swiper.current.accent, (swiper.target ?: swiper.current).accent, swiper.progress) },
                            radius = 22.dp, depth = 6.dp)
                )
            }
            Row(Modifier.fillMaxWidth().height(DockHeight)) {
                Portal.entries.forEach { p ->
                    val active = p == swiper.visual
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
                            fontSize = 11.sp, fontFamily = Display, fontWeight = FontWeight.SemiBold,
                            color = if (active) Ink else Muted,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

private val DockHeight = 56.dp

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
        Text(code, color = if (active) Ink else Bone, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}
