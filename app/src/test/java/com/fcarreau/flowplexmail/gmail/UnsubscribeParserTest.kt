package com.fcarreau.flowplexmail.gmail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsubscribeParserTest {

    @Test
    fun `header nul retourne aucune cible`() {
        assertNull(parseListUnsubscribe(null, null))
    }

    @Test
    fun `header vide retourne aucune cible`() {
        assertNull(parseListUnsubscribe("  ", null))
    }

    @Test
    fun `lien http seul est detecte`() {
        val target = parseListUnsubscribe("<https://exemple.com/unsub?id=42>", null)

        assertTrue(target is UnsubscribeTarget.Http)
        assertEquals("https://exemple.com/unsub?id=42", (target as UnsubscribeTarget.Http).url)
        assertEquals(false, target.oneClickPost)
    }

    @Test
    fun `lien http est priorise sur mailto quand les deux sont presents`() {
        val target = parseListUnsubscribe(
            "<mailto:unsub@exemple.com?subject=stop>, <https://exemple.com/unsub>",
            null,
        )

        assertTrue(target is UnsubscribeTarget.Http)
        assertEquals("https://exemple.com/unsub", (target as UnsubscribeTarget.Http).url)
    }

    @Test
    fun `List-Unsubscribe-Post active le mode one-click POST`() {
        val target = parseListUnsubscribe(
            "<https://exemple.com/unsub>",
            "List-Unsubscribe=One-Click",
        )

        assertTrue(target is UnsubscribeTarget.Http)
        assertTrue((target as UnsubscribeTarget.Http).oneClickPost)
    }

    @Test
    fun `List-Unsubscribe-Post avec une valeur differente ne declenche pas le one-click`() {
        val target = parseListUnsubscribe("<https://exemple.com/unsub>", "autre-chose")

        assertTrue(target is UnsubscribeTarget.Http)
        assertEquals(false, (target as UnsubscribeTarget.Http).oneClickPost)
    }

    @Test
    fun `mailto seul est detecte avec adresse et sujet decode`() {
        val target = parseListUnsubscribe("<mailto:unsub@exemple.com?subject=Please%20stop>", null)

        assertTrue(target is UnsubscribeTarget.Mailto)
        val mailto = target as UnsubscribeTarget.Mailto
        assertEquals("unsub@exemple.com", mailto.address)
        assertEquals("Please stop", mailto.subject)
    }

    @Test
    fun `mailto sans parametre subject a un subject nul`() {
        val target = parseListUnsubscribe("<mailto:unsub@exemple.com>", null)

        assertTrue(target is UnsubscribeTarget.Mailto)
        assertNull((target as UnsubscribeTarget.Mailto).subject)
    }

    @Test
    fun `aucun token reconnu retourne null`() {
        assertNull(parseListUnsubscribe("<ftp://exemple.com/nope>", null))
    }
}
