package com.hermesagent.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.core.app.ApplicationProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A scheduled read may create the process, but must not boot the interactive Gateway graph. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationBackgroundStartupTest {
    @Test
    fun backgroundCheckWithoutASavedGatewayReturnsWithoutStartingInteractiveGraph() = kotlinx.coroutines.runBlocking {
        val app = ApplicationProvider.getApplicationContext<HermesApplication>()
        kotlinx.coroutines.withTimeout(5_000) { app.runNotificationCatchUp() }
        for (property in listOf("sessionRepository", "gatewayConnection", "pluginLoader", "composerQueueController")) {
            val field = HermesApplication::class.java.getDeclaredField("$property\$delegate")
            field.isAccessible = true
            assertFalse("Background check initialized $property", (field.get(app) as Lazy<*>).isInitialized())
        }
    }

    @Test
    fun interactiveStartupKeepsOneRepositoryAcrossRepeatedActivityStarts() {
        val app = ApplicationProvider.getApplicationContext<HermesApplication>()
        app.ensureInteractiveStarted()
        val repository = app.sessionRepository
        app.ensureInteractiveStarted()
        assertSame(repository, app.sessionRepository)
    }

    @Test
    fun processCreationDoesNotRestoreSocketOrCreateInteractiveOwners() {
        val app = ApplicationProvider.getApplicationContext<HermesApplication>()
        for (property in listOf("sessionRepository", "gatewayConnection", "pluginLoader", "gatewayActivity", "composerQueueController")) {
            val field = HermesApplication::class.java.getDeclaredField("$property\$delegate")
            field.isAccessible = true
            assertFalse("Background process initialized $property", (field.get(app) as Lazy<*>).isInitialized())
        }
    }
}
