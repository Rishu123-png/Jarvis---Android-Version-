package com.jarvis.assistant.social

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.time.Instant

/**
 * Reads the operator's own Instagram professional account through Meta's official API.
 *
 * What this deliberately is:
 *  * **official** — `graph.instagram.com` (Business Login for Instagram) or `graph.facebook.com`
 *    (Facebook Login for Business), with the operator's own token;
 *  * **read-only** — profile, media list and insights;
 *  * **tolerant** — Meta refuses individual metrics on individual post types, so every insights call
 *    is its own step. A refusal becomes a [IgSnapshot.notes] line, never a failed sync and never a
 *    fabricated number;
 *  * **bounded** — a token costs about 16 requests per sync, insights are capped to the newest few
 *    posts, and the whole enrichment pass has a wall-clock budget so a slow network cannot keep a
 *    spinner (or a foreground service) alive.
 *
 * What it deliberately is not: scraping, DM reading, or access to anyone else's account. None of
 * that exists in the API, and pretending otherwise would be the dishonest kind of feature.
 */
object InstagramClient {

    sealed interface Result {
        data class Ok(val snapshot: IgSnapshot) : Result
        data class Error(val message: String, val hint: String? = null) : Result
    }

    sealed interface TokenResult {
        data class Ok(val days: Long) : TokenResult
        data class Error(val message: String, val hint: String? = null) : TokenResult
    }

    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MEDIA_LIMIT = 25
    private const val INSIGHT_MEDIA_LIMIT = 12
    private const val ENRICH_BUDGET_MS = 45_000L
    private const val MAX_BODY_CHARS = 4_000

    private const val PROFILE_FIELDS =
        "id,username,name,account_type,followers_count,follows_count,media_count,biography,profile_picture_url"

    private const val MEDIA_FIELDS =
        "id,caption,media_type,media_product_type,permalink,thumbnail_url,media_url,timestamp,like_count,comments_count"

    private const val MEDIA_METRICS = "reach,saved,shares,total_interactions"

    private class IgFailure(val message: String, val hint: String? = null) : Exception(message)

    // ---------------------------------------------------------------------------------- public

    suspend fun fetch(context: Context): Result = withContext(Dispatchers.IO) {
        val token = InstagramStore.token(context)
            ?: return@withContext Result.Error(
                "No Instagram account is connected.",
                "Paste an access token to link your account.",
            )
        val platform = InstagramStore.platform(context)
        val base = platform.host

        val profileJson = try {
            get("$base/me?fields=$PROFILE_FIELDS&access_token=${encode(token)}")
        } catch (failure: IgFailure) {
            return@withContext Result.Error(failure.message, failure.hint)
        } catch (timeout: SocketTimeoutException) {
            return@withContext Result.Error(
                "Meta did not answer in time.",
                "Try again on a steadier connection.",
            )
        } catch (unknownHost: UnknownHostException) {
            return@withContext Result.Error("No network reachable.", "Check your connection.")
        } catch (other: IOException) {
            return@withContext Result.Error("Could not reach Meta.", other.message)
        } catch (t: Throwable) {
            return@withContext Result.Error("Unexpected reply from Meta.", t.message)
        }

        val profile = parseProfile(profileJson)
        if (profile.id.isBlank()) {
            return@withContext Result.Error(
                "Meta returned no account id.",
                "The token may belong to a personal account. Switch the account to Creator or Business.",
            )
        }

        val notes = mutableListOf<String>()

        // ---- media list (public fields only) ----
        val rawMedia = try {
            val json = get("$base/me/media?fields=$MEDIA_FIELDS&limit=$MEDIA_LIMIT&access_token=${encode(token)}")
            parseMedia(json)
        } catch (failure: IgFailure) {
            notes.add("Posts unavailable: ${failure.message}")
            emptyList()
        } catch (t: Throwable) {
            notes.add("Posts unavailable: ${t.javaClass.simpleName}")
            emptyList()
        }

        // ---- per-post insights, newest first, with a wall-clock budget ----
        val deadline = System.currentTimeMillis() + ENRICH_BUDGET_MS
        var insightsBlocked = false
        var enriched = 0
        val media = ArrayList<IgMedia>(rawMedia.size)

        for ((index, item) in rawMedia.withIndex()) {
            if (insightsBlocked || index >= INSIGHT_MEDIA_LIMIT) {
                media.add(item)
                continue
            }
            if (System.currentTimeMillis() > deadline) {
                notes.add("Post insights stopped at the time budget; ${rawMedia.size - index} post(s) kept public counts only.")
                media.add(item)
                insightsBlocked = true
                continue
            }
            if (item.format == "STORY") {
                // Stories expire and expose a different metric set; not worth a request per story.
                media.add(item)
                continue
            }

            val insight = try {
                fetchMediaInsight(base, item.id, token)
            } catch (failure: IgFailure) {
                if (failure.message.contains("permission", ignoreCase = true) ||
                    (failure.hint ?: "").contains("permission", ignoreCase = true)
                ) {
                    notes.add("Post insights need the manage-insights permission; public counts only.")
                    insightsBlocked = true
                }
                null
            } catch (t: Throwable) {
                null
            }

            if (insight == null) {
                media.add(item)
            } else {
                enriched++
                media.add(
                    item.copy(
                        reach = insight["reach"],
                        saved = insight["saved"],
                        shares = insight["shares"],
                        interactions = insight["total_interactions"],
                    ),
                )
            }
        }

        if (rawMedia.isNotEmpty() && enriched == 0) {
            notes.add("No post insights returned. Likes and comments are still live.")
        }

        // ---- account-level insights ----
        var accountReach: Int? = null
        var profileViews: Int? = null
        try {
            val json = get(
                "$base/me/insights?metric=reach,profile_views&period=day&access_token=${encode(token)}",
            )
            val data = json.optJSONArray("data")
            var days = 0
            if (data != null) {
                for (i in 0 until data.length()) {
                    val entry = data.optJSONObject(i) ?: continue
                    val name = entry.optString("name")
                    val series = entry.optJSONArray("values")
                    val values = ArrayList<Int>()
                    if (series != null) {
                        for (j in 0 until series.length()) {
                            val point = series.optJSONObject(j) ?: continue
                            val value = point.opt("value")
                            if (value is Number) values.add(value.toInt())
                            days = maxOf(days, series.length())
                        }
                    }
                    val total = if (values.isEmpty()) null else values.sum()
                    when (name) {
                        "reach" -> accountReach = total
                        "profile_views" -> profileViews = total
                    }
                }
            }
            if (days > 0) notes.add("Account reach and profile views are sums over $days reported day(s).")
        } catch (failure: IgFailure) {
            notes.add("Account insights unavailable: ${failure.message}")
        } catch (t: Throwable) {
            notes.add("Account insights unavailable: ${t.javaClass.simpleName}")
        }

        val snapshot = IgSnapshot(
            platform = platform,
            profile = profile,
            media = media.sortedByDescending { it.timestampMs },
            accountReach = accountReach,
            profileViews = profileViews,
            notes = notes,
            fetchedAt = System.currentTimeMillis(),
        )
        InstagramStore.saveSnapshot(context, snapshot)
        Result.Ok(snapshot)
    }

    /** Extends an Instagram-Login long-lived token back to 60 days. */
    suspend fun refreshToken(context: Context): TokenResult = withContext(Dispatchers.IO) {
        val token = InstagramStore.token(context)
            ?: return@withContext TokenResult.Error("No account connected.")
        if (InstagramStore.platform(context) != IgPlatform.INSTAGRAM_LOGIN) {
            return@withContext TokenResult.Error(
                "Refresh only applies to Instagram Login tokens.",
                "A Facebook Login token is refreshed by regenerating it in your Meta app.",
            )
        }
        try {
            val json = get(
                "${IgPlatform.INSTAGRAM_LOGIN.host}/refresh_access_token" +
                    "?grant_type=ig_refresh_token&access_token=${encode(token)}",
            )
            val fresh = json.optString("access_token")
            if (fresh.isBlank()) {
                return@withContext TokenResult.Error("Meta returned no token.")
            }
            val saveError = InstagramStore.save(
                context,
                fresh,
                IgPlatform.INSTAGRAM_LOGIN,
            )
            if (saveError != null) return@withContext TokenResult.Error(saveError)
            val days = (json.optLong("expires_in", 0L) / 86_400L).coerceAtLeast(1L)
            TokenResult.Ok(days)
        } catch (failure: IgFailure) {
            TokenResult.Error(failure.message, failure.hint)
        } catch (t: Throwable) {
            TokenResult.Error("Could not refresh the token.", t.message)
        }
    }

    // --------------------------------------------------------------------------------- parsing

    private fun parseProfile(json: JSONObject): IgProfile = IgProfile(
        id = json.optString("id"),
        username = json.optString("username"),
        name = json.optString("name"),
        accountType = json.optString("account_type").ifBlank { "PROFESSIONAL" },
        followers = json.optInt("followers_count"),
        follows = json.optInt("follows_count"),
        mediaCount = json.optInt("media_count"),
        biography = json.optString("biography"),
        pictureUrl = json.optString("profile_picture_url"),
    )

    private fun parseMedia(json: JSONObject): List<IgMedia> {
        val data = json.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isBlank()) continue
                add(
                    IgMedia(
                        id = id,
                        format = formatOf(obj),
                        caption = obj.optString("caption", ""),
                        timestampMs = parseTimestamp(obj.optString("timestamp")),
                        likes = obj.optInt("like_count"),
                        comments = obj.optInt("comments_count"),
                        permalink = obj.optString("permalink"),
                        thumbnail = obj.optString("thumbnail_url").ifBlank {
                            obj.optString("media_url")
                        },
                    ),
                )
            }
        }
    }

    private fun formatOf(obj: JSONObject): String {
        val product = obj.optString("media_product_type").uppercase()
        if (product == "REELS") return "REELS"
        if (product == "STORY") return "STORY"
        return when (obj.optString("media_type").uppercase()) {
            "CAROUSEL_ALBUM", "CAROUSEL" -> "CAROUSEL"
            "VIDEO" -> "VIDEO"
            "IMAGE" -> "IMAGE"
            else -> "POST"
        }
    }

    private fun parseTimestamp(raw: String): Long = try {
        Instant.parse(raw).toEpochMilli()
    } catch (t: Throwable) {
        0L
    }

    /**
     * Per-post metrics. The full metric set is tried first; if Meta refuses one of them (it does,
     * per post type, with no useful detail) the call is retried with just `reach`, which is the one
     * metric that exists everywhere.
     */
    private fun fetchMediaInsight(base: String, mediaId: String, token: String): Map<String, Int> {
        val full = try {
            get("$base/$mediaId/insights?metric=$MEDIA_METRICS&access_token=${encode(token)}")
        } catch (failure: IgFailure) {
            if ((failure.hint ?: "").contains("permission", ignoreCase = true)) throw failure
            get("$base/$mediaId/insights?metric=reach&access_token=${encode(token)}")
        }
        val out = HashMap<String, Int>()
        val data = full.optJSONArray("data") ?: return out
        for (i in 0 until data.length()) {
            val entry = data.optJSONObject(i) ?: continue
            val name = entry.optString("name")
            val series = entry.optJSONArray("values") ?: continue
            var total = 0
            for (j in 0 until series.length()) {
                val point = series.optJSONObject(j) ?: continue
                val value = point.opt("value")
                if (value is Number) total += value.toInt()
            }
            if (name.isNotBlank()) out[name] = total
        }
        return out
    }

    // ------------------------------------------------------------------------------------ http

    private fun get(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "JARVIS-Android")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = try {
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            } catch (t: Throwable) {
                ""
            }.take(MAX_BODY_CHARS)

            if (code !in 200..299) throw explain(code, body)

            return try {
                JSONObject(body)
            } catch (t: Throwable) {
                throw IgFailure("Meta returned something that is not JSON.", "HTTP $code")
            }
        } finally {
            try {
                connection.disconnect()
            } catch (t: Throwable) {
                // Nothing to release.
            }
        }
    }

    private fun explain(code: Int, body: String): IgFailure {
        val error = try {
            JSONObject(body).optJSONObject("error")
        } catch (t: Throwable) {
            null
        } ?: return IgFailure("Meta refused the request (HTTP $code).", null)

        val message = error.optString("message").ifBlank { "Meta refused the request (HTTP $code)." }
        val errorCode = error.optInt("code")
        val subCode = error.optInt("error_subcode")
        val hint = when {
            errorCode == 190 || subCode == 463 || subCode == 467 ->
                "The token is expired or revoked. Generate a fresh one and connect again."

            errorCode == 4 || errorCode == 17 || errorCode == 32 || errorCode == 613 ->
                "Rate limit reached. Wait a few minutes, then refresh."

            errorCode == 10 || errorCode == 200 ->
                "The token is missing a permission. Insights need instagram_business_manage_insights."

            errorCode == 100 ->
                "Meta rejected a parameter. The account must be Creator or Business, not personal."

            else -> null
        }
        return IgFailure(message, hint)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Exposed for the UI: how many posts JARVIS will spend insight requests on. */
    val insightPostLimit: Int get() = INSIGHT_MEDIA_LIMIT
}
