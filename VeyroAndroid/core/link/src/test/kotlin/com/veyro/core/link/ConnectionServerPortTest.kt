package com.veyro.core.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/** ADR-052: the listening port of the previous run is reused when free, else the OS picks one. */
class ConnectionServerPortTest {

    private fun server() = ConnectionServer(onConnection = { _, _, _ -> })

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `a free preferred port is reused`() {
        val wanted = freePort()
        val server = server()
        try {
            assertEquals(wanted, server.start(preferredPort = wanted))
            assertTrue(server.isRunning)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a taken preferred port falls back to one the OS picks`() {
        ServerSocket(0).use { taken ->
            val server = server()
            try {
                val port = server.start(preferredPort = taken.localPort)
                assertNotEquals(taken.localPort, port)
                assertTrue(port > 0)
                assertTrue(server.isRunning)
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun `no or an invalid preference lets the OS pick`() {
        listOf(0, 80, 70_000).forEach { preference ->
            val server = server()
            try {
                assertTrue(server.start(preferredPort = preference) >= 1024)
            } finally {
                server.stop()
            }
        }
    }
}
