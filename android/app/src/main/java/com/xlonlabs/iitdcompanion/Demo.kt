package com.xlonlabs.iitdcompanion

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLConnection
import java.util.Collections

/**
 * Demo mode, for Play review and anyone without an IIT Delhi account.
 *
 * Signing in with [USER] / [PASSWORD] makes every tab answer its requests from
 * the pages in assets/demo instead of the network. The pages keep the real
 * portals' https addresses and copy their login forms' markup, so the app's own
 * auto-login, CAPTCHA prompts and RollCall bar run exactly as they do against
 * the real portals. Nothing goes over the network. Same login as the iOS app.
 */
object Demo {
    const val USER = "demo"
    const val PASSWORD = "review2026"

    private const val MOODLE_CAPTCHA_Q = "4 + 9"
    private const val MOODLE_CAPTCHA_A = "13"
    private const val ROLLCALL_CAPTCHA = "R7K4P"

    fun isDemo(c: Creds) = c.kerberos.lowercase() == USER && c.password == PASSWORD

    // Sign-in state per host, like a session cookie; it resets when the app restarts or the
    // login is forgotten. Requests arrive on WebView's IO threads, hence the synchronized set.
    private val signedIn: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    fun reset() = signedIn.clear()

    /** Every request a demo tab makes, answered locally; anything unknown is a 404, never the network. */
    fun serve(ctx: Context, url: Uri): WebResourceResponse {
        val host = url.host.orEmpty()
        val path = url.path.orEmpty().ifEmpty { "/" }
        val reply = when {
            path.startsWith("/_demo/") -> asset(ctx, path.removePrefix("/_demo/"))
            host == "webmail.iitd.ac.in" -> webmail(ctx, url)
            host == "moodle.iitd.ac.in" || host == "moodlenew.iitd.ac.in" -> moodle(ctx, host, path, url)
            host == "rollcall.iitd.ac.in" -> rollcall(ctx, path)
            host == "eacademics.iitd.ac.in" -> eacademics(ctx, path, url)
            host == "oauth.iitd.ac.in" -> oauth(ctx, url)
            else -> null
        }
        return reply ?: notFound()
    }

    /* ---- portals ---- */

    private fun webmail(ctx: Context, q: Uri): WebResourceResponse? {
        val host = "webmail.iitd.ac.in"
        return when (q.getQueryParameter("_task")) {
            "login" -> {
                if (!valid(q.getQueryParameter("_user"), q.getQueryParameter("_pass")))
                    return page(ctx, "webmail-login", "ERROR" to """<div class="error">Login failed.</div>""")
                signedIn += host
                redirect("/?_task=mail&_mbox=INBOX")
            }
            "logout" -> {
                signedIn -= host
                page(ctx, "webmail-login", "ERROR" to """<div class="notice">Successfully logged out.</div>""")
            }
            else -> if (host in signedIn) page(ctx, "webmail") else page(ctx, "webmail-login", "ERROR" to "")
        }
    }

    private fun moodle(ctx: Context, host: String, path: String, q: Uri): WebResourceResponse? {
        val old = host == "moodle.iitd.ac.in"
        val captcha = if (old) """
            <div class="captcha"><label for="valuepkg3">Please solve: <b>$MOODLE_CAPTCHA_Q =</b></label>
            <input type="text" name="valuepkg3" id="valuepkg3" class="form-control" autocomplete="off"></div>
        """.trimIndent() else ""
        fun login(error: String) =
            page(ctx, "moodle-login", "ERROR" to error, "CAPTCHA" to captcha, "REMEMBER" to if (old) "" else "hidden")
        return when (path) {
            "/login/index.php" -> when {
                host in signedIn -> redirect("/my/")
                q.getQueryParameter("username") == null -> login("")
                !valid(q.getQueryParameter("username"), q.getQueryParameter("password")) ->
                    login("""<div class="alert alert-danger" id="loginerrormessage">Invalid login, please try again</div>""")
                old && q.getQueryParameter("valuepkg3")?.trim() != MOODLE_CAPTCHA_A ->
                    login("""<div class="alert alert-danger" id="loginerrormessage">Wrong answer to the security question, please try again</div>""")
                else -> {
                    signedIn += host
                    redirect("/my/")
                }
            }
            "/login/logout.php" -> {
                signedIn -= host
                redirect("/")
            }
            else -> if (host in signedIn) page(ctx, "moodle") else page(ctx, "moodle-guest")
        }
    }

    private fun rollcall(ctx: Context, path: String): WebResourceResponse? {
        val host = "rollcall.iitd.ac.in"
        if (path == "/logout.php") {
            signedIn -= host
            return redirect("/index.php")
        }
        return if (host in signedIn) page(ctx, "rollcall") else page(ctx, "rollcall-landing")
    }

    private fun eacademics(ctx: Context, path: String, q: Uri): WebResourceResponse? {
        val host = "eacademics.iitd.ac.in"
        if (path == "/sportal/login") {
            // the real portal signs out at /sportal/login?logout
            if (q.queryParameterNames.contains("logout")) signedIn -= host
            return if (host in signedIn) redirect("/sportal/home") else page(ctx, "eacademics-landing")
        }
        return if (host in signedIn) page(ctx, "eacademics") else redirect("/sportal/login")
    }

    /** The shared IITD sign-in page; [CLIENTS] says where each app's login lands. */
    private fun oauth(ctx: Context, q: Uri): WebResourceResponse? {
        val client = q.getQueryParameter("client_id")?.takeIf { it in CLIENTS } ?: "rollcall"
        val (app, landing) = CLIENTS.getValue(client)
        fun done(): WebResourceResponse {
            signedIn += Uri.parse(landing).host.orEmpty()
            return redirect(landing)
        }
        fun login(error: String) = page(ctx, "oauth-login", "ERROR" to error, "APP" to app, "CLIENT" to client)
        return when {
            q.getQueryParameter("username") == null -> login("")
            !valid(q.getQueryParameter("username"), q.getQueryParameter("password")) ->
                login("""<p class="error">Invalid username or password.</p>""")
            q.getQueryParameter("ct_captcha")?.trim()?.uppercase() != ROLLCALL_CAPTCHA ->
                login("""<p class="error">The security code entered was incorrect.</p>""")
            else -> done()
        }
    }

    private val CLIENTS = mapOf(
        "rollcall" to ("RollCall" to "https://rollcall.iitd.ac.in/index.php"),
        "eacademics" to ("eAcademics" to "https://eacademics.iitd.ac.in/sportal/home"),
    )

    private fun valid(user: String?, pass: String?) = user?.trim()?.lowercase() == USER && pass == PASSWORD

    /* ---- responses ---- */

    private fun page(ctx: Context, name: String, vararg slots: Pair<String, String>): WebResourceResponse? {
        var text = runCatching { ctx.assets.open("demo/$name.html").use { String(it.readBytes()) } }.getOrNull() ?: return null
        for ((k, v) in slots) text = text.replace("{{$k}}", v)
        return html(text)
    }

    private fun asset(ctx: Context, file: String): WebResourceResponse? {
        if (file.isEmpty() || '/' in file) return null
        val bytes = runCatching { ctx.assets.open("demo/$file").use { it.readBytes() } }.getOrNull() ?: return null
        val (mime, headers) = when (file.substringAfterLast('.')) {
            "css" -> "text/css" to emptyMap()
            "svg" -> "image/svg+xml" to emptyMap()
            "pdf" -> "application/pdf" to mapOf("Content-Disposition" to "attachment; filename=\"$file\"")
            else -> "application/octet-stream" to emptyMap()
        }
        return WebResourceResponse(mime, "utf-8", 200, "OK", headers, ByteArrayInputStream(bytes))
    }

    /** A page that moves on at once; an intercepted response can't send a real 302. */
    private fun redirect(to: String) = html("<!doctype html><script>location.replace(${JSONObject.quote(to)})</script>")

    private fun html(body: String) =
        WebResourceResponse("text/html", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(body.toByteArray()))

    private fun notFound() =
        WebResourceResponse("text/html", "utf-8", 404, "Not Found", emptyMap(),
            ByteArrayInputStream("<!doctype html><p>Not found</p>".toByteArray()))

    /**
     * Demo files are copied out of the app into Downloads, since DownloadManager would fetch
     * the real (missing) address. Returns the saved file's name, or null if it isn't a demo file.
     */
    fun saveDownload(ctx: Context, url: String): String? {
        val uri = Uri.parse(url)
        val file = uri.path?.removePrefix("/_demo/")?.takeIf { uri.path?.startsWith("/_demo/") == true && '/' !in it }
            ?: return null
        val bytes = runCatching { ctx.assets.open("demo/$file").use { it.readBytes() } }.getOrNull() ?: return null
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file)
                put(MediaStore.Downloads.MIME_TYPE, URLConnection.guessContentTypeFromName(file) ?: "application/octet-stream")
            }
            val dest = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            ctx.contentResolver.openOutputStream(dest)?.use { it.write(bytes) } ?: return null
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
            File(dir, file).writeBytes(bytes)
        }
        return file
    }
}
