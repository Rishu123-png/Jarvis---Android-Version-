package com.jarvis.assistant.social

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Which Meta login path the token came from.
 *
 * These are genuinely different APIs with different hosts, not two names for one thing, so the
 * operator picks one and JARVIS never guesses.
 */
enum class IgPlatform(
    val id: String,
    val label: String,
    val host: String,
    val note: String,
) {
    INSTAGRAM_LOGIN(
        id = "instagram_login",
        label = "INSTAGRAM LOGIN",
        host = "https://graph.instagram.com",
        note = "No Facebook account and no Facebook Page needed — you log in with Instagram. Token lasts 60 days and can be extended here.",
    ),
    FACEBOOK_LOGIN(
        id = "facebook_login",
        label = "FACEBOOK LOGIN",
        host = "https://graph.facebook.com/v23.0",
        note = "Needs a Facebook account and a Page linked to the account. Skip this if you only use Instagram.",
    ),
    ;

    companion object {
        fun fromId(id: String?): IgPlatform = entries.firstOrNull { it.id == id } ?: INSTAGRAM_LOGIN
    }
}

/** The account itself. Counts are only available for professional (business/creator) accounts. */
data class IgProfile(
    val id: String = "",
    val username: String = "",
    val name: String = "",
    val accountType: String = "",
    val followers: Int = 0,
    val follows: Int = 0,
    val mediaCount: Int = 0,
    val biography: String = "",
    val pictureUrl: String = "",
) {
    val handle: String get() = if (username.isBlank()) "unknown" else "@$username"
}

/** One post, plus whatever per-post insights the account was allowed to return. */
data class IgMedia(
    val id: String,
    val format: String,
    val caption: String,
    val timestampMs: Long,
    val likes: Int,
    val comments: Int,
    val permalink: String,
    val thumbnail: String,
    val reach: Int? = null,
    val saved: Int? = null,
    val shares: Int? = null,
    val views: Int? = null,
    val interactions: Int? = null,
) {
    /** Public engagement, always available. Insights are added when they exist. */
    val engagement: Int
        get() = likes + comments + (saved ?: 0) + (shares ?: 0)

    val captionPreview: String
        get() = caption.replace('\n', ' ').trim().take(70).ifBlank { "(no caption)" }

    val whenLabel: String
        get() = try {
            Instant.ofEpochMilli(timestampMs)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("d MMM · HH:mm"))
        } catch (t: Throwable) {
            "—"
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("format", format)
        put("caption", caption.take(400))
        put("at", timestampMs)
        put("likes", likes)
        put("comments", comments)
        put("permalink", permalink)
        put("thumb", thumbnail)
        reach?.let { put("reach", it) }
        saved?.let { put("saved", it) }
        shares?.let { put("shares", it) }
        views?.let { put("views", it) }
        interactions?.let { put("interactions", it) }
    }

    companion object {
        fun fromJson(obj: JSONObject?): IgMedia? {
            val source = obj ?: return null
            val id = source.optString("id")
            if (id.isBlank()) return null
            return IgMedia(
                id = id,
                format = source.optString("format", "POST"),
                caption = source.optString("caption", ""),
                timestampMs = source.optLong("at", 0L),
                likes = source.optInt("likes", 0),
                comments = source.optInt("comments", 0),
                permalink = source.optString("permalink", ""),
                thumbnail = source.optString("thumb", ""),
                reach = source.optIntOrNull("reach"),
                saved = source.optIntOrNull("saved"),
                shares = source.optIntOrNull("shares"),
                views = source.optIntOrNull("views"),
                interactions = source.optIntOrNull("interactions"),
            )
        }
    }
}

/** A follower reading kept over time, so growth is measured rather than guessed. */
data class GrowthPoint(val at: Long, val followers: Int, val mediaCount: Int)

/**
 * Everything one successful sync produced.
 *
 * [notes] is not decoration: every metric Meta refused is listed there, so a missing number is
 * explained instead of silently rendering as zero.
 */
data class IgSnapshot(
    val platform: IgPlatform,
    val profile: IgProfile,
    val media: List<IgMedia>,
    val accountReach: Int? = null,
    val profileViews: Int? = null,
    val notes: List<String> = emptyList(),
    val fetchedAt: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("platform", platform.id)
        put("fetchedAt", fetchedAt)
        accountReach?.let { put("accountReach", it) }
        profileViews?.let { put("profileViews", it) }
        put("notes", JSONArray(notes))
        put(
            "profile",
            JSONObject().apply {
                put("id", profile.id)
                put("username", profile.username)
                put("name", profile.name)
                put("type", profile.accountType)
                put("followers", profile.followers)
                put("follows", profile.follows)
                put("media", profile.mediaCount)
                put("bio", profile.biography)
                put("picture", profile.pictureUrl)
            },
        )
        put("media", JSONArray().apply { media.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(raw: String?): IgSnapshot? {
            if (raw.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(raw)
                val profileObj = obj.optJSONObject("profile") ?: return null
                val profile = IgProfile(
                    id = profileObj.optString("id"),
                    username = profileObj.optString("username"),
                    name = profileObj.optString("name"),
                    accountType = profileObj.optString("type"),
                    followers = profileObj.optInt("followers"),
                    follows = profileObj.optInt("follows"),
                    mediaCount = profileObj.optInt("media"),
                    biography = profileObj.optString("bio"),
                    pictureUrl = profileObj.optString("picture"),
                )
                if (profile.followers == 0 && profile.username.isBlank()) return null
                val mediaArr = obj.optJSONArray("media") ?: JSONArray()
                val media = buildList {
                    for (i in 0 until mediaArr.length()) {
                        IgMedia.fromJson(mediaArr.optJSONObject(i))?.let { add(it) }
                    }
                }
                val notesArr = obj.optJSONArray("notes") ?: JSONArray()
                val notes = buildList {
                    for (i in 0 until notesArr.length()) {
                        notesArr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
                IgSnapshot(
                    platform = IgPlatform.fromId(obj.optString("platform")),
                    profile = profile,
                    media = media,
                    accountReach = obj.optIntOrNull("accountReach"),
                    profileViews = obj.optIntOrNull("profileViews"),
                    notes = notes,
                    fetchedAt = obj.optLong("fetchedAt"),
                )
            } catch (t: Throwable) {
                null
            }
        }
    }
}

/** JSONObject.optInt returns 0 for absent keys, which is wrong for "the API refused this metric". */
internal fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key, -1).takeIf { it >= 0 } else null
