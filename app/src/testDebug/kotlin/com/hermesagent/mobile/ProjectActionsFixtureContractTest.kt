package com.hermesagent.mobile

import org.junit.Assert.*
import org.junit.Test

class ProjectActionsFixtureContractTest {
    @Test fun `debug native project actions fixture exists`() {
        assertNotNull(Class.forName("com.hermesagent.mobile.ProjectActionsParityActivity"))
    }
}
