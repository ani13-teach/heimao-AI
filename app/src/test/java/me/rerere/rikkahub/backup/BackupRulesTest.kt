package me.rerere.rikkahub.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BackupRulesTest {
    @Test
    fun legacyRulesAreCompleteDenyList() {
        val root = parseSourceXml("res/xml/backup_rules.xml").documentElement

        assertEquals("full-backup-content", root.tagName)
        assertCompleteDenyList(root, "legacy backup rules")
    }

    @Test
    fun api31CloudAndDeviceTransferRulesAreCompleteDenyLists() {
        val root = parseSourceXml("res/xml/data_extraction_rules.xml").documentElement

        assertEquals("data-extraction-rules", root.tagName)
        val sections = childElements(root)
        assertEquals(
            setOf("cloud-backup", "device-transfer"),
            sections.map { it.tagName }.toSet(),
        )
        sections.forEach { section ->
            assertCompleteDenyList(section, "${section.tagName} rules")
        }
    }

    @Test
    fun manifestReferencesBothBackupRuleResources() {
        val manifest = parseSourceXml("AndroidManifest.xml").documentElement
        val applications = manifest.getElementsByTagName("application")
        assertEquals(1, applications.length)

        val application = applications.item(0) as Element
        assertEquals(
            "@xml/backup_rules",
            application.getAttributeNS(ANDROID_NAMESPACE, "fullBackupContent"),
        )
        assertEquals(
            "@xml/data_extraction_rules",
            application.getAttributeNS(ANDROID_NAMESPACE, "dataExtractionRules"),
        )
    }

    private fun assertCompleteDenyList(parent: Element, description: String) {
        val rules = childElements(parent)
        assertTrue("$description must contain exclude rules", rules.isNotEmpty())
        assertTrue(
            "$description must not contain an include or another unconstrained rule",
            rules.all { it.tagName == "exclude" },
        )

        val excludesByDomain = rules.groupBy { rule ->
            rule.getAttribute("domain").also { domain ->
                assertTrue("$description has an unknown backup domain: $domain", domain in DOMAINS)
            }
        }
        assertEquals("$description must cover every backup domain", DOMAINS, excludesByDomain.keys)

        DOMAINS.forEach { domain ->
            assertTrue(
                "$description must exclude the complete $domain domain",
                excludesByDomain.getValue(domain).any { it.getAttribute("path") == "." },
            )
        }

        assertFalse(
            "$description must not use wildcard paths; complete domains cover dynamic recovery names",
            rules.any { it.getAttribute("path").contains('*') },
        )
        assertFalse(
            "$description must not use legacy conditional include flags",
            rules.any { it.hasAttribute("requireFlags") },
        )

        // These are the sensitive paths that must never be covered by an automatic backup. The
        // file/root domain exclusions above intentionally cover dynamic UUID-suffixed names too.
        SENSITIVE_PATHS.forEach { path ->
            assertTrue(
                "$description does not exclude sensitive path $path",
                isExcluded(excludesByDomain, path),
            )
        }
    }

    private fun isExcluded(excludesByDomain: Map<String, List<Element>>, path: String): Boolean {
        val domain = when {
            path.startsWith("database/") -> "database"
            path.startsWith("sharedprefs/") -> "sharedpref"
            path.startsWith("external/") -> "external"
            else -> "file"
        }
        return excludesByDomain.getValue(domain).any { rule ->
            val excludedPath = rule.getAttribute("path").trimEnd('/')
            excludedPath == "." || path == excludedPath || path.startsWith("$excludedPath/")
        }
    }

    private fun parseSourceXml(relativePath: String) =
        secureDocumentBuilder().parse(sourceFile(relativePath))

    private fun secureDocumentBuilder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder()

    private fun sourceFile(relativePath: String): File {
        val candidates = listOf(
            File("app/src/main/$relativePath"),
            File("src/main/$relativePath"),
            File("../app/src/main/$relativePath"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate source resource $relativePath from ${File(System.getProperty("user.dir").orEmpty()).absolutePath}")
    }

    private fun childElements(parent: Element): List<Element> = buildList {
        for (index in 0 until parent.childNodes.length) {
            val node: Node = parent.childNodes.item(index)
            if (node is Element) add(node)
        }
    }

    companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        private val DOMAINS = setOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref",
        )
        private val SENSITIVE_PATHS = listOf(
            "workspaces/plaintext",
            "snapshots/snapshot.zip",
            "datastore/settings.preferences_pb",
            ".workspace-archive-recovery.json",
            ".workspace-snapshot-recovery.json.tmp",
            ".workspace-decrypted-uuid",
            ".workspace-restore-recovery-uuid",
            ".legacy-restore-recovery-uuid",
            ".database-recovery-uuid",
            "database/rikka_hub.db",
            "sharedprefs/workspace_backup_passwords.xml",
            "cache/restore.tmp",
        )
    }
}
