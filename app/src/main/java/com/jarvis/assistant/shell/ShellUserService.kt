package com.jarvis.assistant.shell

import java.util.concurrent.TimeUnit

/**
 * The process that actually touches the system.
 *
 * Shizuku starts this class in a separate process running as **shell** (uid 2000, the same identity
 * `adb shell` gets) or, if the operator's backend is root, as uid 0. Nothing is escalated beyond
 * what that identity already allows, and no `su` is ever invoked.
 *
 * Rules kept here so the app side stays simple:
 *  * every command is run through `/system/bin/sh -c`, never `su`;
 *  * output is read on a second thread so a full pipe buffer cannot deadlock the wait;
 *  * a hard timeout kills the process, so a wedged command always returns to the caller;
 *  * the reply is truncated, because binder transactions have a size limit.
 */
class ShellUserService : IShellService.Stub() {

    override fun runShell(command: String?, timeoutMs: Int): String {
        val raw = command?.trim().orEmpty()
        if (raw.isEmpty()) return "EXIT=2\nNo command given."

        val budget = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS).toLong()

        val proc: Process = try {
            ProcessBuilder("/system/bin/sh", "-c", raw)
                .redirectErrorStream(true)
                .start()
        } catch (t: Throwable) {
            return "EXIT=1\n${t.javaClass.simpleName}: ${t.message.orEmpty()}".trim()
        }

        return try {
            val captured = StringBuilder()
            val pump = Thread {
                try {
                    proc.inputStream.bufferedReader().use { reader ->
                        var line = StringBuilder()
                        while (true) {
                            val ch = reader.read()
                            if (ch == -1) break
                            if (ch == '\n'.code) {
                                appendLine(captured, line.toString())
                                line = StringBuilder()
                                if (captured.length >= MAX_OUTPUT_CHARS) break
                            } else if (line.length < MAX_OUTPUT_CHARS) {
                                line.append(ch.toChar())
                            }
                        }
                        if (line.isNotEmpty()) appendLine(captured, line.toString())
                    }
                } catch (t: Throwable) {
                    // Stream closed by the timeout path; the exit code already says what happened.
                }
            }
            pump.isDaemon = true
            pump.start()

            val finished = proc.waitFor(budget, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                "EXIT=124\nTimed out after ${budget / 1000}s."
            } else {
                pump.join(JOIN_MS)
                val code = proc.exitValue()
                val body = synchronized(captured) { captured.toString().trimEnd() }
                if (body.isEmpty()) "EXIT=$code" else "EXIT=$code\n$body"
            }
        } catch (t: Throwable) {
            "EXIT=1\n${t.javaClass.simpleName}: ${t.message.orEmpty()}".trim()
        } finally {
            try {
                proc.destroy()
            } catch (t: Throwable) {
                // Already gone.
            }
        }
    }

    private fun appendLine(into: StringBuilder, line: String) {
        val room = MAX_OUTPUT_CHARS - into.length
        if (room <= 0) return
        into.append(line.take(room)).append('\n')
    }

    private companion object {
        const val MIN_TIMEOUT_MS = 1_000
        const val MAX_TIMEOUT_MS = 60_000
        const val JOIN_MS = 750L
        const val MAX_OUTPUT_CHARS = 8_000
    }
}
