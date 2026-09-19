package com.alananasss.kittytune.data.discord

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.alananasss.kittytune.ui.common.QrCodeGenerator
import com.my.kizzy.rpc.KizzyRPC
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

sealed interface RemoteAuthState {
    object Idle : RemoteAuthState
    object Connecting : RemoteAuthState
    data class Ready(
        val fingerprint: String,
        val deepLinkUrl: String,
        val qrBitmap: Bitmap?
    ) : RemoteAuthState
    data class UserScanned(
        val username: String,
        val discriminator: String,
        val avatarUrl: String?
    ) : RemoteAuthState
    data class Success(
        val token: String,
        val username: String?
    ) : RemoteAuthState
    data class Error(val message: String) : RemoteAuthState
    object Canceled : RemoteAuthState
}

class DiscordRemoteAuthManager {

    companion object {
        private const val TAG = "DiscordRemoteAuth"
        private const val GATEWAY_URL = "wss://remote-auth-gateway.discord.gg/?v=2"
        private const val LOGIN_ENDPOINT = "https://discord.com/api/v9/users/@me/remote-auth/login"
        private const val ORIGIN_HEADER = "https://discord.com"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private const val BROWSER_VERSION = "131.0.0.0"
        private const val CLIENT_BUILD_NUMBER = 354323

        /**
         * Discord rejects API calls that do not carry the client fingerprint its web
         * client always sends. Without this header /users/@me/remote-auth/login answers
         * 400, which is why the QR flow could reach the approval step and then fail on
         * the final exchange. The values must stay consistent with USER_AGENT.
         */
        private fun buildSuperProperties(): String {
            val props = JSONObject().apply {
                put("os", "Android")
                put("browser", "Chrome Mobile")
                put("device", "")
                put("system_locale", "en-US")
                put("browser_user_agent", USER_AGENT)
                put("browser_version", BROWSER_VERSION)
                put("os_version", "10")
                put("referrer", "")
                put("referring_domain", "")
                put("referrer_current", "")
                put("referring_domain_current", "")
                put("release_channel", "stable")
                put("client_build_number", CLIENT_BUILD_NUMBER)
                put("client_event_source", JSONObject.NULL)
            }
            return Base64.encodeToString(
                props.toString().toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var currentWebSocket: WebSocket? = null
    private var rsaKeyPair: KeyPair? = null

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val _state = MutableStateFlow<RemoteAuthState>(RemoteAuthState.Idle)
    val state: StateFlow<RemoteAuthState> = _state.asStateFlow()

    fun start() {
        cancel()
        _state.value = RemoteAuthState.Connecting

        scope.launch {
            try {
                // 1. Generate 2048-bit RSA Key Pair
                val keyGen = KeyPairGenerator.getInstance("RSA")
                keyGen.initialize(2048)
                val keyPair = keyGen.generateKeyPair()
                rsaKeyPair = keyPair

                val encodedPublicKey = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)

                // 2. Connect to Remote Auth Gateway WebSocket
                val request = Request.Builder()
                    .url(GATEWAY_URL)
                    .addHeader("Origin", ORIGIN_HEADER)
                    .addHeader("User-Agent", USER_AGENT)
                    .build()

                currentWebSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.d(TAG, "Remote Auth WebSocket opened")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handleMessage(webSocket, text, encodedPublicKey, keyPair)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d(TAG, "Remote Auth WebSocket closing: $code / $reason")
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d(TAG, "Remote Auth WebSocket closed: $code / $reason")
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e(TAG, "Remote Auth WebSocket failure", t)
                        if (_state.value !is RemoteAuthState.Success && _state.value !is RemoteAuthState.Canceled) {
                            _state.value = RemoteAuthState.Error(t.message ?: "Connection error")
                        }
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Remote Auth", e)
                _state.value = RemoteAuthState.Error(e.message ?: "Initialization error")
            }
        }
    }

    private fun handleMessage(
        webSocket: WebSocket,
        text: String,
        encodedPublicKey: String,
        keyPair: KeyPair
    ) {
        try {
            val json = JSONObject(text)
            val op = json.optString("op")
            Log.d(TAG, "Received op: $op")

            when (op) {
                "hello" -> {
                    val heartbeatInterval = json.optLong("heartbeat_interval", 41250L)
                    startHeartbeat(webSocket, heartbeatInterval)

                    // Send init payload with public key
                    val initPayload = JSONObject().apply {
                        put("op", "init")
                        put("encoded_public_key", encodedPublicKey)
                    }
                    webSocket.send(initPayload.toString())
                }

                "nonce_proof" -> {
                    val encryptedNonceStr = json.getString("encrypted_nonce")
                    val encryptedNonceBytes = Base64.decode(encryptedNonceStr, Base64.DEFAULT)

                    val decryptedNonce = decryptRsaOaep(keyPair, encryptedNonceBytes)
                    val md = MessageDigest.getInstance("SHA-256")
                    val digest = md.digest(decryptedNonce)
                    val proof = Base64.encodeToString(
                        digest,
                        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                    )

                    val proofPayload = JSONObject().apply {
                        put("op", "nonce_proof")
                        put("proof", proof)
                    }
                    webSocket.send(proofPayload.toString())
                }

                "pending_remote_init" -> {
                    val fingerprint = json.getString("fingerprint")
                    val deepLinkUrl = "https://discord.com/ra/$fingerprint"
                    val qrBitmap = QrCodeGenerator.generateQrBitmap(deepLinkUrl, 512)
                    _state.value = RemoteAuthState.Ready(fingerprint, deepLinkUrl, qrBitmap)
                }

                "pending_ticket" -> {
                    val encryptedUserPayload = if (json.has("encrypted_user_payload")) json.getString("encrypted_user_payload") else null
                    var username = "Discord User"
                    var discriminator = "0"
                    var avatarUrl: String? = null

                    if (!encryptedUserPayload.isNullOrEmpty()) {
                        try {
                            val decryptedPayloadBytes = decryptRsaOaep(
                                keyPair,
                                Base64.decode(encryptedUserPayload, Base64.DEFAULT)
                            )
                            val payloadStr = String(decryptedPayloadBytes, Charsets.UTF_8)
                            // Format is usually id:discriminator:avatar:username
                            val parts = payloadStr.split(":")
                            if (parts.size >= 4) {
                                val userId = parts[0]
                                discriminator = parts[1]
                                val avatarHash = parts[2]
                                username = parts[3]
                                if (avatarHash.isNotBlank()) {
                                    avatarUrl = "https://cdn.discordapp.com/avatars/$userId/$avatarHash.png"
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to decrypt user payload in pending_ticket", e)
                        }
                    }

                    _state.value = RemoteAuthState.UserScanned(username, discriminator, avatarUrl)
                }

                "pending_login" -> {
                    val ticket = json.getString("ticket")
                    scope.launch {
                        exchangeTicketForToken(ticket, keyPair, webSocket)
                    }
                }

                "cancel" -> {
                    Log.d(TAG, "Remote Auth cancelled by server / user on mobile")
                    cancel()
                    _state.value = RemoteAuthState.Canceled
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: $text", e)
        }
    }

    private fun startHeartbeat(webSocket: WebSocket, intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                try {
                    val heartbeat = JSONObject().apply { put("op", "heartbeat") }
                    webSocket.send(heartbeat.toString())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send heartbeat", e)
                    break
                }
            }
        }
    }

    private suspend fun exchangeTicketForToken(
        ticket: String,
        keyPair: KeyPair,
        webSocket: WebSocket
    ) {
        try {
            val body = JSONObject().apply { put("ticket", ticket) }
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(LOGIN_ENDPOINT)
                .addHeader("Origin", ORIGIN_HEADER)
                .addHeader("Referer", "$ORIGIN_HEADER/")
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("X-Super-Properties", buildSuperProperties())
                .addHeader("X-Discord-Locale", "en-US")
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            val rawBody = response.body.string()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed ticket exchange: HTTP ${response.code} / $rawBody")
                // Surface whatever Discord actually said. Only reading "message" was
                // not enough: field-level rejections come back as e.g.
                // {"ticket":["Value is not a valid ticket"]} with no "message" key,
                // which left the error looking identical to no detail at all.
                val detail = runCatching { JSONObject(rawBody).optString("message") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: rawBody.trim().take(300).takeIf { it.isNotBlank() }
                    ?: "empty response body"
                _state.value = RemoteAuthState.Error(
                    "Failed to exchange ticket: ${response.code} ($detail)"
                )
                return
            }

            val responseJson = JSONObject(rawBody)
            val encryptedTokenStr = responseJson.getString("encrypted_token")
            val encryptedTokenBytes = Base64.decode(encryptedTokenStr, Base64.DEFAULT)
            val decryptedTokenBytes = decryptRsaOaep(keyPair, encryptedTokenBytes)
            val token = String(decryptedTokenBytes, Charsets.UTF_8).trim()

            // Fetch user info for confirmation
            var username: String? = null
            try {
                val userInfoResult = KizzyRPC.getUserInfo(token)
                if (userInfoResult.isSuccess) {
                    val info = userInfoResult.getOrNull()
                    username = info?.name?.ifBlank { null } ?: info?.username
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not fetch user profile after token exchange", e)
            }

            _state.value = RemoteAuthState.Success(token, username)
            try {
                webSocket.close(1000, "Login completed")
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "Error in exchangeTicketForToken", e)
            _state.value = RemoteAuthState.Error(e.message ?: "Token exchange failed")
        }
    }

    private fun decryptRsaOaep(keyPair: KeyPair, data: ByteArray): ByteArray {
        val oaepSpec = OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT
        )
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyPair.private, oaepSpec)
        return cipher.doFinal(data)
    }

    fun cancel() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        try {
            currentWebSocket?.close(1000, "Canceled by user")
        } catch (_: Exception) {}
        currentWebSocket = null
        if (_state.value !is RemoteAuthState.Success) {
            _state.value = RemoteAuthState.Idle
        }
    }
}
