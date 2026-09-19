package com.alananasss.kittytune.ui.profile

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/**
 * Renders the hCaptcha challenge Discord requires before it will exchange a
 * remote-auth ticket.
 *
 * The challenge is solved by the user, exactly as intended - this only displays it
 * and passes the resulting token back. The page is loaded against a discord.com base
 * URL because hCaptcha validates the site key against the requesting origin.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HCaptchaWebView(
    siteKey: String,
    rqData: String?,
    modifier: Modifier = Modifier,
    onSolved: (String) -> Unit,
    onError: (String) -> Unit
) {
    val currentOnSolved by rememberUpdatedState(onSolved)
    val currentOnError by rememberUpdatedState(onError)

    // JSONObject.quote handles escaping so a key or rqdata containing quotes or
    // slashes cannot break out of the generated script.
    val html = remember(siteKey, rqData) {
        val keyLiteral = JSONObject.quote(siteKey)
        val rqDataLiteral = rqData?.let { JSONObject.quote(it) }
        val renderOptions = buildString {
            append("{ sitekey: ").append(keyLiteral)
            append(", theme: 'dark'")
            append(", callback: onCaptchaSolved")
            append(", 'error-callback': onCaptchaError")
            append(", 'expired-callback': onCaptchaExpired")
            if (rqDataLiteral != null) append(", rqdata: ").append(rqDataLiteral)
            append(" }")
        }
        """
        <!DOCTYPE html>
        <html>
          <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
              html, body { margin:0; padding:0; background:transparent; }
              #box { display:flex; justify-content:center; padding:8px 0; }
            </style>
            <script>
              function onCaptchaSolved(token) { AndroidCaptcha.onSolved(token); }
              function onCaptchaError(err) { AndroidCaptcha.onError(String(err)); }
              function onCaptchaExpired() { AndroidCaptcha.onError('expired'); }
              function onloadCallback() {
                try {
                  hcaptcha.render('box', $renderOptions);
                } catch (e) {
                  AndroidCaptcha.onError(String(e));
                }
              }
            </script>
            <script src="https://js.hcaptcha.com/1/api.js?onload=onloadCallback&render=explicit" async defer></script>
          </head>
          <body><div id="box"></div></body>
        </html>
        """.trimIndent()
    }

    Box(modifier = modifier.fillMaxWidth().height(520.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(Color.Transparent.toArgb())
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        // hCaptcha opens its challenge in a second window.
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(false)
                    }
                    webViewClient = WebViewClient()
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onSolved(token: String) {
                                post { currentOnSolved(token) }
                            }

                            @JavascriptInterface
                            fun onError(message: String) {
                                post { currentOnError(message) }
                            }
                        },
                        "AndroidCaptcha"
                    )
                    loadDataWithBaseURL(
                        "https://discord.com",
                        html,
                        "text/html",
                        "utf-8",
                        null
                    )
                }
            }
        )
    }
}
