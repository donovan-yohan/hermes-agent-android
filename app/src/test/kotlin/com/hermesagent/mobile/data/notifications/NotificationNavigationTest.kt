package com.hermesagent.mobile.data.notifications

import org.junit.Assert.*
import org.junit.Test

class NotificationNavigationTest {
    @Test fun coldAndWarmTargetsDispatchOnceOnlyToTheirSavedEndpoint() {
        for (target in listOf(NotificationTarget.Session("saved", "qualified", "bot"), NotificationTarget.Room("saved", "room"))) {
            val navigation = NotificationNavigation()
            val opened = mutableListOf<NotificationTarget>()
            navigation.offer(target)
            navigation.ready(null, false, opened::add, opened::add)
            assertTrue(opened.isEmpty())
            navigation.ready("saved", true, opened::add, opened::add)
            navigation.ready("saved", true, opened::add, opened::add)
            assertEquals(listOf(target), opened)
            navigation.offer(target)
            navigation.ready("replacement", true, opened::add, opened::add)
            assertNull(navigation.pending)
            assertEquals(listOf(target), opened)
            navigation.offer(target)
            navigation.ready("saved", true, opened::add, opened::add)
            assertEquals(listOf(target, target), opened)
        }
    }
}
