package me.rerere.rikkahub.data.workspace

import java.security.MessageDigest
import java.util.Locale

/**
 * Pure model of a public apt plan: the validated request, the argv that has to be simulated, the
 * parsed result of that simulation and the fingerprint over both.
 *
 * The file itself never executes anything. It builds both arrays — the `apt-get -s` argv used to
 * simulate a request and, through [WorkspaceAptRequest.executionCommand], the fixed real argv a
 * confirmed plan may run — but running either stays behind whatever runner the caller owns, and a
 * plan that reaches that runner must have been simulated, parsed and confirmed first.
 *
 * Two different trust domains are kept apart:
 *
 * - A **request** is model input. Its package arguments are validated against the Debian ASCII
 *   name grammar, sorted and de-duplicated, so a request can never smuggle an option (`-y`), a
 *   shell metacharacter (`;`), a whitespace split or an architecture selection (`pkg:amd64`) into
 *   the argv.
 * - A **simulation report** is apt's own output. It is untrusted as well, but it is not the same
 *   shape of input: names are read from apt's `Inst`/`Remv`/`Purg`/`Conf` verbs and may carry an
 *   `:arch` qualifier there, and every count comes from those verbs only. Locale-dependent
 *   summary lines ("0 upgraded, 0 newly installed, ...") are ignored on purpose.
 *
 * The exact option position accepted by `apt-get` for `--only-upgrade` is not re-verified here;
 * this file only assembles the array.
 */

/** The apt actions a public plan may request. `wireName` is persisted verbatim, so it is stable. */
enum class WorkspaceAptAction(val wireName: String) {
    INSTALL("install"),
    REMOVE("remove"),
    UPGRADE("upgrade"),
    ;

    companion object {
        /** Returns the action stored under [wireName], or `null` when the stored value is unknown. */
        fun fromWireName(wireName: String): WorkspaceAptAction? =
            entries.firstOrNull { it.wireName == wireName }
    }
}

/** Maximum number of distinct packages one plan may request. */
const val MAX_WORKSPACE_APT_PACKAGES = 32

/** Maximum amount of redacted simulation text handed to the UI. */
const val MAX_WORKSPACE_APT_SIMULATION_TEXT_CHARS = 4096

private const val MAX_WORKSPACE_APT_PACKAGE_CHARS = 255
private const val MAX_WORKSPACE_APT_OUTPUT_NAME_CHARS = 255
private const val MAX_WORKSPACE_APT_VERSION_CHARS = 128
private const val MAX_WORKSPACE_APT_FINGERPRINT_CHARS = 256
private const val TRUNCATION_MARKER = "\u2026[truncated]"
private const val UNKNOWN_VERSION = "unknown"

/** The guest path of the only program the dedicated apt channel may run. */
internal const val APT_GET_GUEST_PATH = "/usr/bin/apt-get"

/**
 * Every fixed shape an execution argv may have, without its package list. An argv that does not
 * start with one of these is rejected before a package can ever be changed.
 */
private val APT_EXECUTION_PREFIXES = listOf(
    listOf(APT_GET_GUEST_PATH, "-y", "install"),
    listOf(APT_GET_GUEST_PATH, "-y", "remove"),
    listOf(APT_GET_GUEST_PATH, "-y", "purge"),
    listOf(APT_GET_GUEST_PATH, "-y", "--only-upgrade", "install"),
)

/** Debian package argument grammar. Anything else never reaches the argv. */
private val APT_PACKAGE_PATTERN = Regex("[a-z0-9][a-z0-9+.-]*")

/**
 * Name grammar for names read back from apt output. It additionally accepts the `:arch` qualifier
 * apt prints on a multi-architecture system; the request grammar above never does.
 */
private val APT_OUTPUT_NAME_PATTERN = Regex("[a-z0-9][a-z0-9+.-]*(?::[a-z0-9][a-z0-9-]*)?")

/** Version strings are printable ASCII without spaces; control characters are rejected. */
private val APT_VERSION_PATTERN = Regex("[!-~]{1,$MAX_WORKSPACE_APT_VERSION_CHARS}")

/** Fingerprints are opaque but must stay single-line and printable to canonicalize unambiguously. */
private val APT_FINGERPRINT_PATTERN = Regex("[\\x21-\\x7E]{1,$MAX_WORKSPACE_APT_FINGERPRINT_CHARS}")

private val APT_INSTALL_LINE = Regex("""^Inst\s+(\S+)\s+(?:\[([^\]]*)]\s+)?\(([^)]*)\)""")
private val APT_REMOVE_LINE = Regex("""^Remv\s+(\S+)\s*(?:\[([^\]]*)]\s*)?(?:\(([^)]*)\))?""")
private val APT_PURGE_LINE = Regex("""^Purg\s+(\S+)\s*(?:\[([^\]]*)]\s*)?(?:\(([^)]*)\))?""")
private val APT_CONFIGURE_LINE = Regex("""^Conf\s+(\S+)\s*(?:\(([^)]*)\))?""")
private val APT_VERB_PATTERN = Regex("^(Inst|Remv|Purg|Conf)(?:\\s|$)")

private val ANSI_ESCAPE_PATTERN = Regex(
    "\u001B\\[[0-?]*[ -/]*[@-~]" +
        "|\u001B\\][^\u0007]*(?:\u0007|\u001B\\\\)" +
        "|\u001B[@-Z\\\\_]",
)
private val CONTROL_CHARACTER_PATTERN = Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]")
private val URL_CREDENTIAL_PATTERN = Regex("([A-Za-z][A-Za-z0-9+.\\-]*://)[^/@\\s]+@")
private val SECRET_QUERY_PARAMETER_PATTERN = Regex(
    "(?i)((?:access[_-]?token|api[_-]?key|auth|key|passwd|password|pwd|secret|token)=)[^&\\s\"']+",
)

/**
 * A validated apt request.
 *
 * The constructor re-checks the invariants of [packages] (every argument is a valid Debian package
 * name, the list is sorted, de-duplicated, non-empty and within [MAX_WORKSPACE_APT_PACKAGES]), so
 * a `copy()` cannot produce a request that [of] would have rejected. Use the factories below to
 * normalize model input.
 */
data class WorkspaceAptRequest(
    val action: WorkspaceAptAction,
    val packages: List<String>,
    /** Only [WorkspaceAptAction.REMOVE] may purge; apt has no purge mode for install/upgrade. */
    val purge: Boolean = false,
) {
    init {
        require(packages.isNotEmpty()) { "apt plan must request at least one package" }
        require(packages.size <= MAX_WORKSPACE_APT_PACKAGES) {
            "apt plan must not request more than $MAX_WORKSPACE_APT_PACKAGES packages"
        }
        packages.forEach(::requireValidPackageArgument)
        require(packages.distinct().size == packages.size) { "apt plan packages must be unique" }
        require(packages == packages.sorted()) { "apt plan packages must be sorted" }
        require(!purge || action == WorkspaceAptAction.REMOVE) {
            "only a remove plan may purge"
        }
    }

    /**
     * The argv to simulate. Always simulated (`-s`): this file has no variant that would change
     * the environment, and no caller may strip the option before running the array.
     *
     * `upgrade` requires an explicit package list, so there is no `upgrade` command here; apt is
     * asked to upgrade exactly the named packages and nothing else.
     */
    fun command(): List<String> = when (action) {
        WorkspaceAptAction.INSTALL -> listOf("apt-get", "-s", "install") + packages
        WorkspaceAptAction.REMOVE ->
            listOf("apt-get", "-s", if (purge) "purge" else "remove") + packages
        WorkspaceAptAction.UPGRADE ->
            listOf("apt-get", "-s", "--only-upgrade", "install") + packages
    }

    /**
     * The real argv a confirmed plan may run. This is the only non-simulated form this file
     * exposes, and every element of it comes from the already validated [packages] and [action]:
     * the verb is fixed, the package list is the validated one and `-y` is the only option that is
     * ever added, so no option (`--allow-unauthenticated`, `--force-yes`, `--auto-remove`, ...) and
     * no shell text can be smuggled in.
     *
     * The shape is exactly `/usr/bin/apt-get -y <install|remove|purge|--only-upgrade install>
     * <packages>`. The guest path is required by the dedicated apt channel, and this array is only
     * reachable through a plan that was simulated, parsed and confirmed first; [requireExecutionArgv]
     * re-checks the shape at the runner boundary.
     */
    fun executionCommand(): List<String> = when (action) {
        WorkspaceAptAction.INSTALL -> listOf(APT_GET_GUEST_PATH, "-y", "install") + packages
        WorkspaceAptAction.REMOVE ->
            listOf(APT_GET_GUEST_PATH, "-y", if (purge) "purge" else "remove") + packages
        WorkspaceAptAction.UPGRADE ->
            listOf(APT_GET_GUEST_PATH, "-y", "--only-upgrade", "install") + packages
    }

    companion object {
        fun install(packages: List<String>): WorkspaceAptRequest =
            of(WorkspaceAptAction.INSTALL, packages)

        fun remove(packages: List<String>, purge: Boolean = false): WorkspaceAptRequest =
            of(WorkspaceAptAction.REMOVE, packages, purge)

        /** Upgrading is always explicit: there is no "upgrade everything" request. */
        fun upgrade(packages: List<String>): WorkspaceAptRequest =
            of(WorkspaceAptAction.UPGRADE, packages)

        /** Validates and normalizes [packages] (sort, de-duplicate) and builds the request. */
        fun of(
            action: WorkspaceAptAction,
            packages: List<String>,
            purge: Boolean = false,
        ): WorkspaceAptRequest = WorkspaceAptRequest(
            action = action,
            packages = normalizePackages(packages),
            purge = purge,
        )

        /** Rebuilds a request from the persisted `action` value; unknown actions are rejected. */
        fun fromWireName(
            action: String,
            packages: List<String>,
            purge: Boolean = false,
        ): WorkspaceAptRequest = of(
            action = WorkspaceAptAction.fromWireName(action)
                ?: throw IllegalArgumentException("Unknown apt plan action"),
            packages = packages,
            purge = purge,
        )

        private fun normalizePackages(packages: List<String>): List<String> {
            require(packages.isNotEmpty()) { "apt plan must request at least one package" }
            packages.forEach(::requireValidPackageArgument)
            val normalized = packages.distinct().sorted()
            require(normalized.size <= MAX_WORKSPACE_APT_PACKAGES) {
                "apt plan must not request more than $MAX_WORKSPACE_APT_PACKAGES packages"
            }
            return normalized
        }
    }
}

/**
 * Raw outcome of a simulated apt run, as reported by whatever runner executed the argv. The three
 * failure flags are checked before any output is parsed.
 */
data class WorkspaceAptSimulationResult(
    val exitCode: Int,
    val timedOut: Boolean = false,
    /** The runner had to cut the output off, so the plan cannot be completed from it. */
    val truncated: Boolean = false,
    val stdout: String = "",
    val stderr: String = "",
)

/**
 * One package change read from apt's simulation output.
 *
 * [fromVersion] is the replaced/removed version (`[old]`), [toVersion] the version apt would put
 * in place (`(...)`); either is `null` when apt printed no version for it.
 */
data class WorkspaceAptPackageChange(
    val name: String,
    val fromVersion: String? = null,
    val toVersion: String? = null,
)

/**
 * Parsed simulation of one [WorkspaceAptRequest].
 *
 * [installs] are packages apt would newly install (`Inst` without a replaced version),
 * [upgrades] are `Inst` lines that replace another version, [removals]/[purges] are `Remv`/`Purg`
 * lines and [configured] the packages apt would configure.
 */
data class WorkspaceAptSimulation(
    val request: WorkspaceAptRequest,
    val installs: List<WorkspaceAptPackageChange> = emptyList(),
    val upgrades: List<WorkspaceAptPackageChange> = emptyList(),
    val removals: List<WorkspaceAptPackageChange> = emptyList(),
    val purges: List<WorkspaceAptPackageChange> = emptyList(),
    val configured: List<String> = emptyList(),
) {
    /** True when the simulation would take packages away, whatever the requested action was. */
    val removesPackages: Boolean
        get() = removals.isNotEmpty() || purges.isNotEmpty()

    /** True when the simulation would not change anything at all. */
    val isEmpty: Boolean
        get() = installs.isEmpty() &&
            upgrades.isEmpty() &&
            removals.isEmpty() &&
            purges.isEmpty()
}

/**
 * Parses the simulation output of this request and rejects everything that cannot back a
 * confirmable plan.
 *
 * A non-zero exit code, a timeout or truncated output means the simulation did not finish, so
 * nothing is parsed. Afterwards:
 *
 * - a simulation without any change is rejected: there would be nothing to confirm,
 * - an [WorkspaceAptAction.INSTALL] that would remove or purge packages is rejected: apt was not
 *   asked to take anything away, so the plan does not describe what the user is confirming.
 *
 * An upgrade may legitimately remove an obsolete package; that is not rejected here but stays
 * visible through [WorkspaceAptSimulation.removals] so the caller can ask for it explicitly.
 *
 * Only `Inst`/`Remv`/`Purg`/`Conf` verbs are counted. Summary lines are locale-dependent text and
 * are never trusted; a verb line that cannot be parsed rejects the whole simulation instead of
 * being skipped silently.
 */
fun WorkspaceAptRequest.parseSimulation(
    result: WorkspaceAptSimulationResult,
): WorkspaceAptSimulation {
    require(!result.timedOut) { "apt simulation timed out" }
    require(!result.truncated) { "apt simulation output was truncated" }
    require(result.exitCode == 0) { "apt simulation failed with exit code ${result.exitCode}" }

    val installs = mutableListOf<WorkspaceAptPackageChange>()
    val upgrades = mutableListOf<WorkspaceAptPackageChange>()
    val removals = mutableListOf<WorkspaceAptPackageChange>()
    val purges = mutableListOf<WorkspaceAptPackageChange>()
    val configured = mutableListOf<String>()

    result.stdout.split('\n').forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty()) return@forEach
        val verb = APT_VERB_PATTERN.find(line)?.groupValues?.get(1) ?: return@forEach
        when (verb) {
            "Inst" -> {
                val match = APT_INSTALL_LINE.find(line)
                    ?: throw IllegalArgumentException("Unparseable apt simulation Inst line")
                val name = requireOutputName(match.groupValues[1])
                val fromVersion = versionOrNull(match.groupValues[2])
                val toVersion = versionOrNull(match.groupValues[3].trim().substringBefore(' '))
                val change = WorkspaceAptPackageChange(name, fromVersion, toVersion)
                if (fromVersion == null) installs += change else upgrades += change
            }

            "Remv" -> {
                val match = APT_REMOVE_LINE.find(line)
                    ?: throw IllegalArgumentException("Unparseable apt simulation Remv line")
                removals += WorkspaceAptPackageChange(
                    name = requireOutputName(match.groupValues[1]),
                    fromVersion = versionOrNull(match.groupValues[2]),
                )
            }

            "Purg" -> {
                val match = APT_PURGE_LINE.find(line)
                    ?: throw IllegalArgumentException("Unparseable apt simulation Purg line")
                purges += WorkspaceAptPackageChange(
                    name = requireOutputName(match.groupValues[1]),
                    fromVersion = versionOrNull(match.groupValues[2]),
                )
            }

            else -> {
                val match = APT_CONFIGURE_LINE.find(line)
                    ?: throw IllegalArgumentException("Unparseable apt simulation Conf line")
                configured += requireOutputName(match.groupValues[1])
            }
        }
    }

    return WorkspaceAptSimulation(
        request = this,
        installs = installs,
        upgrades = upgrades,
        removals = removals,
        purges = purges,
        configured = configured,
    ).requireConfirmable()
}

/**
 * Re-checks the plan-level rules on an already parsed simulation (see [parseSimulation]): no empty
 * plan, and no removal or purge behind an install request. Returns the simulation so a caller can
 * chain the check onto loading a plan back from storage.
 */
fun WorkspaceAptSimulation.requireConfirmable(): WorkspaceAptSimulation {
    require(!isEmpty) { "Simulated apt plan would change nothing" }
    if (request.action == WorkspaceAptAction.INSTALL) {
        require(!removesPackages) { "Simulated install would remove packages" }
    }
    return this
}

/**
 * One line per planned change, with the full package name and version, in the order apt resolved
 * them. Only already validated names and versions end up here, so this text may go to the UI
 * as-is; it never contains raw simulation output.
 */
fun WorkspaceAptSimulation.summaryLines(): List<String> = buildList {
    installs.forEach { add("install ${it.name} ${it.toVersion ?: UNKNOWN_VERSION}") }
    upgrades.forEach {
        add(
            "upgrade ${it.name} ${it.fromVersion ?: UNKNOWN_VERSION} -> " +
                "${it.toVersion ?: UNKNOWN_VERSION}",
        )
    }
    removals.forEach { add("remove ${it.name} ${it.fromVersion ?: UNKNOWN_VERSION}") }
    purges.forEach { add("purge ${it.name} ${it.fromVersion ?: UNKNOWN_VERSION}") }
    configured.forEach { add("configure $it") }
}

/**
 * SHA-256 over the request, the environment fingerprint it was computed against and the parsed
 * simulation details. The raw output is not an input: two runs that agree on the parsed details
 * agree on the fingerprint, and a changed version, package, action or purge flag changes it.
 *
 * @throws IllegalArgumentException when [baseFingerprint] is not a single-line printable value.
 */
fun WorkspaceAptSimulation.fingerprint(baseFingerprint: String): String {
    require(APT_FINGERPRINT_PATTERN.matches(baseFingerprint)) {
        "Invalid apt plan base fingerprint"
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonicalDescription(baseFingerprint).toByteArray(Charsets.UTF_8))
    return digest.toHexString()
}

/**
 * Stable canonical serialization of the fingerprint inputs. Values are length-prefixed so no
 * package name, version or fingerprint can shift a field boundary; `null` is `-`.
 */
internal fun WorkspaceAptSimulation.canonicalDescription(baseFingerprint: String): String {
    val builder = StringBuilder()
    builder.append("workspace-apt-plan/1\n")
    builder.append("action=").append(request.action.wireName).append('\n')
    builder.append("purge=").append(request.purge).append('\n')
    builder.append("base=").append(canonicalField(baseFingerprint)).append('\n')
    builder.append("packages=").append(request.packages.size).append('\n')
    request.packages.forEach {
        builder.append("package=").append(canonicalField(it)).append('\n')
    }
    appendChanges(builder, "install", installs)
    appendChanges(builder, "upgrade", upgrades)
    appendChanges(builder, "remove", removals)
    appendChanges(builder, "purge", purges)
    builder.append("configure=").append(configured.size).append('\n')
    configured.forEach {
        builder.append("configured=").append(canonicalField(it)).append('\n')
    }
    return builder.toString()
}

/**
 * Makes raw simulation text safe to show: escape sequences and control characters are removed,
 * credentials in URLs and secret-looking query parameters are redacted and the result is bounded.
 *
 * This is a presentation filter for text apt produced; it is not a validation step and never
 * replaces [parseSimulation].
 */
fun redactWorkspaceAptSimulationText(
    raw: String,
    maxChars: Int = MAX_WORKSPACE_APT_SIMULATION_TEXT_CHARS,
): String {
    require(maxChars >= TRUNCATION_MARKER.length) { "Redaction limit is too small" }
    val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
    var text = ANSI_ESCAPE_PATTERN.replace(normalized, "")
    text = CONTROL_CHARACTER_PATTERN.replace(text, "")
    text = URL_CREDENTIAL_PATTERN.replace(text) { "${it.groupValues[1]}<redacted>@" }
    text = SECRET_QUERY_PARAMETER_PATTERN.replace(text) { "${it.groupValues[1]}<redacted>" }
    if (text.length <= maxChars) return text.trim()
    return text.take(maxChars - TRUNCATION_MARKER.length).trimEnd() + TRUNCATION_MARKER
}

/**
 * Re-checks that [argv] is exactly one of the fixed execution shapes: the guest apt path, `-y`, a
 * fixed verb and then nothing but validated package arguments. Any trailing option would have to
 * be a valid Debian package name to pass, and a leading `-` is not one, so an option such as
 * `--allow-unauthenticated`, `--force-yes` or `--auto-remove` cannot pass this boundary.
 *
 * The argv is always produced by [WorkspaceAptRequest.executionCommand] from an already validated
 * request, so this is a defensive assertion: it is what a test can pin down, and what a regression
 * would have to break before a package is changed.
 */
fun requireExecutionArgv(argv: List<String>) {
    val prefix = APT_EXECUTION_PREFIXES.firstOrNull { candidate ->
        argv.size > candidate.size && argv.subList(0, candidate.size) == candidate
    } ?: throw IllegalArgumentException("apt execution argv is not a fixed public plan shape")
    val packages = argv.drop(prefix.size)
    packages.forEach(::requireValidPackageArgument)
    require(packages.size <= MAX_WORKSPACE_APT_PACKAGES) {
        "apt execution argv must not name more than $MAX_WORKSPACE_APT_PACKAGES packages"
    }
    require(packages.distinct().size == packages.size) {
        "apt execution argv packages must be unique"
    }
    require(packages == packages.sorted()) { "apt execution argv packages must be sorted" }
}

private fun appendChanges(
    builder: StringBuilder,
    label: String,
    changes: List<WorkspaceAptPackageChange>,
) {
    builder.append(label).append('=').append(changes.size).append('\n')
    changes.forEach { change ->
        builder.append(label)
            .append(":name=").append(canonicalField(change.name))
            .append('\n')
        builder.append(label)
            .append(":from=").append(canonicalField(change.fromVersion))
            .append('\n')
        builder.append(label)
            .append(":to=").append(canonicalField(change.toVersion))
            .append('\n')
    }
}

private fun canonicalField(value: String?): String =
    if (value == null) "-" else "${value.length}:$value"

private fun requireValidPackageArgument(value: String) {
    require(value.length in 1..MAX_WORKSPACE_APT_PACKAGE_CHARS && APT_PACKAGE_PATTERN.matches(value)) {
        "Invalid apt package name: '${printableSnippet(value)}'"
    }
}

private fun requireOutputName(value: String): String {
    require(
        value.length in 1..MAX_WORKSPACE_APT_OUTPUT_NAME_CHARS &&
            APT_OUTPUT_NAME_PATTERN.matches(value),
    ) {
        "Unparseable apt simulation package name"
    }
    return value
}

private fun versionOrNull(value: String): String? {
    if (value.isEmpty()) return null
    require(APT_VERSION_PATTERN.matches(value)) { "Unparseable apt simulation version" }
    return value
}

/** Keeps exception messages readable without letting control characters into a log. */
private fun printableSnippet(value: String): String =
    value.filter { it.code in 0x20..0x7E }.take(64)

private fun ByteArray.toHexString(): String = joinToString("") {
    "%02x".format(Locale.US, it.toInt() and 0xff)
}
