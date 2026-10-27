// Runs inside the Shizuku user service process (uid 2000 for adb, 0 for root).
// Deliberately tiny: one method, no state, no callbacks.
package com.jarvis.assistant.shell;

interface IShellService {
    /**
     * Runs [command] through /system/bin/sh and returns "EXIT=<code>\n<output>".
     * [timeoutMs] is enforced inside the service so a stuck command cannot hang the caller.
     */
    String runShell(String command, int timeoutMs);
}
