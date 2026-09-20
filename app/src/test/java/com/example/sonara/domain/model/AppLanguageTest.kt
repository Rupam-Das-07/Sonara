package com.example.sonara.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for AppLanguage domain model.
 */
class AppLanguageTest {

    @Test
    fun `SYSTEM_DEFAULT has empty bcp47Tag`() {
        assertEquals("", AppLanguage.SYSTEM_DEFAULT.bcp47Tag)
    }

    @Test
    fun `national languages are Hindi Bengali Urdu`() {
        val national = AppLanguage.nationalLanguages().map { it.name }
        assertTrue("Hindi must be present", "HINDI" in national)
        assertTrue("Bengali must be present", "BENGALI" in national)
        assertTrue("Urdu must be present", "URDU" in national)
    }

    @Test
    fun `national languages bcp47 tags are correct`() {
        assertEquals("hi", AppLanguage.HINDI.bcp47Tag)
        assertEquals("bn", AppLanguage.BENGALI.bcp47Tag)
        assertEquals("ur", AppLanguage.URDU.bcp47Tag)
    }

    @Test
    fun `international languages are sorted alphabetically by displayName`() {
        val international = AppLanguage.internationalLanguages()
        val sortedNames = international.map { it.displayName }.sorted()
        assertEquals(sortedNames, international.map { it.displayName })
    }

    @Test
    fun `international languages all have non-empty bcp47 tags`() {
        AppLanguage.internationalLanguages().forEach { lang ->
            assertTrue("${lang.name} must have a non-empty BCP-47 tag", lang.bcp47Tag.isNotEmpty())
        }
    }

    @Test
    fun `fromName returns correct enum for valid name`() {
        assertEquals(AppLanguage.HINDI, AppLanguage.fromName("HINDI"))
        assertEquals(AppLanguage.BENGALI, AppLanguage.fromName("BENGALI"))
        assertEquals(AppLanguage.SYSTEM_DEFAULT, AppLanguage.fromName("SYSTEM_DEFAULT"))
    }

    @Test
    fun `fromName returns SYSTEM_DEFAULT for unknown name`() {
        assertEquals(AppLanguage.SYSTEM_DEFAULT, AppLanguage.fromName("NONEXISTENT_LANGUAGE"))
        assertEquals(AppLanguage.SYSTEM_DEFAULT, AppLanguage.fromName(""))
    }

    @Test
    fun `group is correctly assigned`() {
        assertEquals(LanguageGroup.SYSTEM, AppLanguage.SYSTEM_DEFAULT.group)
        assertEquals(LanguageGroup.NATIONAL, AppLanguage.HINDI.group)
        assertEquals(LanguageGroup.NATIONAL, AppLanguage.BENGALI.group)
        assertEquals(LanguageGroup.NATIONAL, AppLanguage.URDU.group)
        assertEquals(LanguageGroup.INTERNATIONAL, AppLanguage.ENGLISH.group)
    }

    @Test
    fun `all entries have non-null displayNames`() {
        AppLanguage.entries.forEach { lang ->
            assertNotNull("${lang.name} must have a displayName", lang.displayName)
            assertTrue("${lang.name} displayName must not be blank", lang.displayName.isNotBlank())
        }
    }
}
