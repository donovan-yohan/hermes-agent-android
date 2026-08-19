package com.hermesagent.mobile.data.prefs

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The accepted host key must not travel.
 *
 * It is a decision a person made on one device after comparing a fingerprint
 * out of band. A cloud backup or a device transfer that carries it forward
 * hands the new phone a trusted host it never reviewed, silently — the one
 * thing the whole TOFU design exists to prevent. Excluding it costs one extra
 * fingerprint review on a restored install, which is the safe direction.
 *
 * This reads the shipped XML rather than a copy of it, because the rules only
 * do anything if they are the ones in the APK. It also checks the legacy
 * pre-API-31 file, since a rule that exists in only one of them protects only
 * half the fleet.
 */
class BackupRulesTest {

    /**
     * Where DataStore actually puts it: `preferencesDataStore(name = "hermes")`
     * writes `files/datastore/hermes.preferences_pb` plus temporary siblings, so
     * the directory is the unit that can be excluded. A rule naming a key inside
     * a protobuf blob is a rule that does not exist.
     */
    private val exclusion = """<exclude domain="file" path="datastore" />"""

    @Test
    fun `cloud backup and device transfer both exclude the store the host key lives in`() {
        val rules = resource("data_extraction_rules.xml")

        val cloud = rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val transfer = rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")

        assertTrue("cloud-backup must not carry the accepted fingerprint", cloud.contains(exclusion))
        assertTrue("device-transfer must not carry it either", transfer.contains(exclusion))
    }

    @Test
    fun `the legacy rules say the same thing`() {
        assertTrue(
            "an API 30 device must not restore a host key an API 31 device would refuse to",
            resource("backup_rules.xml").contains(exclusion),
        )
    }

    /** Gradle runs unit tests from the module directory; fall back to the repo root. */
    private fun resource(name: String): String {
        val candidates = listOf(
            File("src/main/res/xml/$name"),
            File("app/src/main/res/xml/$name"),
        )
        val found = candidates.firstOrNull(File::isFile)
        requireNotNull(found) { "$name not found in ${candidates.map(File::getAbsolutePath)}" }
        return found.readText()
    }
}
