package com.jarvis.assistant.social

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/** One post with the numbers that make it comparable to the others. */
data class MediaStat(
    val media: IgMedia,
    val engagement: Int,
    val ratePercent: Double,
    val reach: Int?,
)

/** Average performance of one content format. */
data class FormatStat(
    val format: String,
    val posts: Int,
    val averageEngagement: Double,
    val averageRate: Double,
)

/**
 * Everything the HUD and the AI briefing need, computed on device.
 *
 * Pure functions over a snapshot — no Android, no network, no clock beyond what it is handed. That
 * makes the numbers explainable: each finding in [findings] is derived from a field the screen also
 * shows, so nothing here can invent a metric Meta never returned.
 */
data class IgAnalysis(
    val followers: Int,
    val postCount: Int,
    val windowDays: Long,
    val averageLikes: Double,
    val averageComments: Double,
    val averageEngagement: Double,
    val engagementRate: Double,
    val reachCoverage: Double?,
    val bestDay: String?,
    val bestHour: Int?,
    val postsPerWeek: Double?,
    val daysSinceLastPost: Long?,
    val formats: List<FormatStat>,
    val top: List<MediaStat>,
    val weakest: List<MediaStat>,
    val followerDelta: Int?,
    val followerGrowthPercent: Double?,
    val findings: List<String>,
    val briefingInput: String,
) {
    val bestFormat: FormatStat? get() = formats.firstOrNull()
    val worstFormat: FormatStat? get() = formats.lastOrNull()
}

object InstagramAnalytics {

    /** Creator-content benchmarks used only to phrase a finding, never to fake a number. */
    private const val HEALTHY_RATE_LOW = 1.0
    private const val HEALTHY_RATE_HIGH = 5.0

    fun compute(snapshot: IgSnapshot, growth: List<GrowthPoint>): IgAnalysis {
        val followers = snapshot.profile.followers.coerceAtLeast(1)
        val media = snapshot.media
        val now = System.currentTimeMillis()

        val stats = media.map { item ->
            val rate = item.engagement.toDouble() / followers * 100.0
            MediaStat(
                media = item,
                engagement = item.engagement,
                ratePercent = rate,
                reach = item.reach,
            )
        }

        val avgEngagement = if (stats.isEmpty()) 0.0 else stats.map { it.engagement }.average()
        val avgRate = if (stats.isEmpty()) 0.0 else stats.map { it.ratePercent }.average()

        val withReach = stats.filter { (it.reach ?: 0) > 0 }
        val reachCoverage = if (withReach.isEmpty()) {
            null
        } else {
            withReach.map { it.media.engagement.toDouble() / (it.reach ?: 1) * 100.0 }.average()
        }

        val windowDays = media.mapNotNull { it.timestampMs.takeIf { ts -> ts > 0 } }
            .minOrNull()
            ?.let { oldest -> ((now - oldest) / 86_400_000L).coerceAtLeast(1) }
            ?: 0L

        val formats = stats
            .groupBy { it.media.format }
            .map { (format, items) ->
                FormatStat(
                    format = format,
                    posts = items.size,
                    averageEngagement = items.map { it.engagement }.average(),
                    averageRate = items.map { it.ratePercent }.average(),
                )
            }
            .sortedByDescending { it.averageRate }

        // Timing: only formats where the publish time is meaningful, and only buckets with
        // something to compare. A single post at 3am is not a "best time to post".
        val timed = stats.filter { it.media.timestampMs > 0 }
        val zone = ZoneId.systemDefault()
        fun zoned(stat: MediaStat) = Instant.ofEpochMilli(stat.media.timestampMs).atZone(zone)

        val byHour = timed.groupBy { zoned(it).hour }
            .filterValues { it.size >= 2 }
            .entries
            .maxByOrNull { entry -> entry.value.map { it.ratePercent }.average() }
        val byDay = timed.groupBy { zoned(it).dayOfWeek.name }
            .filterValues { it.size >= 2 }
            .entries
            .maxByOrNull { entry -> entry.value.map { it.ratePercent }.average() }

        val perWeek = if (timed.size < 2 || windowDays < 7) {
            null
        } else {
            timed.size.toDouble() / (windowDays / 7.0)
        }

        val daysSinceLast = media.mapNotNull { it.timestampMs.takeIf { ts -> ts > 0 } }
            .maxOrNull()
            ?.let { newest -> ((now - newest) / 86_400_000L).coerceAtLeast(0) }

        val top = stats.sortedByDescending { it.engagement }.take(3)
        val weakest = stats.sortedBy { it.engagement }.take(2)

        val firstFollowers = growth.firstOrNull()?.followers
        val delta = if (firstFollowers != null && growth.size >= 2) {
            snapshot.profile.followers - firstFollowers
        } else {
            null
        }
        val deltaPercent = if (firstFollowers != null && firstFollowers > 0 && delta != null) {
            delta.toDouble() / firstFollowers * 100.0
        } else {
            null
        }

        val findings = buildFindings(
            snapshot = snapshot,
            stats = stats,
            formats = formats,
            avgRate = avgRate,
            reachCoverage = reachCoverage,
            bestDay = byDay?.key,
            bestHour = byHour?.key,
            postsPerWeek = perWeek,
            daysSinceLast = daysSinceLast,
            delta = delta,
        )

        return IgAnalysis(
            followers = snapshot.profile.followers,
            postCount = media.size,
            windowDays = windowDays,
            averageLikes = if (stats.isEmpty()) 0.0 else media.map { it.likes }.average(),
            averageComments = if (stats.isEmpty()) 0.0 else media.map { it.comments }.average(),
            averageEngagement = avgEngagement,
            engagementRate = avgRate,
            reachCoverage = reachCoverage,
            bestDay = byDay?.key?.lowercase()?.replaceFirstChar { it.uppercase() },
            bestHour = byHour?.key,
            postsPerWeek = perWeek,
            daysSinceLastPost = daysSinceLast,
            formats = formats,
            top = top,
            weakest = weakest,
            followerDelta = delta,
            followerGrowthPercent = deltaPercent,
            findings = findings,
            briefingInput = buildBriefingInput(snapshot, stats, formats, avgRate, perWeek, delta),
        )
    }

    // -------------------------------------------------------------------------------- findings

    private fun buildFindings(
        snapshot: IgSnapshot,
        stats: List<MediaStat>,
        formats: List<FormatStat>,
        avgRate: Double,
        reachCoverage: Double?,
        bestDay: String?,
        bestHour: Int?,
        postsPerWeek: Double?,
        daysSinceLast: Long?,
        delta: Int?,
    ): List<String> {
        val out = mutableListOf<String>()
        if (stats.isEmpty()) {
            out.add("No posts came back from the API, so there is nothing to compare yet.")
            return out
        }

        when {
            avgRate >= HEALTHY_RATE_HIGH ->
                out.add(
                    "Engagement is ${pct(avgRate)} — well above the 1–5% band most creator accounts sit in.",
                )

            avgRate >= HEALTHY_RATE_LOW ->
                out.add("Engagement is ${pct(avgRate)} — inside the healthy 1–5% band.")

            else ->
                out.add(
                    "Engagement is ${pct(avgRate)} — under 1%. Reach is outrunning interaction; the first line of a caption does most of that work.",
                )
        }

        if (formats.size >= 2) {
            val best = formats.first()
            val worst = formats.last()
            if (best.format != worst.format && worst.averageRate > 0) {
                val ratio = best.averageRate / worst.averageRate
                out.add(
                    "${pretty(best.format)} averages ${pct(best.averageRate)} versus ${pct(worst.averageRate)} for ${pretty(worst.format)}" +
                        if (ratio >= 1.2) " — ${String.format(Locale.US, "%.1f", ratio)}× the return." else ".",
                )
            }
        }

        if (bestDay != null && bestHour != null) {
            out.add(
                "Posts that landed on ${bestDay.lowercase().replaceFirstChar { it.uppercase() }} around " +
                    "${String.format(Locale.US, "%02d", bestHour)}:00 performed best.",
            )
        }

        if (postsPerWeek != null) {
            out.add(
                when {
                    postsPerWeek >= 5 -> "You are publishing ${oneDecimal(postsPerWeek)}× a week — high volume; watch that quality holds."
                    postsPerWeek >= 2 -> "You are publishing ${oneDecimal(postsPerWeek)}× a week — a sustainable cadence."
                    else -> "You are publishing ${oneDecimal(postsPerWeek)}× a week — under 2 is where accounts usually stall."
                },
            )
        }

        if (daysSinceLast != null) {
            out.add(
                when {
                    daysSinceLast <= 1 -> "The newest post is from today or yesterday."
                    daysSinceLast <= 7 -> "The newest post is $daysSinceLast day(s) old."
                    else -> "Nothing has been posted for $daysSinceLast days; the algorithm cools off after a week of silence."
                },
            )
        }

        if (reachCoverage != null) {
            out.add(
                if (reachCoverage >= 8.0) {
                    "Of the people reached, ${pct(reachCoverage)} interacted — strong content-to-audience fit."
                } else {
                    "Of the people reached, ${pct(reachCoverage)} interacted — the post travels but does not land."
                },
            )
        } else {
            out.add("Per-post reach was not returned, so engagement is measured against followers only.")
        }

        if (delta != null) {
            out.add(
                if (delta >= 0) {
                    "Followers are up $delta since JARVIS started recording."
                } else {
                    "Followers are down ${abs(delta)} since JARVIS started recording."
                },
            )
        } else {
            out.add("Growth needs at least two syncs on different days; this is the first reading.")
        }

        val top = stats.maxByOrNull { it.engagement }
        if (top != null) {
            out.add("Top post: “${top.media.captionPreview}” (${top.engagement} interactions).")
        }

        if (snapshot.notes.isNotEmpty()) {
            out.add("Some metrics were unavailable this sync — see the notes panel.")
        }
        return out
    }

    // --------------------------------------------------------------------------------- briefing

    private fun buildBriefingInput(
        snapshot: IgSnapshot,
        stats: List<MediaStat>,
        formats: List<FormatStat>,
        avgRate: Double,
        postsPerWeek: Double?,
        followerDelta: Int?,
    ): String {
        val formatLines = formats.joinToString("; ") {
            "${it.format}: ${it.posts} post(s), avg ${it.averageEngagement.toInt()} interactions, ${pct(it.averageRate)}"
        }
        val postLines = stats.take(10).joinToString("\n") {
            "- ${it.media.format} · ${it.media.whenLabel} · ${it.media.likes} likes, ${it.media.comments} comments" +
                (it.media.reach?.let { reach -> ", $reach reach" } ?: "") +
                ", rate ${pct(it.ratePercent)} · \"${it.media.captionPreview}\""
        }
        return buildString {
            appendLine("Account: ${snapshot.profile.handle} (${snapshot.profile.accountType})")
            appendLine("Followers: ${snapshot.profile.followers} · posts total: ${snapshot.profile.mediaCount}")
            appendLine("Analysed: ${stats.size} recent post(s), average engagement rate ${pct(avgRate)}")
            if (followerDelta != null) appendLine("Follower change since first sync: $followerDelta")
            if (postsPerWeek != null) appendLine("Cadence: ${oneDecimal(postsPerWeek)} posts/week")
            snapshot.accountReach?.let { appendLine("Account reach (reported window): $it") }
            snapshot.profileViews?.let { appendLine("Profile views (reported window): $it") }
            appendLine("Formats: $formatLines")
            appendLine("Recent posts:")
            appendLine(postLines)
            if (snapshot.notes.isNotEmpty()) {
                appendLine("Unavailable metrics: ${snapshot.notes.joinToString(" · ")}")
            }
        }.trim()
    }

    /** System prompt for the briefing. Explicit about what the model must not invent. */
    const val BRIEFING_SYSTEM_PROMPT: String =
        "You are JARVIS analysing a creator's own Instagram Insights export, handed to you as plain " +
            "text. Answer in short, dense lines — no markdown, no emoji, no headings. Cover, in this " +
            "order: what is working, what is not, one concrete format experiment, one concrete " +
            "posting-time change, and one caption or hook change. Use only numbers present in the " +
            "input; never invent a metric, a competitor, a hashtag trend or an audience demographic. " +
            "If a metric is missing, say it is missing rather than guessing. Keep it under 120 words."

    // ---------------------------------------------------------------------------------- helpers

    private fun pct(value: Double): String = String.format(Locale.US, "%.1f%%", value)

    private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun pretty(format: String): String = when (format) {
        "REELS" -> "Reels"
        "CAROUSEL" -> "Carousels"
        "IMAGE" -> "Single images"
        "VIDEO" -> "Feed videos"
        "STORY" -> "Stories"
        else -> format.lowercase().replaceFirstChar { it.uppercase() }
    }
}
