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
    inputs.file(layout.projectDirectory.file("docs/parity/composer-capabilities.json"))
    inputs.file(layout.projectDirectory.file("docs/parity/composer-capture-matrix.json"))
    inputs.file(layout.projectDirectory.file("docs/parity/desktop-composer-inventory.json"))
    inputs.files(layout.projectDirectory.file("AGENTS.md"))
    commandLine(script.asFile.absolutePath)
}
