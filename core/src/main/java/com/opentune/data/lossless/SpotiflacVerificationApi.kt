package com.opentune.data.lossless

import android.net.Uri
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** User-operated provider challenge, with an attempt-bound callback and a pinned issuer. */
object SpotiflacVerificationApi {
    private const val CALLBACK = "spotiflac://session-grant"

    suspend fun bootstrap(base: String, installId: String): JSONObject {
        val url = "$base/bootstrap".toHttpUrl().newBuilder()
            .addQueryParameter("install_id", installId)
            .addQueryParameter("app_version", SpotiflacGated.APP_VERSION)
            .addQueryParameter("platform", "android").build()
        return JSONObject(LosslessHttp.text(url.toString(), timeoutMs = 12_000))
    }

    fun challengeUrl(data: JSONObject, base: String, state: String): String? {
        val issuer = base.toHttpUrlOrNull() ?: return null
        val page = httpsUrl(data.optString("challenge_url"))?.toHttpUrl() ?: return null
        if (page.host != issuer.host || page.port != issuer.port || page.encodedPath != issuer.encodedPath.trimEnd('/') + "/challenge") return null
        if (!Regex("[a-f0-9]{32}").matches(state)) return null
        return page.newBuilder().setQueryParameter("cb", "$CALLBACK?state=$state").build().toString()
    }

    fun isChallengePage(actual: String?, expected: String): Boolean {
        val url = actual?.toHttpUrlOrNull() ?: return false
        val pinned = expected.toHttpUrlOrNull() ?: return false
        return url.newBuilder().fragment(null).build() == pinned
    }

    fun isCallback(uri: Uri): Boolean = uri.scheme == "spotiflac" && uri.host == "session-grant" &&
        uri.port == -1 && uri.userInfo == null && uri.path.isNullOrEmpty() && uri.fragment == null

    fun grantFromCallback(uri: Uri, state: String): String? {
        if (!isCallback(uri) || uri.getQueryParameters("state") != listOf(state)) return null
        return uri.getQueryParameters("grant").singleOrNull()?.takeIf {
            it.isNotBlank() && it.length <= 8_192 && it.none(Char::isISOControl)
        }
    }

    suspend fun exchange(base: String, installId: String, grant: String): String {
        val body = JSONObject().put("grant", grant).put("install_id", installId)
            .put("app_version", SpotiflacGated.APP_VERSION).put("platform", "android")
        val request = Request.Builder().url("$base/session/exchange")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json").build()
        return LosslessHttp.bytes(request, 1_048_576, timeoutMs = 12_000).toString(Charsets.UTF_8)
    }
}

