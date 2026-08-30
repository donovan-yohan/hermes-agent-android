plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

/**
 * Repo invariants that no Kotlin test can see — the CLAUDE.md symlink, the
 * chalkbag ignore rules, the theme ledger's upstream pin. `:app:check` depends
 * on this, so a broken invariant fails the same command that runs the tests
 * rather than waiting for someone to notice.
 */
val verifyRepoInvariants by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks repository invariants (see scripts/check-repo-invariants.sh)."
    val script = layout.projectDirectory.file("scripts/check-repo-invariants.sh")
    inputs.file(script)
    inputs.file(layout.projectDirectory.file("scripts/check-product-copy.py"))
    inputs.file(layout.projectDirectory.file("scripts/check-composer-parity.py"))
    inputs.file(layout.projectDirectory.file("scripts/check-ci-workflow.py"))
    inputs.dir(layout.projectDirectory.dir("scripts/tests"))
    inputs.file(layout.projectDirectory.file(".chalk/skills/port-hermes-desktop-surface/scripts/capture-android-reference.py"))
    inputs.file(layout.projectDirectory.file(".github/workflows/android-exact-head.yml"))
    inputs.file(layout.projectDirectory.file("scripts/check-parity-evidence.py"))
    // The composer contract's three JSON files and every `docs/parity/*.md` page
    // the parity-evidence gate reads live here, so the directory is the input:
    // adding a parity page must re-run the check rather than be declared up to
    // date over it.
    inputs.dir(layout.projectDirectory.dir("docs/parity"))
    inputs.files(layout.projectDirectory.file("AGENTS.md"))
    // The cleartext invariant reads both of these, so a change to either has to
    // re-run the check rather than being declared up to date over it.
    inputs.file(layout.projectDirectory.file("app/src/main/AndroidManifest.xml"))
    inputs.file(layout.projectDirectory.file("app/src/main/res/xml/network_security_config.xml"))
    commandLine(script.asFile.absolutePath)
}
