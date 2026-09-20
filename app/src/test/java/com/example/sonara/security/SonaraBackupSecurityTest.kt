package com.example.sonara.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Security validation tests for Android backup and data extraction configuration.
 *
 * Finding ID: sonara:android:unrestricted-data-extraction-backup
 *
 * Validates:
 * 1. AndroidManifest.xml keeps allowBackup="true", while referencing both modern
 *    dataExtractionRules (@xml/data_extraction_rules) and legacy fullBackupContent (@xml/backup_rules).
 * 2. data_extraction_rules.xml (Android 12+ / API 31+) explicitly:
 *    - includes database domain ("database", ".")
 *    - includes datastore preferences ("file", "datastore")
 *    - excludes offline downloaded audio and partial downloads ("file", "sonara_downloads")
 *    for BOTH <cloud-backup> and <device-transfer>.
 * 3. backup_rules.xml (Android 6-11 / API 23-30) explicitly:
 *    - includes database domain ("database", ".")
 *    - includes datastore preferences ("file", "datastore")
 *    - excludes offline downloaded audio ("file", "sonara_downloads").
 */
class SonaraBackupSecurityTest {

    private fun resolveProjectFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app", relativePath),
            File("..", relativePath),
            File("../app", relativePath)
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Could not find file '$relativePath'. Searched: ${candidates.map { it.absolutePath }}")
    }

    private fun parseXml(file: File): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val builder = factory.newDocumentBuilder()
        return builder.parse(file)
    }

    private fun NodeList.toList(): List<Node> {
        val list = ArrayList<Node>(length)
        for (i in 0 until length) {
            list.add(item(i))
        }
        return list
    }

    private fun Element.childElements(tagName: String): List<Element> {
        val result = mutableListOf<Element>()
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && (tagName == "*" || node.nodeName == tagName)) {
                result.add(node as Element)
            }
        }
        return result
    }

    // -------------------------------------------------------------------------
    // 1. AndroidManifest.xml verification
    // -------------------------------------------------------------------------

    @Test
    fun `manifest enables backup and configures modern and legacy rule references`() {
        val manifestFile = resolveProjectFile("src/main/AndroidManifest.xml")
        val doc = parseXml(manifestFile)

        val appElements = doc.getElementsByTagName("application")
        assertEquals("Expected exactly one <application> element in manifest", 1, appElements.length)
        val appElement = appElements.item(0) as Element

        val allowBackup = appElement.getAttribute("android:allowBackup")
        assertEquals("android:allowBackup must be true to preserve user-owned library data", "true", allowBackup)

        val dataExtractionRules = appElement.getAttribute("android:dataExtractionRules")
        assertEquals(
            "android:dataExtractionRules must reference @xml/data_extraction_rules",
            "@xml/data_extraction_rules",
            dataExtractionRules
        )

        val fullBackupContent = appElement.getAttribute("android:fullBackupContent")
        assertEquals(
            "android:fullBackupContent must reference @xml/backup_rules",
            "@xml/backup_rules",
            fullBackupContent
        )
    }

    // -------------------------------------------------------------------------
    // 2. data_extraction_rules.xml (Android 12+ / API 31+) verification
    // -------------------------------------------------------------------------

    @Test
    fun `data extraction rules configure cloud-backup and device-transfer with proper includes and excludes`() {
        val file = resolveProjectFile("src/main/res/xml/data_extraction_rules.xml")
        val doc = parseXml(file)

        val root = doc.documentElement
        assertEquals("Root element must be data-extraction-rules", "data-extraction-rules", root.nodeName)

        val cloudBackupElements = root.childElements("cloud-backup")
        assertEquals("Must contain exactly one <cloud-backup> block", 1, cloudBackupElements.size)
        assertRulesSection(cloudBackupElements[0], "cloud-backup")

        val deviceTransferElements = root.childElements("device-transfer")
        assertEquals("Must contain exactly one <device-transfer> block", 1, deviceTransferElements.size)
        assertRulesSection(deviceTransferElements[0], "device-transfer")
    }

    // -------------------------------------------------------------------------
    // 3. backup_rules.xml (Android 6-11 / API 23-30) verification
    // -------------------------------------------------------------------------

    @Test
    fun `legacy backup rules configure proper includes and excludes`() {
        val file = resolveProjectFile("src/main/res/xml/backup_rules.xml")
        val doc = parseXml(file)

        val root = doc.documentElement
        assertEquals("Root element must be full-backup-content", "full-backup-content", root.nodeName)

        assertRulesSection(root, "full-backup-content")
    }

    // -------------------------------------------------------------------------
    // Helper assertion methods
    // -------------------------------------------------------------------------

    private fun assertRulesSection(sectionElement: Element, sectionName: String) {
        val includes = sectionElement.childElements("include")
        val excludes = sectionElement.childElements("exclude")

        // 1. Verify database is included
        val dbInclude = includes.find { it.getAttribute("domain") == "database" }
        assertNotNull("Section <$sectionName> must include domain='database'", dbInclude)
        assertEquals(
            "Section <$sectionName> database include path must be '.'",
            ".",
            dbInclude?.getAttribute("path")
        )

        // 2. Verify file domain is included (which includes filesDir/datastore)
        val fileInclude = includes.find {
            it.getAttribute("domain") == "file" && (it.getAttribute("path") == "." || it.getAttribute("path") == "datastore")
        }
        assertNotNull(
            "Section <$sectionName> must include domain='file' (covering preferences)",
            fileInclude
        )

        // 3. Verify sonara_downloads is explicitly excluded from the included files
        val downloadsExclude = excludes.find {
            it.getAttribute("domain") == "file" && it.getAttribute("path") == "sonara_downloads"
        }
        assertNotNull(
            "Section <$sectionName> must explicitly exclude domain='file' path='sonara_downloads'",
            downloadsExclude
        )
    }
}
