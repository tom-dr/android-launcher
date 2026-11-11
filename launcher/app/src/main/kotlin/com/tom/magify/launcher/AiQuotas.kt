package com.tom.magify.launcher

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

const val AI_QUOTAS_WIDGET = "ai_quotas"

private const val PREFS_NAME = "ai_quotas"
private const val USER_AGENT = "MagifyLauncher/1.0 (Android)"
// Quotas move slowly enough that refetching more often than this on every
// return to the home screen would only cost battery and API calls.
private const val STALE_AFTER_MS = 5 * 60_000L
// Unlocking the phone is a deliberate "show me the current state" moment.
private const val STALE_AFTER_UNLOCK_MS = 60_000L
private const val LOGIN_TIMEOUT_MS = 5 * 60_000
private const val TOKEN_EXPIRY_MARGIN_MS = 60_000L
private const val FOREGROUND_NETWORK_DELAY_MS = 700L

enum class AiProvider(val label: String) { CLAUDE("Claude Code"), CODEX("Codex") }

data class QuotaWindow(val label: String, val usedPercent: Int, val resetsAtMillis: Long?)

data class AiQuotaState(
    val connected: Boolean = false,
    val windows: List<QuotaWindow> = emptyList(),
    val plan: String? = null,
    val updatedAtMillis: Long = 0L,
    val loading: Boolean = false,
    val connecting: Boolean = false,
    val error: String? = null
)

/**
 * Fetches the Claude Code and Codex subscription quotas straight from their
 * APIs. The phone signs in with its *own* OAuth session for each service
 * (same public client ids as the CLIs, PKCE + loopback redirect), never a
 * copy of the PC's tokens: both services rotate refresh tokens, so sharing
 * one token family between two devices would log the other one out.
 */
class AiQuotaManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshJobs = ConcurrentHashMap<AiProvider, Job>()
    private val loginJobs = ConcurrentHashMap<AiProvider, Job>()
    private val loginServers = ConcurrentHashMap<AiProvider, ServerSocket>()
    private val pendingExchanges = ConcurrentHashMap<AiProvider, PendingExchange>()

    private val _states = MutableStateFlow(AiProvider.entries.associateWith { loadCachedState(it) })
    val states: StateFlow<Map<AiProvider, AiQuotaState>> = _states.asStateFlow()

    fun refreshIfStale(maxAgeMillis: Long = STALE_AFTER_MS) {
        val now = System.currentTimeMillis()
        AiProvider.entries.forEach { provider ->
            val state = _states.value.getValue(provider)
            if (state.connected && (state.error != null || now - state.updatedAtMillis > maxAgeMillis)) {
                refresh(provider)
            }
        }
    }

    /**
     * Android 16's background firewall cuts the launcher's network while the
     * browser is in front, so a sign-in finished there usually can't trade
     * its code for tokens yet: that happens here, once back on the home screen.
     */
    fun onForeground(refreshQuotas: Boolean, afterUnlock: Boolean = false) {
        scope.launch {
            // Network access is restored a moment after the activity resumes.
            delay(FOREGROUND_NETWORK_DELAY_MS)
            pendingExchanges.keys.forEach { provider -> completePendingLogin(provider) }
            if (refreshQuotas) refreshIfStale(if (afterUnlock) STALE_AFTER_UNLOCK_MS else STALE_AFTER_MS)
        }
    }

    /**
     * Run by [AiQuotaJobService] while the launcher isn't in front: the only
     * fetches allowed to notify a threshold crossing. Returns once done.
     */
    suspend fun refreshInBackground() {
        AiProvider.entries
            .filter { _states.value.getValue(it).connected }
            .mapNotNull { refresh(it, notifyCrossings = true) }
            .forEach { it.join() }
    }

    /**
     * [notifyCrossings] stays false for every fetch made from the launcher's
     * UI: the quotas are on screen then, a notification would be redundant.
     * Such fetches still move the baseline the next crossing is measured from.
     */
    @Synchronized
    fun refresh(provider: AiProvider, notifyCrossings: Boolean = false): Job? {
        refreshJobs[provider]?.takeIf { it.isActive }?.let { return it }
        if (loadTokens(provider) == null) return null
        setState(provider) { it.copy(loading = true) }
        val previousWindows = _states.value.getValue(provider).windows
        return scope.launch {
            try {
                val (windows, plan) = fetchWithValidToken(provider)
                val fresh = AiQuotaState(
                    connected = true,
                    windows = windows,
                    plan = plan,
                    updatedAtMillis = System.currentTimeMillis()
                )
                saveCache(provider, fresh)
                setState(provider) { fresh }
                AiQuotaAlerts.onFreshQuotas(appContext, provider, previousWindows, windows, notifyCrossings)
            } catch (e: ReauthRequiredException) {
                clearProvider(provider)
                setState(provider) { AiQuotaState(error = "Reconnexion nécessaire") }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                setState(provider) { it.copy(loading = false, error = describe(e)) }
            }
        }.also { refreshJobs[provider] = it }
    }

    /** Opens the provider's sign-in page; [openBrowser] is called on the main thread. */
    fun login(provider: AiProvider, openBrowser: (Uri) -> Unit) {
        pendingExchanges.remove(provider)
        loginServers.remove(provider)?.close()
        loginJobs.remove(provider)?.cancel()
        val client = clientFor(provider)
        loginJobs[provider] = scope.launch {
            val server = try {
                ServerSocket(client.port, 8, InetAddress.getByName("127.0.0.1"))
            } catch (e: IOException) {
                setState(provider) { it.copy(error = "Port ${client.port} déjà utilisé") }
                return@launch
            }
            loginServers[provider] = server
            try {
                val verifier = randomUrlSafe(64)
                val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
                val state = randomUrlSafe(32)
                val redirectUri = "http://localhost:${server.localPort}${client.callbackPath}"
                setState(provider) { it.copy(connecting = true, error = null) }
                withContext(Dispatchers.Main) {
                    openBrowser(Uri.parse(client.authorizeUrl(redirectUri, challenge, state)))
                }
                val code = awaitRedirectCode(server, client.callbackPath, state)
                pendingExchanges[provider] = PendingExchange(code, redirectUri, verifier, state)
                completePendingLogin(provider)
            } catch (e: Exception) {
                // Closed or cancelled by a newer attempt / logout: that one
                // owns the state now, leave it alone.
                currentCoroutineContext().ensureActive()
                val message = when (e) {
                    is SocketTimeoutException -> "Connexion expirée"
                    is SocketException -> null
                    else -> describe(e)
                }
                setState(provider) { it.copy(connecting = false, error = message) }
            } finally {
                server.close()
                loginServers.remove(provider, server)
            }
        }
    }

    private fun completePendingLogin(provider: AiProvider) {
        // Taking it out of the map makes this call its only owner, so the
        // login job and onForeground() can never both redeem the same code.
        val pending = pendingExchanges.remove(provider) ?: return
        try {
            val tokens = clientFor(provider).exchangeCode(
                pending.code, pending.redirectUri, pending.codeVerifier, pending.state
            )
            saveTokens(provider, tokens)
            setState(provider) { AiQuotaState(connected = true) }
            refresh(provider)
        } catch (e: HttpStatusException) {
            setState(provider) { it.copy(connecting = false, error = describe(e)) }
        } catch (e: IOException) {
            // Most likely still in the background: retried by onForeground(),
            // unless a newer sign-in attempt has replaced it meanwhile.
            pendingExchanges.putIfAbsent(provider, pending)
            setState(provider) { it.copy(connecting = true, error = null) }
        }
    }

    fun logout(provider: AiProvider) {
        pendingExchanges.remove(provider)
        loginServers.remove(provider)?.close()
        loginJobs.remove(provider)?.cancel()
        refreshJobs.remove(provider)?.cancel()
        clearProvider(provider)
        setState(provider) { AiQuotaState() }
    }

    private fun fetchWithValidToken(provider: AiProvider): Pair<List<QuotaWindow>, String?> {
        val client = clientFor(provider)
        var tokens = loadTokens(provider) ?: throw ReauthRequiredException()
        if (tokens.expiresAtMillis - System.currentTimeMillis() < TOKEN_EXPIRY_MARGIN_MS) {
            tokens = refreshTokens(provider, tokens)
        }
        return try {
            client.fetchQuota(tokens)
        } catch (e: HttpStatusException) {
            if (e.code != 401) throw e
            client.fetchQuota(refreshTokens(provider, tokens))
        }
    }

    private fun refreshTokens(provider: AiProvider, tokens: StoredTokens): StoredTokens {
        val refreshed = try {
            clientFor(provider).refresh(tokens)
        } catch (e: HttpStatusException) {
            if (e.code in 400..403) throw ReauthRequiredException()
            throw e
        }
        saveTokens(provider, refreshed)
        return refreshed
    }

    private fun setState(provider: AiProvider, transform: (AiQuotaState) -> AiQuotaState) {
        _states.update { states -> states + (provider to transform(states.getValue(provider))) }
    }

    private fun clientFor(provider: AiProvider): QuotaClient = when (provider) {
        AiProvider.CLAUDE -> ClaudeQuotaClient
        AiProvider.CODEX -> CodexQuotaClient
    }

    private fun key(provider: AiProvider, name: String) = "${provider.name.lowercase()}_$name"

    private fun loadTokens(provider: AiProvider): StoredTokens? {
        val access = prefs.getString(key(provider, "access"), null) ?: return null
        val refresh = prefs.getString(key(provider, "refresh"), null) ?: return null
        return StoredTokens(
            accessToken = access,
            refreshToken = refresh,
            expiresAtMillis = prefs.getLong(key(provider, "expires"), 0L),
            accountId = prefs.getString(key(provider, "account"), null)
        )
    }

    private fun saveTokens(provider: AiProvider, tokens: StoredTokens) {
        // commit(), not apply(): a rotated refresh token lost to a process
        // death would leave the stored one already invalidated server-side.
        prefs.edit()
            .putString(key(provider, "access"), tokens.accessToken)
            .putString(key(provider, "refresh"), tokens.refreshToken)
            .putLong(key(provider, "expires"), tokens.expiresAtMillis)
            .putString(key(provider, "account"), tokens.accountId)
            .commit()
    }

    private fun clearProvider(provider: AiProvider) {
        prefs.edit().apply {
            listOf("access", "refresh", "expires", "account", "cache").forEach { remove(key(provider, it)) }
        }.apply()
    }

    private fun saveCache(provider: AiProvider, state: AiQuotaState) {
        val windows = JSONArray()
        state.windows.forEach { window ->
            windows.put(
                JSONObject()
                    .put("label", window.label)
                    .put("used", window.usedPercent)
                    .put("resetsAt", window.resetsAtMillis ?: JSONObject.NULL)
            )
        }
        val json = JSONObject()
            .put("windows", windows)
            .put("plan", state.plan ?: JSONObject.NULL)
            .put("updatedAt", state.updatedAtMillis)
        prefs.edit().putString(key(provider, "cache"), json.toString()).apply()
    }

    private fun loadCachedState(provider: AiProvider): AiQuotaState {
        if (loadTokens(provider) == null) return AiQuotaState()
        val json = prefs.getString(key(provider, "cache"), null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return AiQuotaState(connected = true)
        val windows = json.optJSONArray("windows")?.let { array ->
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                QuotaWindow(
                    label = item.getString("label"),
                    usedPercent = item.getInt("used"),
                    resetsAtMillis = if (item.isNull("resetsAt")) null else item.getLong("resetsAt")
                )
            }
        }.orEmpty()
        return AiQuotaState(
            connected = true,
            windows = windows,
            plan = if (json.isNull("plan")) null else json.getString("plan"),
            updatedAtMillis = json.optLong("updatedAt", 0L)
        )
    }

    private fun describe(e: Exception): String = when (e) {
        is UnknownHostException, is ConnectException, is SocketTimeoutException -> "Hors ligne"
        is HttpStatusException -> "Erreur ${e.code}"
        else -> "Erreur : ${e.message.orEmpty().take(60)}"
    }
}

private class StoredTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val accountId: String?
)

/** An authorization code received but not yet traded for tokens. */
private class PendingExchange(
    val code: String,
    val redirectUri: String,
    val codeVerifier: String,
    val state: String
)

private class ReauthRequiredException : IOException("Reconnexion nécessaire")

private class HttpStatusException(val code: Int, body: String) : IOException("HTTP $code: ${body.take(200)}")

private interface QuotaClient {
    /** 0 lets the system pick any free loopback port. */
    val port: Int
    val callbackPath: String
    fun authorizeUrl(redirectUri: String, codeChallenge: String, state: String): String
    fun exchangeCode(code: String, redirectUri: String, codeVerifier: String, state: String): StoredTokens
    fun refresh(tokens: StoredTokens): StoredTokens
    fun fetchQuota(tokens: StoredTokens): Pair<List<QuotaWindow>, String?>
}

/** Endpoints and client id as used by the Claude Code CLI itself. */
private object ClaudeQuotaClient : QuotaClient {
    private const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    private const val AUTHORIZE_URL = "https://claude.com/cai/oauth/authorize"
    private const val TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
    private const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
    private const val PROFILE_URL = "https://api.anthropic.com/api/oauth/profile"
    private const val SCOPES = "user:profile user:inference"

    override val port = 0
    override val callbackPath = "/callback"

    override fun authorizeUrl(redirectUri: String, codeChallenge: String, state: String): String =
        Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("code", "true")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build().toString()

    override fun exchangeCode(code: String, redirectUri: String, codeVerifier: String, state: String): StoredTokens {
        val body = JSONObject()
            .put("grant_type", "authorization_code")
            .put("code", code)
            .put("redirect_uri", redirectUri)
            .put("client_id", CLIENT_ID)
            .put("code_verifier", codeVerifier)
            .put("state", state)
        return tokensFrom(postJson(TOKEN_URL, body), previous = null)
    }

    override fun refresh(tokens: StoredTokens): StoredTokens {
        val body = JSONObject()
            .put("grant_type", "refresh_token")
            .put("refresh_token", tokens.refreshToken)
            .put("client_id", CLIENT_ID)
            .put("scope", SCOPES)
        return tokensFrom(postJson(TOKEN_URL, body), previous = tokens)
    }

    override fun fetchQuota(tokens: StoredTokens): Pair<List<QuotaWindow>, String?> {
        val headers = mapOf(
            "Authorization" to "Bearer ${tokens.accessToken}",
            "anthropic-beta" to "oauth-2025-04-20"
        )
        val json = httpJson(USAGE_URL, headers = headers)
        // The usage endpoint doesn't say which plan these limits belong to
        // (Codex's does); the profile one does, e.g. "claude_pro" -> "Pro".
        // Cosmetic only, so a failure here never hides the quotas.
        val plan = runCatching {
            httpJson(PROFILE_URL, headers = headers).optJSONObject("organization")
                ?.optString("organization_type")
                ?.takeIf { it.isNotEmpty() && it != "null" }
                ?.removePrefix("claude_")
                ?.replaceFirstChar { it.uppercase() }
        }.getOrNull()
        val windows = listOfNotNull(
            json.optJSONObject("five_hour")?.let { claudeWindow("Session 5 h", it) },
            json.optJSONObject("seven_day")?.let { claudeWindow("Semaine", it) }
        )
        return windows to plan
    }

    private fun claudeWindow(label: String, json: JSONObject) = QuotaWindow(
        label = label,
        usedPercent = json.optDouble("utilization", 0.0).roundToInt(),
        resetsAtMillis = json.optString("resets_at").takeIf { it.isNotEmpty() && it != "null" }
            ?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
    )

    private fun tokensFrom(json: JSONObject, previous: StoredTokens?) = StoredTokens(
        accessToken = json.getString("access_token"),
        refreshToken = json.optString("refresh_token").ifEmpty { previous?.refreshToken.orEmpty() },
        expiresAtMillis = System.currentTimeMillis() + json.optLong("expires_in", 3600L) * 1000,
        accountId = null
    )
}

/** Endpoints and client id as used by the Codex CLI's "Sign in with ChatGPT". */
private object CodexQuotaClient : QuotaClient {
    private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private const val ISSUER = "https://auth.openai.com"
    private const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"

    // The only redirect registered for this client, hence a fixed port.
    override val port = 1455
    override val callbackPath = "/auth/callback"

    override fun authorizeUrl(redirectUri: String, codeChallenge: String, state: String): String =
        Uri.parse("$ISSUER/oauth/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", "openid profile email offline_access")
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("id_token_add_organizations", "true")
            .appendQueryParameter("codex_cli_simplified_flow", "true")
            .appendQueryParameter("state", state)
            .appendQueryParameter("originator", "codex_cli_rs")
            .build().toString()

    override fun exchangeCode(code: String, redirectUri: String, codeVerifier: String, state: String): StoredTokens {
        val form = mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to CLIENT_ID,
            "code_verifier" to codeVerifier
        ).entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val json = httpJson(
            "$ISSUER/oauth/token",
            method = "POST",
            body = form,
            contentType = "application/x-www-form-urlencoded"
        )
        return tokensFrom(json, previous = null)
    }

    override fun refresh(tokens: StoredTokens): StoredTokens {
        val body = JSONObject()
            .put("client_id", CLIENT_ID)
            .put("grant_type", "refresh_token")
            .put("refresh_token", tokens.refreshToken)
            .put("scope", "openid profile email")
        return tokensFrom(postJson("$ISSUER/oauth/token", body), previous = tokens)
    }

    override fun fetchQuota(tokens: StoredTokens): Pair<List<QuotaWindow>, String?> {
        val headers = buildMap {
            put("Authorization", "Bearer ${tokens.accessToken}")
            tokens.accountId?.let { put("chatgpt-account-id", it) }
        }
        val json = httpJson(USAGE_URL, headers = headers)
        val rateLimit = json.optJSONObject("rate_limit")
        val windows = listOfNotNull(
            rateLimit?.optJSONObject("primary_window")?.let(::codexWindow),
            rateLimit?.optJSONObject("secondary_window")?.let(::codexWindow)
        )
        val plan = json.optString("plan_type").takeIf { it.isNotEmpty() && it != "null" }
            ?.replaceFirstChar { it.uppercase() }
        return windows to plan
    }

    private fun codexWindow(json: JSONObject): QuotaWindow {
        val seconds = json.optLong("limit_window_seconds", 0L)
        val label = when {
            seconds >= 7 * 86_400L -> "Semaine"
            seconds >= 86_400L -> "${seconds / 86_400L} j"
            seconds > 0 -> "Session ${seconds / 3600L} h"
            else -> "Limite"
        }
        val resetAt = json.optLong("reset_at", 0L)
        return QuotaWindow(
            label = label,
            usedPercent = json.optDouble("used_percent", 0.0).roundToInt(),
            resetsAtMillis = if (resetAt > 0) resetAt * 1000 else null
        )
    }

    private fun tokensFrom(json: JSONObject, previous: StoredTokens?): StoredTokens {
        val access = json.optString("access_token").ifEmpty { previous?.accessToken.orEmpty() }
        val idToken = json.optString("id_token")
        return StoredTokens(
            accessToken = access,
            refreshToken = json.optString("refresh_token").ifEmpty { previous?.refreshToken.orEmpty() },
            expiresAtMillis = jwtClaims(access)?.optLong("exp", 0L)?.takeIf { it > 0 }?.times(1000)
                ?: (System.currentTimeMillis() + 3600_000L),
            accountId = jwtClaims(idToken)?.optJSONObject("https://api.openai.com/auth")
                ?.optString("chatgpt_account_id")?.ifEmpty { null }
                ?: previous?.accountId
        )
    }
}

private fun postJson(url: String, body: JSONObject): JSONObject =
    httpJson(url, method = "POST", body = body.toString(), contentType = "application/json")

private fun httpJson(
    url: String,
    method: String = "GET",
    headers: Map<String, String> = emptyMap(),
    body: String? = null,
    contentType: String? = null
): JSONObject {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/json")
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) throw HttpStatusException(status, text)
        return JSONObject(text)
    } finally {
        connection.disconnect()
    }
}

/**
 * Minimal one-shot HTTP server for the OAuth loopback redirect: the phone's
 * browser lands on http://localhost:<port>/<path>?code=…&state=… once the
 * user has signed in, which is answered with a short "you can go back" page.
 */
private fun awaitRedirectCode(server: ServerSocket, path: String, expectedState: String): String {
    server.soTimeout = LOGIN_TIMEOUT_MS
    while (true) {
        val socket = server.accept()
        try {
            socket.soTimeout = 10_000
            val requestLine = try {
                socket.getInputStream().bufferedReader().readLine().orEmpty()
            } catch (e: SocketTimeoutException) {
                // Browsers open speculative connections that never send anything.
                continue
            }
            val target = requestLine.split(' ').getOrNull(1).orEmpty()
            val uri = Uri.parse("http://localhost$target")
            if (uri.path != path) {
                respond(socket, 404, "")
                continue
            }
            val code = uri.getQueryParameter("code")
            if (uri.getQueryParameter("state") != expectedState || code.isNullOrEmpty()) {
                respond(socket, 400, "Connexion refusée ou expirée. Réessaie depuis le launcher.")
                throw IOException(uri.getQueryParameter("error") ?: "Réponse de connexion invalide")
            }
            respond(socket, 200, "Connexion réussie ✅<br>Tu peux revenir au launcher.")
            return code
        } finally {
            socket.close()
        }
    }
}

private fun respond(socket: Socket, status: Int, message: String) {
    val body = """<!DOCTYPE html><html><head><meta charset="utf-8">""" +
        """<meta name="viewport" content="width=device-width,initial-scale=1"><title>Magify Launcher</title></head>""" +
        """<body style="background:#000;color:#fff;font-family:sans-serif;display:flex;align-items:center;""" +
        """justify-content:center;min-height:100vh;margin:0;text-align:center">""" +
        """<p style="font-size:20px;padding:24px">$message</p></body></html>"""
    val bytes = body.toByteArray()
    val reason = when (status) {
        200 -> "OK"
        404 -> "Not Found"
        else -> "Bad Request"
    }
    val header = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\n" +
        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
    socket.getOutputStream().apply {
        write(header.toByteArray())
        write(bytes)
        flush()
    }
}

private fun jwtClaims(token: String): JSONObject? {
    val payload = token.split('.').getOrNull(1) ?: return null
    return runCatching {
        JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
    }.getOrNull()
}

private val secureRandom = SecureRandom()

private fun randomUrlSafe(byteCount: Int): String =
    base64Url(ByteArray(byteCount).also(secureRandom::nextBytes))

private fun base64Url(bytes: ByteArray): String =
    Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
