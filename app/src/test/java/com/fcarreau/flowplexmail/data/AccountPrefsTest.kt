package com.fcarreau.flowplexmail.data

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.mockk.Runs
import io.mockk.just
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AccountPrefsTest {

    private val context = mockk<Context>()
    private val prefs = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()

    @Before
    fun setUp() {
        every { context.getSharedPreferences("flowplex_prefs", Context.MODE_PRIVATE) } returns prefs
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.apply() } just Runs
    }

    @Test
    fun `save enregistre le compte puis applique les changements`() {
        AccountPrefs.save(context, "moi@exemple.com")

        verifyOrder {
            editor.putString("account_name", "moi@exemple.com")
            editor.apply()
        }
    }

    @Test
    fun `get retourne le compte enregistre`() {
        every { prefs.getString("account_name", null) } returns "moi@exemple.com"

        assertEquals("moi@exemple.com", AccountPrefs.get(context))
    }

    @Test
    fun `get retourne nul quand aucun compte n a ete enregistre`() {
        every { prefs.getString("account_name", null) } returns null

        assertNull(AccountPrefs.get(context))
    }
}
