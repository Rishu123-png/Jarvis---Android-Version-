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
) {
    val views: Int? get() = media.views
}

/** Average performance of one content format. */
data class FormatStat(
    val format: String,
    val posts: Int,
    val averageEngagement: Double,
    val averageRate: Double,
    val averageViews: Double,
    val postsWithViews: Int,
)

/**
 * Everything the HUD and the AI briefing need, computed on device.
 *
 * **Views first.** The operator's goal is reach, not follower count, so the analysis is weighted that
 * way: view totals and per-post views lead, engagement rate is context, and the recommendations are
 * about what to publish next rather than how to farm followers.
 *
 * Pure functions over a snapshot — no Android, no network, no clock beyond what it is handed. Each
 * finding is derived from a field the screen also shows, so nothing here can invent a metric Meta
 * never returned.
 */
data class IgAnalysis(
    val followers: Int,
    val postCount: Int,
    val windowDays: Long,
    val totalViews: Int,
    val averageViews: Double,
    val viewPostCount: Int,
    val viewRate: Double?,
    val averageLikes: Double,
    val averageComments: Double,
    val averageEngagement: Double,
    val engagementRate: Double,
    val reachCoverage: Double?,
    val bestDay: String?,
    val bestHour: Int?,
    val bestViewDay: String?,
    val bestViewHour: Int?,
    val postsPerWeek: Double?,
    val daysSinceLastPost: Long?,
    val formats: List<FormatStat>,
    val top: List<MediaStat>,
    val weakest: List<MediaStat>,
    val followerDelta: Int?,
    val followerGrowthPercent: Double?,
    val findings: List<String>,
    val nextMoves: List<String>,
    val briefingInput: String,
) {
    val bestFormat: FormatStat? get() = formats.firstOrNull()

    val hasViews: Boolean get() = viewPostCount > 0
}

object InstagramAnalytics {

    private const val HEALTHY_RATE_LOW = 1.0
    private const val HEALTHY_RATE_HIGH = 5.0

    fun compute(snapshot: IgSnapshot, growth: List<GrowthPoint>): IgAnalysis {
        val followers = snapshot.profile.followers.coerceAtLeast(1)
        val media = snapshot.media
        val now = System.currentTimeMillis()

        val stats = media.map { item ->
            MediaStat(
                media = item,
                engagement = item.engagement,
                ratePercent = item.engagement.toDouble() / followers * 100.0,
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

        // ---- views: the headline number for a creator chasing reach ----
        val viewStats = stats.filter { (it.views ?: 0) > 0 }
        val totalViews = viewStats.sumOf { it.views ?: 0 }
        val averageViews = if (viewStats.isEmpty()) 0.0 else viewStats.map { (it.views ?: 0).toDouble() }.average()
        val viewsForRate = viewStats.filter { (it.reach ?: 0) > 0 }
        val viewRate = if (viewsForRate.isEmpty()) {
            null
        } else {
            val views = viewsForRate.sumOf { (it.views ?: 0).toDouble() }
            val reach = viewsForRate.sumOf { (it.reach ?: 0).toDouble() }
            if (reach <= 0.0) null else views / reach * 100.0
        }

        val windowDays = media.mapNotNull { it.timestampMs.takeIf { ts -> ts > 0 } }
            .minOrNull()
            ?.let { oldest -> ((now - oldest) / 86_400_000L).coerceAtLeast(1) }
            ?: 0L

        val formats = stats
            .groupBy { it.media.format }
            .map { (format, items) ->
                val withViews = items.filter { (it.views ?: 0) > 0 }
                FormatStat(
                    format = format,
                    posts = items.size,
                    averageEngagement = items.map { it.engagement }.average(),
                    averageRate = items.map { it.ratePercent }.average(),
                    averageViews = if (withViews.isEmpty()) 0.0 else withViews.map { (it.views ?: 0).toDouble() }.average(),
                    postsWithViews = withViews.size,
                )
            }
            .sortedWith(
                compareByDescending<FormatStat> { it.averageViews }
                    .thenByDescending { it.averageRate },
            )

        val timed = stats.filter { it.media.timestampMs > 0 }
        val zone = ZoneId.systemDefault()
        fun zoned(stat: MediaStat) = Instant.ofEpochMilli(stat.media.timestampMs).atZone(zone).let { it }

        val byHour = timed.groupBy { zoned(it).hour }
            .filterValues { it.size >= 2 }
            .entries
            .maxByOrNull { entry -> entry.value.map { it.ratePercent }.average() }
        val byDay = timed.groupBy { zoned(it).dayOfWeek.name }
            .filterValues { it.size >= 2 }
            .entries
            .maxByOrNull { entry -> entry.value.map { it.ratePercent }.average() }

        // Timing measured by views where views exist, because that is the goal being optimised.
        val viewTimed = viewStats.filter { it.media.timestampMs > 0 }
        val viewsByHour = viewTimed.groupBy { zoned(it).hour }
            .filterValues { it.size >= 2 }
            .entries
            .maxByOrNull { entry -> entry.value.map { it.views ?: 0 }.average() }
        val viewsByDay = viewTimed.groupBy { zoned(it).dayOfWeek.name }
            .filterValues { it.size >= 2 }
            .entries
            .maxByOrNull { entry -> entry.value.map { it.views ?: 0 }.average() }

        val perWeek = if (timed.size < 2 || windowDays < 7) {
            null
        } else {
            timed.size.toDouble() / (windowDays / 7.0)
        }

        val daysSinceLast = media.mapNotNull { it.timestampMs.takeIf { ts -> ts > 0 } }
            .maxOrNull()
            ?.let { newest -> ((now - newest) / 86_400_000L).coerceAtLeast(0) }

        val top = stats.sortedByDescending { it.views ?: it.engagement }.take(3)
        val weakest = stats.sortedBy { it.views ?: it.engagement }.take(2)

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
            totalViews = totalViews,
            averageViews = averageViews,
            viewRate = viewRate,
            viewPostCount = viewStats.size,
        )

        val nextMoves = buildNextMoves(
            stats = stats,
            formats = formats,
            viewRate = viewRate,
            daysSinceLast = daysSinceLast,
            bestViewDay = viewsByDay?.key,
            bestViewHour = viewsByHour?.key,
            postsPerWeek = perWeek,
        )

        return IgAnalysis(
            followers = snapshot.profile.followers,
            postCount = media.size,
            windowDays = windowDays,
            totalViews = totalViews,
            averageViews = averageViews,
            viewPostCount = viewStats.size,
            viewRate = viewRate,
            averageLikes = if (stats.isEmpty()) 0.0 else media.map { it.likes }.average(),
            averageComments = if (stats.isEmpty()) 0.0 else media.map { it.comments }.average(),
            averageEngagement = avgEngagement,
            engagementRate = avgRate,
            reachCoverage = reachCoverage,
            bestDay = byDay?.key?.let { prettyDay(it) },
            bestHour = byHour?.key,
            bestViewDay = viewsByDay?.key?.let { prettyDay(it) },
            bestViewHour = viewsByHour?.key,
            postsPerWeek = perWeek,
            daysSinceLastPost = daysSinceLast,
            formats = formats,
            top = top,
            weakest = weakest,
            followerDelta = delta,
            followerGrowthPercent = deltaPercent,
            findings = findings,
            nextMoves = nextMoves,
            briefingInput = buildBriefingInput(snapshot, stats, formats, avgRate, perWeek, delta, totalViews, averageViews, viewRate),
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
        totalViews: Int,
        averageViews: Double,
        viewRate: Double?,
        viewPostCount: Int,
    ): List<String> {
        val out = mutableListOf<String>()
        if (stats.isEmpty()) {
            out.add("No posts came back from the API, so there is nothing to compare yet.")
            return out
        }

        if (viewPostCount > 0) {
            out.add(
                "$totalViews views across $viewPostCount post(s) — ${averageViews.toInt()} per post on average.",
            )
        } else {
            out.add("Views were not returned for these posts; Reels and videos are where Meta reports them.")
        }

        if (viewRate != null) {
            out.add(
                if (viewRate >= 100.0) {
                    "Each view reaches beyond the audience that was shown the post (${pct(viewRate)} of reached accounts)."
                } else {
                    "Of the accounts reached, ${pct(viewRate)} of the possible views were actually watched."
                },
            )
        }

        when {
            avgRate >= HEALTHY_RATE_HIGH ->
                out.add("Interaction rate is ${pct(avgRate)} — well above the 1–5% band most creator accounts sit in.")

            avgRate >= HEALTHY_RATE_LOW ->
                out.add("Interaction rate is ${pct(avgRate)} — inside the healthy 1–5% band.")

            else ->
                out.add("Interaction rate is ${pct(avgRate)} — under 1%. Views can still be high while this is low; it means people watched and moved on.")
        }

        if (formats.size >= 2) {
            val best = formats.first()
            val worst = formats.last()
            if (best.format != worst.format) {
                val comparison = if (best.averageViews > 0 && worst.averageViews > 0) {
                    "averages ${best.averageViews.toInt()} views versus ${worst.averageViews.toInt()}"
                } else {
                    "averages ${pct(best.averageRate)} interaction versus ${pct(worst.averageRate)}"
                }
                val ratio = if (worst.averageViews > 0 && best.averageViews > 0) {
                    best.averageViews / worst.averageViews
                } else {
                    if (worst.averageRate > 0) best.averageRate / worst.averageRate else 0.0
                }
                out.add(
                    "${pretty(best.format)} $comparison for ${pretty(worst.format)}" +
                        if (ratio >= 1.2) " — ${String.format(Locale.US, "%.1f", ratio)}× the return." else ".",
                )
            }
        }

        if (bestDay != null && bestHour != null) {
            out.add(
                "Posts that landed on ${bestDay.lowercase().replaceFirstChar { it.uppercase() }} around " +
                    "${String.format(Locale.US, "%02d", bestHour)}:00 drew the most interaction.",
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
                    else -> "Nothing has been posted for $daysSinceLast days; reach cools off after a week of silence."
                },
            )
        }

        if (reachCoverage != null && viewPostCount == 0) {
            out.add(
                if (reachCoverage >= 8.0) {
                    "Of the people reached, ${pct(reachCoverage)} interacted — strong content-to-audience fit."
                } else {
                    "Of the people reached, ${pct(reachCoverage)} interacted — the post travels but does not land."
                },
            )
        }

        if (delta != null) {
            out.add(
                if (delta >= 0) {
                    "Followers are up $delta since JARVIS started recording (context, not the goal)."
                } else {
                    "Followers are down ${abs(delta)} since JARVIS started recording (context, not the goal)."
                },
            )
        }

        val top = stats.maxByOrNull { it.views ?: it.engagement }
        if (top != null) {
            val number = top.views?.let { "$it views" } ?: "${top.engagement} interactions"
            out.add("Best performer: “${top.media.captionPreview}” — $number.")
        }

        if (snapshot.notes.isNotEmpty()) {
            out.add("Some metrics were unavailable this sync — see the notes panel.")
        }
        return out
    }

    // ------------------------------------------------------------------------------- next moves

    /** Concrete, checkable suggestions for the *next* post, derived only from this account's data. */
    private fun buildNextMoves(
        stats: List<MediaStat>,
        formats: List<FormatStat>,
        viewRate: Double?,
        daysSinceLast: Long?,
        bestViewDay: String?,
        bestViewHour: Int?,
        postsPerWeek: Double?,
    ): List<String> {
        if (stats.isEmpty()) return listOf("Sync again once there are posts to compare.")

        val out = mutableListOf<String>()
        val best = formats.firstOrNull()
        val reels = formats.firstOrNull { it.format == "REELS" }
        val still = formats.firstOrNull { it.format == "IMAGE" || it.format == "CAROUSEL" }

        when {
            best != null && best.format == "REELS" && best.averageViews > 0 ->
                out.add("Next post: a Reel. Reels average ${best.averageViews.toInt()} views here, and they are what the view count is built from.")

            reels == null && still != null ->
                out.add("You have no Reels in this window. One Reel is the cheapest test of whether video reaches further for this account.")

            best != null && best.averageViews > 0 ->
                out.add("Next post: another ${pretty(best.format).lowercase()} — it is your strongest format at ${best.averageViews.toInt()} average views.")

            else -> out.add("Publish one Reel and one carousel this week, then compare average views — that answers the format question with your own numbers.")
        }

        if (bestViewDay != null && bestViewHour != null) {
            out.add(
                "Publish at ${String.format(Locale.US, "%02d", bestViewHour)}:00 on ${prettyDay(bestViewDay).lowercase()} — your view-heavy posts cluster there.",
            )
        } else if (bestViewDay != null) {
            out.add("${prettyDay(bestViewDay)} carries your best view numbers so far.")
        } else {
            out.add("Not enough timed posts to name an hour yet; keep the next three at different times so JARVIS can compare.")
        }

        if (viewRate != null && viewRate < 40.0) {
            out.add("Only ${pct(viewRate)} of reached accounts watched. Open on the payoff in the first second — no logo, no intro line.")
        }

        if (daysSinceLast != null && daysSinceLast >= 7) {
            out.add("It has been $daysSinceLast days. Post within 48 hours; reach recovers faster from a short gap than a long one.")
        }

        if (postsPerWeek != null && postsPerWeek >= 6) {
            out.add("At ${oneDecimal(postsPerWeek)} posts a week you are trading depth for volume. Cut one post and put the effort into the hook.")
        }

        return out.take(4)
    }

    // --------------------------------------------------------------------------------- briefing

    private fun buildBriefingInput(
        snapshot: IgSnapshot,
        stats: List<MediaStat>,
        formats: List<FormatStat>,
        avgRate: Double,
        postsPerWeek: Double?,
        followerDelta: Int?,
        totalViews: Int,
        averageViews: Double,
        viewRate: Double?,
    ): String {
        val formatLines = formats.joinToString("; ") {
            "${it.format}: ${it.posts} post(s), avg ${it.averageEngagement.toInt()} interactions, " +
                "avg ${it.averageViews.toInt()} views (${it.postsWithViews} with views), ${pct(it.averageRate)}"
        }
        val postLines = stats.take(10).joinToString("\n") {
            "- ${it.media.format} · ${it.media.whenLabel} · ${it.media.views ?: 0} views, " +
                "${it.media.likes} likes, ${it.media.comments} comments, ${it.media.reach ?: 0} reach, " +
                "rate ${pct(it.ratePercent)} · \"${it.media.captionPreview}\""
        }
        return buildString {
            appendLine("Goal: maximise VIEWS, not followers.")
            appendLine("Account: ${snapshot.profile.handle} (${snapshot.profile.accountType})")
            appendLine("Followers: ${snapshot.profile.followers} · posts total: ${snapshot.profile.mediaCount}")
            appendLine("Views: $totalViews total, ${averageViews.toInt()} average per post with views" +
                (viewRate?.let { ", ${pct(it)} of reach" } ?: ""))
            appendLine("Analysed: ${stats.size} recent post(s), average interaction rate ${pct(avgRate)}")
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

    /** System prompt for the briefing. Explicit about the goal and about what must not be invented. */
    const val BRIEFING_SYSTEM_PROMPT: String =
        "You are JARVIS analysing a creator's own Instagram Insights, handed to you as plain text. " +
            "The goal is VIEWS, not followers — never advise chasing follower count. Answer in short, " +
            "dense lines, no markdown, no emoji, no headings. Cover, in order: what is driving views, " +
            "what is wasting effort, the exact next post to make (format, topic angle, hook line), the " +
            "best time to publish based on the data given, and one change to the first second of the " +
            "video. Use only numbers present in the input; never invent a metric, a competitor, a " +
            "hashtag trend or an audience demographic. If a metric is missing, say it is missing " +
            "rather than guessing. Under 130 words."

    // ---------------------------------------------------------------------------------- helpers

    private fun pct(value: Double): String = String.format(Locale.US, "%.1f%%", value)

    private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun prettyDay(raw: String): String =
        raw.lowercase().replaceFirstChar { it.uppercase() }

    private fun pretty(format: String): String = when (format) {
        "REELS" -> "Reels"
        "CAROUSEL" -> "Carousels"
        "IMAGE" -> "Single images"
        "VIDEO" -> "Feed videos"
        "STORY" -> "Stories"
        else -> format.lowercase().replaceFirstChar { it.uppercase() }
    }
}
