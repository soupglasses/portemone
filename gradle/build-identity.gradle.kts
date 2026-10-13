// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

// Provider-backed Git reads are configuration-cache inputs, including changes to HEAD and the worktree.
fun gitOutput(vararg arguments: String): String {
    val output = providers.exec {
        workingDir(rootDir)
        commandLine("git", *arguments)
        isIgnoreExitValue = true
    }
    check(output.result.get().exitValue == 0) {
        "Cannot determine the build's Git revision in $rootDir: " +
                output.standardError.asText.get().trim() +
                "\nBuild from a readable Git checkout. Git failures must not produce an unidentified APK."
    }
    return output.standardOutput.asText.get().trim()
}

// Do not mistake an enclosing repository for this app's source checkout.
val checkout =
    if (rootDir.resolve(".git").exists()) gitOutput("rev-parse", "--show-toplevel") else null
val ownsCheckout = checkout != null && file(checkout).canonicalFile == rootDir.canonicalFile
check(checkout == null || ownsCheckout) {
    "Git resolved $rootDir to a different checkout ($checkout). Open this app's Git checkout in Android Studio."
}
val commit = if (ownsCheckout) gitOutput(
    "log",
    "--no-show-signature",
    "-1",
    "--format=%H%n%cs"
)?.lines() else null
val hash = commit?.getOrNull(0)?.takeIf { it.matches(Regex("[0-9a-f]{40,64}")) }
val date = commit?.getOrNull(1)?.takeIf { it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) }
check(!ownsCheckout || (hash != null && date != null)) {
    "Git returned an invalid commit identity for $rootDir: $commit"
}
val modified = ownsCheckout && gitOutput("status", "--porcelain", "--untracked-files=normal") != ""
val stableTag = Regex("""v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)""")
val reachableTags = if (ownsCheckout) gitOutput("tag", "--merged", "HEAD", "--list", "v*")
    .lines().filter { stableTag.matches(it) } else emptyList()

fun versionCode(tag: String): Int {
    val parts = checkNotNull(stableTag.matchEntire(tag)).groupValues.drop(1).map { it.toLong() }
    val (major, minor, patch) = parts
    check(major <= 210000 && minor <= 99 && patch <= 99) {
        "Version $tag exceeds supported major/minor/patch bounds (210000/99/99)."
    }
    val code = major * 10000 + minor * 100 + patch
    check(code in 1..2100000000L) { "Version $tag must produce an Android version code in 1..2100000000." }
    return code.toInt()
}

val exactTags = if (ownsCheckout) gitOutput("tag", "--points-at", "HEAD").lines()
    .filter { it.startsWith("v") } else emptyList()
check(exactTags.size <= 1 && exactTags.all { stableTag.matches(it) }) {
    "A release commit must have at most one version tag, in the form vMAJOR.MINOR.PATCH."
}
val tag = exactTags.singleOrNull() ?: reachableTags.maxByOrNull { versionCode(it) }
val taggedRelease = exactTags.size == 1 && !modified
val version = tag?.removePrefix("v")?.let { if (taggedRelease) it else "$it-dev" } ?: "0.0.0-dev"
extra["releaseVersionName"] = version
extra["releaseVersionCode"] = tag?.let { versionCode(it) } ?: 1
val revision = if (hash != null && date != null) {
    "${hash.take(12)}${if (modified) " · modified" else ""} ($date)"
} else {
    "Development build · Git revision unavailable"
}

extra["developmentBuildIdentity"] = revision
extra["releaseBuildIdentity"] =
    if (taggedRelease && hash != null && date != null) "$version · $revision" else revision
