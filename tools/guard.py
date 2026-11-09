#!/usr/bin/env python3
"""Repository guard — the checks a compiler cannot make.

Three things in this repository have gone wrong before, all silently:

  1. the numbers the README and ROADMAP claim drifted away from the enums they describe
     (the docs once said "19 action types" while the code had 63, and "42 suites" while there were 46);
  2. a pinned download (Shizuku) and the app's own idea of that pin could disagree, so the phone would
     fetch a file the build never verified;
  3. a component could be exported that only ever needed to hear from the system.

None of those break `assembleDebug`. All three are cheap to check, so they are checked here, in CI,
before the tests run.

    python3 tools/guard.py

Exits 0 when everything agrees, 1 with one readable line per disagreement.
No dependencies beyond the standard library, and no network.
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/jarvis/assistant"
TEST = ROOT / "app/src/test/java/com/jarvis/assistant"
GRADLE = ROOT / "app/build.gradle.kts"
README = ROOT / "README.md"
ROADMAP = ROOT / "ROADMAP.md"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"

ANDROID = "{http://schemas.android.com/apk/res/android}"

# Broadcasts sent by the platform. A receiver that listens to nothing else never needs to be
# exported, because the system is exempt from the exported check.
SYSTEM_BROADCASTS = {
    "android.intent.action.BOOT_COMPLETED",
    "android.intent.action.LOCKED_BOOT_COMPLETED",
    "android.intent.action.QUICKBOOT_POWERON",
    "android.intent.action.MY_PACKAGE_REPLACED",
    "android.intent.action.TIME_SET",
    "android.intent.action.TIMEZONE_CHANGED",
    "android.intent.action.SCREEN_ON",
    "android.intent.action.SCREEN_OFF",
    "android.intent.action.ACTION_POWER_CONNECTED",
    "android.intent.action.ACTION_POWER_DISCONNECTED",
    "android.net.conn.CONNECTIVITY_CHANGE",
}

# Cleartext is a deliberate exception for exactly two addresses: the loopback the core is reached on
# over `adb reverse`, and the emulator's alias for its host. Enforced at runtime by
# res/xml/network_security_config.xml; enforced here so a new literal cannot slip in unnoticed.
CLEARTEXT_ALLOWED_HOSTS = {"127.0.0.1", "localhost", "10.0.2.2"}

# The five ActionType entries that steer a routine instead of doing something to the phone.
CONTROL_FLOW = {"WAIT", "WAIT_UNTIL", "IF_BLOCK", "REPEAT_BLOCK", "STOP_ROUTINE"}

problems: list[str] = []


def fail(message: str) -> None:
    problems.append(message)


# -------------------------------------------------------------------------------------------------
# What the code actually says
# -------------------------------------------------------------------------------------------------


def enum_entries(relative_path: str, enum_name: str) -> list[str]:
    """The entry names of a Kotlin enum, read without a Kotlin parser."""
    source = (MAIN / relative_path).read_text(encoding="utf-8")
    match = re.search(r"enum class " + enum_name + r"\(", source, re.S)
    if not match:
        fail(f"{relative_path}: enum {enum_name} not found")
        return []
    body = source[match.start():]
    body = body[: body.index("\n}\n") + 3]
    return re.findall(r"^\s{4}([A-Z][A-Z0-9_]*)\(", body, re.M)


def gradle_value(pattern: str, label: str) -> str:
    source = GRADLE.read_text(encoding="utf-8")
    match = re.search(pattern, source)
    if not match:
        fail(f"app/build.gradle.kts: could not read {label}")
        return ""
    return match.group(1)


def facts() -> dict[str, int]:
    actions = enum_entries("automation/AutomationModels.kt", "ActionType")
    conditions = enum_entries("automation/AutomationModels.kt", "ConditionType")
    triggers = enum_entries("automation/AutomationModels.kt", "TriggerKind")
    stepping = [name for name in actions if name in CONTROL_FLOW]
    suites = sorted(TEST.glob("*.kt"))
    tests = sum(path.read_text(encoding="utf-8").count("@Test") for path in suites)
    return {
        "action_types": len(actions),
        "actions": len(actions) - len(stepping),
        "control_flow": len(stepping),
        "conditions": len(conditions),
        "triggers": len(triggers),
        "suites": len(suites),
        "tests": tests,
        "version_code": int(gradle_value(r"versionCode\s*=\s*(\d+)", "versionCode") or 0),
    }


# -------------------------------------------------------------------------------------------------
# 1. The numbers in the docs
# -------------------------------------------------------------------------------------------------

# Each pattern is anchored to the phrase that makes the number a claim, not just a number in prose:
# "the screen agent may take 20 actions" is about a budget, not about how many ActionType entries
# exist, and a guard that cannot tell those apart is a guard nobody keeps.
README_CLAIMS = [
    (r"tests-(\d+)%20suites", ("suites",), "badge"),
    (r"(\d+) JVM suites(?: and (\d+) tests)?", ("suites", "tests"), "test counts"),
    (r"(\d+) test suites", ("suites",), "verified-by-CI line"),
    (r"(\d+) action types", ("action_types",), "routines table"),
    (r"the engine: (\d+) actions, (\d+) conditions, (\d+) triggers",
     ("actions", "conditions", "triggers"), "architecture"),
]

# The ROADMAP also states the build number it was last reviewed against, which is the field most
# likely to be forgotten.
ROADMAP_CLAIMS = [
    (r"Automation: (\d+) action types \((\d+) actions \+ (\d+) control-flow\), "
     r"(\d+) conditions, (\d+) triggers",
     ("action_types", "actions", "control_flow", "conditions", "triggers"), "automation row"),
    (r"Tests: (\d+) JVM suites(?: and (\d+) tests)?", ("suites", "tests"), "tests row"),
    (r"versionCode (\d+)\)", ("version_code",), "last-updated line"),
]


def check_doc(path: Path, claims, truth: dict[str, int], name: str) -> None:
    lines = path.read_text(encoding="utf-8").splitlines()
    for number, line in enumerate(lines, start=1):
        for regex, keys, label in claims:
            for match in re.finditer(regex, line):
                for group, key in zip(match.groups(), keys):
                    if group is None:
                        continue
                    claimed = int(group)
                    if claimed != truth[key]:
                        fail(
                            f"{name}:{number} ({label}) says {claimed} "
                            f"{key.replace('_', ' ')} but the code has {truth[key]}"
                        )


# -------------------------------------------------------------------------------------------------
# 2. Pins: the build and the phone must agree on what they are downloading
# -------------------------------------------------------------------------------------------------


def check_pins() -> None:
    gradle = GRADLE.read_text(encoding="utf-8")
    installer = (MAIN / "device/ShizukuInstaller.kt").read_text(encoding="utf-8")

    version = re.search(r'val shizukuVersion\s*=\s*"([^"]+)"', gradle)
    size = re.search(r"val shizukuApkBytes\s*=\s*([\d_]+)L", gradle)
    if not version or not size:
        fail("app/build.gradle.kts: the Shizuku pin (version and byte size) could not be read")
        return
    pinned_version = version.group(1)
    pinned_size = int(size.group(1).replace("_", ""))

    for label, pattern in (
        ("VERSION", r'const val VERSION = "([^"]+)"'),
        ("DOWNLOAD_URL version", r"download/v([^/]+)/"),
    ):
        match = re.search(pattern, installer)
        if not match:
            fail(f"ShizukuInstaller.kt: could not read {label}")
        elif match.group(1) != pinned_version:
            fail(
                f"ShizukuInstaller.kt {label} is {match.group(1)} "
                f"but app/build.gradle.kts pins {pinned_version}"
            )

    size_match = re.search(r"const val APK_BYTES = ([\d_]+)L", installer)
    if not size_match:
        fail("ShizukuInstaller.kt: could not read APK_BYTES")
    elif int(size_match.group(1).replace("_", "")) != pinned_size:
        fail(
            f"ShizukuInstaller.kt APK_BYTES is {size_match.group(1)} "
            f"but app/build.gradle.kts pins {pinned_size}"
        )

    # The download URL the phone falls back to must name the same build the APK was fetched from.
    url_match = re.search(r"const val DOWNLOAD_URL =\s*((?:\s*\"[^\"]*\"\s*\+?)+)", installer)
    if not url_match:
        fail("ShizukuInstaller.kt: could not read DOWNLOAD_URL")
    else:
        url = "".join(re.findall(r'"([^"]*)"', url_match.group(1)))
        if pinned_version not in url:
            fail(f"ShizukuInstaller.kt DOWNLOAD_URL does not name {pinned_version}: {url}")


# -------------------------------------------------------------------------------------------------
# 3. Cleartext, and components that never needed to be exported
# -------------------------------------------------------------------------------------------------

CLEARTEXT = re.compile(r"http://([A-Za-z0-9.\-]+)")


def is_comment(line: str) -> bool:
    """A comment may name an address to explain why it is refused; only code is judged."""
    stripped = line.strip()
    return stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*")


def check_cleartext() -> None:
    for path in sorted(MAIN.rglob("*.kt")):
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if is_comment(line):
                continue
            code = line.split("//")[0]
            for match in CLEARTEXT.finditer(code):
                host = match.group(1).rstrip(".")
                if host in CLEARTEXT_ALLOWED_HOSTS:
                    continue
                fail(
                    f"{path.relative_to(ROOT)}:{number} opens a plaintext address "
                    f"(http://{host}); only loopback may be clear"
                )


def check_exported_receivers() -> None:
    tree = ET.parse(MANIFEST)
    for receiver in tree.getroot().iter("receiver"):
        exported = (receiver.get(f"{ANDROID}exported") or "").lower()
        if exported != "true":
            continue
        actions = {
            action.get(f"{ANDROID}name")
            for action in receiver.iter("action")
        }
        actions.discard(None)
        if actions and actions <= SYSTEM_BROADCASTS:
            name = receiver.get(f"{ANDROID}name", "?")
            fail(
                f"AndroidManifest.xml: {name} is exported but listens only to system broadcasts "
                f"({', '.join(sorted(actions))}) — exported=\"false\" still receives them"
            )


# -------------------------------------------------------------------------------------------------


def main() -> int:
    truth = facts()
    if truth["suites"] == 0:
        fail("no test suites found; the guard is looking in the wrong place")

    check_doc(README, README_CLAIMS, truth, "README.md")
    check_doc(ROADMAP, ROADMAP_CLAIMS, truth, "ROADMAP.md")
    check_pins()
    check_cleartext()
    check_exported_receivers()

    if problems:
        print(f"guard: {len(problems)} problem(s)\n")
        for problem in problems:
            print(f"  FAIL  {problem}")
        print(
            "\nThese are the checks that keep claims, pins and the manifest in step with the code."
            "\nFix the file (or, when the code genuinely changed, the claim) and run this again."
        )
        return 1

    print(
        "guard: ok — "
        f"{truth['action_types']} action types "
        f"({truth['actions']} actions + {truth['control_flow']} control-flow), "
        f"{truth['conditions']} conditions, {truth['triggers']} triggers, "
        f"{truth['suites']} suites / {truth['tests']} tests, build {truth['version_code']}. "
        "Docs, pins, cleartext and exported receivers all agree."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
