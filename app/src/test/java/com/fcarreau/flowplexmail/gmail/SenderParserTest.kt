package com.fcarreau.flowplexmail.gmail

import org.junit.Assert.assertEquals
import org.junit.Test

class SenderParserTest {

    @Test
    fun `nom affichable entre guillemets et email entre chevrons`() {
        val info = parseSender("\"Boutique Exemple\" <no-reply@boutique.com>")

        assertEquals("Boutique Exemple", info.displayName)
        assertEquals("boutique.com", info.domain)
    }

    @Test
    fun `nom affichable sans guillemets`() {
        val info = parseSender("Boutique Exemple <no-reply@boutique.com>")

        assertEquals("Boutique Exemple", info.displayName)
        assertEquals("boutique.com", info.domain)
    }

    @Test
    fun `domaine est mis en minuscules`() {
        val info = parseSender("Boutique <No-Reply@Boutique.COM>")

        assertEquals("boutique.com", info.domain)
    }

    @Test
    fun `adresse seule sans nom affichable utilise le domaine comme nom`() {
        val info = parseSender("no-reply@boutique.com")

        assertEquals("boutique.com", info.displayName)
        assertEquals("boutique.com", info.domain)
    }

    @Test
    fun `nom affichable vide entre chevrons retombe sur le domaine`() {
        val info = parseSender("   <no-reply@boutique.com>")

        assertEquals("boutique.com", info.displayName)
        assertEquals("boutique.com", info.domain)
    }

    @Test
    fun `deux expediteurs du meme domaine partagent le meme domaine de regroupement`() {
        val a = parseSender("Commandes <orders@boutique.com>")
        val b = parseSender("Support <support@boutique.com>")

        assertEquals(a.domain, b.domain)
    }

    @Test
    fun `header sans arobase produit un domaine vide`() {
        val info = parseSender("expediteur-invalide")

        assertEquals("", info.domain)
        assertEquals("expediteur-invalide", info.displayName)
    }
}
