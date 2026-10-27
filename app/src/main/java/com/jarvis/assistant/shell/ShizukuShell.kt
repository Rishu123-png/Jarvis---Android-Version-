package com.jarvis.assistant.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.jarvis.assistant.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Optional adb-level shell access through Shizuku.
 *
 * **Stability contract.** This is the most privileged layer in the app, so it is also the most
 * defensive:
 *
 *  * nothing here runs at launch — the Shizuku binder is only touched when a screen or a routine
 *    actually asks for the status;
 *  * every call is wrapped, because Shizuku throws `IllegalStateException` when its binder is gone
 *    and `SecurityException` when permission was revoked mid-call;
 *  * binding and executing both have timeouts, so a dead Shizuku server returns an error instead of
 *    wedging the caller;
 *  * when anything is missing the rest of the app behaves exactly as before — [Outcome.Failed] with
 *    a plain-language reason, never a crash and never a silent lie.
 *
 * No root is required: Shizuku's standard setup is a one-time `adb shell sh /sdcard/…/start.sh`
 * (or wireless debugging on Android 11+). Sui, the Magisk module, works too, but is not needed.
 */
object ShizukuShell {

    /** How far the privileged tier has got. */
    enum class Status(val label: String) {
        NOT_INSTALLED("SHIZUKU NOT INSTALLED"),
        NOT_RUNNING("SERVICE STOPPED"),
        PERMISSION_NEEDED("PERMISSION NEEDED"),
        READY("CONNECTED"),
        ;
    }

    data class State(
        val status: Status = Status.NOT_INSTALLED,
        val privilege: String = "",
        val detail: String = "",
    ) {
        val isReady: Boolean get() = status == Status.READY
    }

    sealed interface Outcome {
        data class Ok(val exitCode: Int, val output: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    const val REQUEST_CODE = 4_211
    private const val BIND_TIMEOUT_MS = 6_000L

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bindLock = Mutex()

    @Volatile private var service: IShellService? = null
    @Volatile private var listenersRegistered = false
    @Volatile private var args: Shizuku.UserServiceArgs? = null

    /** Last permission result reported by the Shizuku app, for the UI to show. */
    @Volatile var lastPermissionDenied: Boolean = false
        private set

    val isReady: Boolean get() = _state.value.isReady

    // --------------------------------------------------------------------------------- status

    /**
     * Recomputes [state]. Safe to call from anywhere, including the main thread: the binder probes
     * run on the IO dispatcher. Never throws.
     */
    suspend fun refresh(context: Context): Unit = withContext(Dispatchers.IO) {
        val installed = isManagerInstalled(context)
        val next = try {
            val alive = Shizuku.pingBinder() && !Shizuku.isPreV11()
            if (!alive) {
                State(
                    status = if (installed) Status.NOT_RUNNING else Status.NOT_INSTALLED,
                    detail = if (installed) {
                        "Shizuku is installed but its service is not running."
                    } else {
                        "Install Shizuku and start it once over adb or wireless debugging."
                    },
                )
            } else {
                val granted = try {
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                } catch (t: Throwable) {
                    false
                }
                if (granted) {
                    val uid = try {
                        Shizuku.getUid()
                    } catch (t: Throwable) {
                        -1
                    }
                    State(
                        status = Status.READY,
                        privilege = when (uid) {
                            0 -> "ROOT"
                            2000 -> "ADB SHELL"
                            else -> "UID $uid"
                        },
                        detail = "Commands run as ${if (uid == 0) "root" else "the shell user"}.",
                    )
                } else {
                    State(
                        status = Status.PERMISSION_NEEDED,
                        detail = "Shizuku is running. JARVIS needs its one-time authorisation.",
                    )
                }
            }
        } catch (t: Throwable) {
            State(Status.NOT_RUNNING, detail = "Shizuku is not reachable right now.")
        }

        _state.value = next
        if (!next.isReady) dropService()
    }

    /** Registers the binder listeners once. Cheap, and harmless if Shizuku is absent. */
    fun attach(context: Context) {
        if (listenersRegistered) return
        listenersRegistered = true
        val app = context.applicationContext
        try {
            Shizuku.addBinderReceivedListenerSticky {
                scope.launch { refresh(app) }
            }
            Shizuku.addBinderDeadListener {
                dropService()
                _state.value = State(Status.NOT_RUNNING, detail = "Shizuku stopped.")
            }
            Shizuku.addRequestPermissionResultListener { _, grantResult ->
                lastPermissionDenied = grantResult != PackageManager.PERMISSION_GRANTED
                scope.launch { refresh(app) }
            }
        } catch (t: Throwable) {
            // Listener registration must never be able to take the app down.
        }
    }

    /**
     * Asks Shizuku for permission. Must be called from the main thread, and only in response to an
     * explicit operator action — the caller is responsible for both.
     */
    fun requestPermission(context: Context) {
        attach(context)
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(REQUEST_CODE)
                }
            }
        } catch (t: Throwable) {
            lastPermissionDenied = true
        }
    }

    fun isManagerInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (t: Throwable) {
        false
    }

    /** Opens the Shizuku app so the operator can start it, without JARVIS guessing an intent. */
    fun launchIntent(context: Context): android.content.Intent? = try {
        context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
    } catch (t: Throwable) {
        null
    }

    // ---------------------------------------------------------------------------------- exec

    /**
     * Runs one shell command with the privileged identity.
     *
     * Returns [Outcome.Failed] for every "cannot" case: no Shizuku, no permission, service would not
     * bind, or the command timed out. The caller turns that into a plain sentence for the operator.
     */
    suspend fun exec(
        context: Context,
        command: String,
        timeoutMs: Int = 15_000,
    ): Outcome = withContext(Dispatchers.IO) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return@withContext Outcome.Failed("No command given")

        refresh(context)
        val current = _state.value
        if (!current.isReady) {
            return@withContext Outcome.Failed(
                when (current.status) {
                    Status.NOT_INSTALLED -> "Shizuku is not installed"
                    Status.NOT_RUNNING -> "Shizuku is not running"
                    Status.PERMISSION_NEEDED -> "Shizuku permission not granted"
                    Status.READY -> "Shizuku is not ready"
                },
            )
        }

        val shell = ensureService(context)
            ?: return@withContext Outcome.Failed("Shizuku service did not start")

        val reply = try {
            withTimeoutOrNull((timeoutMs + 8_000).toLong()) {
                shell.runShell(trimmed, timeoutMs)
            }
        } catch (t: Throwable) {
            null
        }

        if (reply == null) {
            dropService()
            return@withContext Outcome.Failed("Shizuku did not answer in time")
        }

        val firstLine = reply.lineSequence().firstOrNull().orEmpty()
        val exit = firstLine.removePrefix("EXIT=").trim().toIntOrNull() ?: -1
        val body = reply.lineSequence().drop(1).joinToString("\n").trim()
        Outcome.Ok(exit, body)
    }

    /** One-line summary for command output. */
    fun describe(state: State = _state.value): String = when (state.status) {
        Status.READY -> "READY · ${state.privilege}"
        Status.PERMISSION_NEEDED -> "PERMISSION NEEDED"
        Status.NOT_RUNNING -> "SERVICE STOPPED"
        Status.NOT_INSTALLED -> "NOT INSTALLED"
    }

    // ------------------------------------------------------------------------------- bindings

    private suspend fun ensureService(context: Context): IShellService? {
        service?.let { return it }
        // One bind at a time: a second caller waits for the first instead of starting a duplicate
        // user service, which Shizuku would otherwise refuse.
        return bindLock.withLock {
            service?.let { return@withLock it }
            try {
                val client = Connection()
                val bound = try {
                    Shizuku.bindUserService(userServiceArgs(context), client)
                    true
                } catch (t: Throwable) {
                    false
                }
                if (!bound) return@withLock null

                val binder = withTimeoutOrNull(BIND_TIMEOUT_MS) { client.binder.await() }
                if (binder == null) {
                    runCatching { Shizuku.unbindUserService(userServiceArgs(context), client, true) }
                    null
                } else {
                    IShellService.Stub.asInterface(binder).also { service = it }
                }
            } catch (t: Throwable) {
                null
            }
        }
    }

    private fun userServiceArgs(context: Context): Shizuku.UserServiceArgs =
        args ?: Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShellUserService::class.java.name),
        )
            .processNameSuffix("shell")
            .daemon(false)
            .debuggable(false)
            .version(BuildConfig.VERSION_CODE)
            .also { args = it }

    private fun dropService() {
        service = null
    }

    private class Connection : ServiceConnection {
        val binder = CompletableDeferred<IBinder?>()

        override fun onServiceConnected(name: ComponentName?, remote: IBinder?) {
            if (!binder.isCompleted) binder.complete(remote)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (!binder.isCompleted) binder.complete(null)
        }
    }

    private const val SHIZUKU_PACKAGE = "moe.shizuku.manager"
}
