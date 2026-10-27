package com.jarvis.assistant.connect

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only Drive and Gmail, through the connection the operator authorised.
 *
 * Deliberately minimal — the newest few Drive files and an unread summary — because "connected" has
 * to mean something visible without turning JARVIS into a mail client. Nothing here writes, deletes
 * or sends anything, and no scope beyond `drive.metadata.readonly` and `gmail.readonly` is requested.
 */
object GoogleData {

    data class DriveFile(
        val name: String,
        val kind: String,
        val modified: String,
        val link: String,
    ) {
        val kindLabel: String
            get() = when {
                kind.contains("folder") -> "FOLDER"
                kind.contains("spreadsheet") -> "SHEET"
                kind.contains("document") -> "DOC"
                kind.contains("presentation") -> "SLIDES"
                kind.contains("pdf") -> "PDF"
                kind.contains("image") -> "IMAGE"
                kind.contains("video") -> "VIDEO"
                else -> "FILE"
            }

        val modifiedLabel: String
            get() = modified.take(10).ifBlank { "—" }
    }

    data class GmailSummary(
        val unreadEstimate: Int,
        val recentSubjects: List<String>,
    )

    sealed interface Fetch<out T> {
        data class Ok<T>(val value: T) : Fetch<T>
        data class Error(val message: String, val hint: String? = null) : Fetch<Nothing>
    }

    suspend fun driveRecent(context: Context, limit: Int = 5): Fetch<List<DriveFile>> =
        withContext(Dispatchers.IO) {
            val token = OAuthFlows.googleAccessToken(context)
                ?: return@withContext Fetch.Error("Google is not connected.", "Connect Google in the connections screen.")

            val url = "https://www.googleapis.com/drive/v3/files" +
                "?pageSize=${limit.coerceIn(1, 20)}" +
                "&orderBy=modifiedTime%20desc" +
                "&fields=" + Web.enc("files(id,name,mimeType,modifiedTime,webViewLink)") +
                "&q=" + Web.enc("trashed=false")

            try {
                val json = Web.get(url, token)
                val files = json.optJSONArray("files")
                val out = buildList {
                    if (files != null) {
                        for (i in 0 until files.length()) {
                            val obj = files.optJSONObject(i) ?: continue
                            val name = obj.optString("name")
                            if (name.isBlank()) continue
                            add(
                                DriveFile(
                                    name = name,
                                    kind = obj.optString("mimeType"),
                                    modified = obj.optString("modifiedTime"),
                                    link = obj.optString("webViewLink"),
                                ),
                            )
                        }
                    }
                }
                Fetch.Ok(out)
            } catch (failure: Web.WebFailure) {
                Fetch.Error(failure.message, failure.hint)
            } catch (t: Throwable) {
                Fetch.Error("Could not read Drive.", t.message)
            }
        }

    suspend fun gmail(context: Context): Fetch<GmailSummary> = withContext(Dispatchers.IO) {
        val token = OAuthFlows.googleAccessToken(context)
            ?: return@withContext Fetch.Error("Google is not connected.", "Connect Google in the connections screen.")

        try {
            val listUrl = "https://gmail.googleapis.com/gmail/v1/users/me/messages" +
                "?q=" + Web.enc("is:unread") +
                "&maxResults=3" +
                "&fields=" + Web.enc("messages/id,resultSizeEstimate")
            val list = Web.get(listUrl, token)
            val unread = list.optInt("resultSizeEstimate", 0)
            val messages = list.optJSONArray("messages")

            val subjects = buildList {
                if (messages != null) {
                    for (i in 0 until messages.length()) {
                        val id = messages.optJSONObject(i)?.optString("id").orEmpty()
                        if (id.isBlank()) continue
                        try {
                            val detail = Web.get(
                                "https://gmail.googleapis.com/gmail/v1/users/me/messages/" +
                                    Web.enc(id) + "?format=metadata&metadataHeaders=" + Web.enc("Subject"),
                                token,
                            )
                            val headers = detail.optJSONObject("payload")?.optJSONArray("headers")
                            var subject = ""
                            if (headers != null) {
                                for (j in 0 until headers.length()) {
                                    val header = headers.optJSONObject(j) ?: continue
                                    if (header.optString("name").equals("Subject", ignoreCase = true)) {
                                        subject = header.optString("value")
                                        break
                                    }
                                }
                            }
                            if (subject.isNotBlank()) add(subject.take(70))
                        } catch (t: Throwable) {
                            // One unreadable message must not lose the whole summary.
                        }
                    }
                }
            }
            Fetch.Ok(GmailSummary(unreadEstimate = unread, recentSubjects = subjects))
        } catch (failure: Web.WebFailure) {
            Fetch.Error(failure.message, failure.hint)
        } catch (t: Throwable) {
            Fetch.Error("Could not read Gmail.", t.message)
        }
    }
}
