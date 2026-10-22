package com.hermesagent.mobile.data.gateway

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SessionProjectTargetsTest {
    private val tree = Json.parseToJsonElement("""{"projects":[
      {"id":"parent","path":"/work","repos":[]},
      {"id":"nested","path":"/work/repo","repos":[{"path":"/work/repo","groups":[{"path":"/work/sibling-tree"}]}]},
      {"id":"windows","path":"C:/Work/Repo/","repos":[]}
    ]}""")
    @Test fun `longest root excludes owning nested project`() {
        assertEquals("nested", projectOwningCwd(tree, "/work/repo/subfolder"))
    }
    @Test fun `worktree outside repository retains project owner`() {
        assertEquals("nested", projectOwningCwd(tree, "/work/sibling-tree/subfolder"))
    }
    @Test fun `prefix without separator does not claim unrelated project`() {
        assertNull(projectOwningCwd(tree, "/workspace"))
    }
    @Test fun `windows separators drive case and trailing slash compare like desktop`() {
        assertEquals("windows", projectOwningCwd(tree, "c:\\work\\repo\\subfolder\\"))
    }
    @Test fun `posix identity remains case sensitive`() {
        assertNull(projectOwningCwd(tree, "/WORK/repo"))
    }
}
