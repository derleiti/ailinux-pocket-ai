package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class AppLanguageCatalogTest {
    @Test fun nativeLocalePickerIncludesSystemDefaultAndCentralEuropeanAsianLanguages() {
        val all = AppLanguageCatalog.choices
        assertEquals("", all.first().tag)
        assertEquals(all.size, all.map { it.tag }.toSet().size)
        assertTrue(all.size >= 20)
        assertTrue(all.filter { !it.preview }.map { it.tag }.containsAll(listOf("", "de")))
        assertTrue(all.first { it.tag == "en" }.preview)
        assertTrue(all.filter { it.preview }.map { it.tag }.containsAll(
            listOf("fr", "es", "it", "pt", "pl", "cs", "sk", "hu",
                "ro", "tr", "ja", "ko", "zh-Hans-CN", "zh-Hant-TW", "hi")))
        assertTrue(all.filter { it.tag.isNotBlank() }.all {
            Locale.forLanguageTag(it.tag).language.isNotBlank()
        })
    }

    @Test fun selectionHandlesSystemResetAndCanonicalLanguageTags() {
        assertEquals(0, AppLanguageCatalog.selectedIndex(""))
        assertEquals(1, AppLanguageCatalog.selectedIndex("de"))
        assertEquals(2, AppLanguageCatalog.selectedIndex("en"))
        assertEquals(17, AppLanguageCatalog.selectedIndex("ko,fr"))
        assertEquals(0, AppLanguageCatalog.selectedIndex("xx-NO"))
        assertEquals(AppLanguageCatalog.selectedIndex("zh-Hans-CN"),
            AppLanguageCatalog.selectedIndex("zh-hans-cn"))
    }

    @Test fun baseAndEnglishStringsCoverEveryMainUiResource() {
        val files = listOf("src/main/res/values/strings.xml",
            "src/main/res/values-en/strings.xml")
        val read = files.map { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(file))
            val strings = doc.getElementsByTagName("string")
            (0 until strings.length).map { index ->
                strings.item(index).attributes.getNamedItem("name").nodeValue
            }.toSet()
        }
        val base = read[0]
        assertTrue(base.contains("language_dialog_title"))
        assertTrue(read[1].containsAll(base - setOf("app_name")))
        val layout = File("src/main/res/layout/activity_main.xml").readText()
        val used = Regex("@string/([a-z_]+)").findAll(layout).map { it.groupValues[1] }.toSet()
        assertTrue("Unresolved layout strings: " + (used - base), base.containsAll(used))
        assertTrue(layout.contains("android:id=\"@+id/appLanguageButton\""))
    }

    @Test fun previewPacksAreExplicitlyPartialAndNeverAdvertisedInSystemLocaleConfig() {
        val config = File("src/main/res/xml/locale_config.xml").readText()
        assertTrue(config.contains("android:name=\"de\""))
        assertTrue(config.contains("android:name=\"en\""))
        assertFalse(config.contains("android:name=\"fr\""))
        for (locale in AppLanguageCatalog.choices.filter { it.preview && it.tag != "en" }) {
            val directory = if (locale.tag.startsWith("zh-"))
                "values-b+" + locale.tag.replace("-", "+")
            else "values-" + locale.tag
            val pack = File("src/main/res/$directory/strings.xml")
            assertTrue("Missing locale preview: " + locale.tag, pack.isFile)
            val xml = pack.readText()
            for (key in listOf("nav_chat", "nav_models", "nav_setup",
                "history_title", "library_title", "settings_title",
                "language_dialog_title", "language_system_default")) {
                assertTrue("Missing $key for " + locale.tag, xml.contains("name=\"$key\""))
            }
        }
    }
}
