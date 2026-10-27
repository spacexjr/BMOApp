package br.com.bmoapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun montaUrlsWebSocketEHttp() {
        val settings = AppSettings(host = "192.168.3.24", port = 8765, token = "x")
        assertEquals("ws://192.168.3.24:8765/ws", settings.wsUrl)
        assertEquals("http://192.168.3.24:8765", settings.httpUrl)
    }

    @Test
    fun removeEsquemasEBarraFinalDoHost() {
        val settings = AppSettings(host = " http://192.168.3.24/ ", port = 8765, token = "x")
        assertEquals("ws://192.168.3.24:8765/ws", settings.wsUrl)
        assertEquals("http://192.168.3.24:8765", settings.httpUrl)
    }

    @Test
    fun exigeHostEPortaValida() {
        assertFalse(AppSettings().isConfigured)
        assertFalse(AppSettings(host = "192.168.3.24", port = 0).isConfigured)
        assertFalse(AppSettings(host = "192.168.3.24", port = 70000).isConfigured)
        assertTrue(AppSettings(host = "192.168.3.24", port = 8765).isConfigured)
    }
}
