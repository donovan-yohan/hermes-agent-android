// Execute the actual init script with a minimal Gradle API boundary, no Gradle.
def check = { String scenario, String expected ->
    def root = new File('/synthetic-checkout')
    def provider = { String name -> new Expando(get: { -> [asFile: new File(root, name)] }) }
    def data = [testApkDir: provider('app/build/outputs/apk/androidTest/debug'),
                testedApksDir: provider('app/build/outputs/apk/debug')]
    def task = [path: ':app:connectedDebugAndroidTest', enabled: true,
                ignoreFailures: false, testData: new Expando(get: { -> data })]
    def tasks = [task]
    def start = [configurationCacheRequested: false]
    switch (scenario) {
        case 'compile': tasks.add([path: ':app:compileDebugKotlin']); break
        case 'gate': tasks.add([path: ':app:newVerificationGate']); break
        case 'missing': tasks.clear(); break
        case 'wrong-task': task.path = ':app:connectedReleaseAndroidTest'; break
        case 'disabled': task.enabled = false; break
        case 'ignore-failures': task.ignoreFailures = true; break
        case 'cache': start.configurationCacheRequested = true; break
        case 'test-dir': data.testApkDir = provider('other-test'); break
        case 'app-dir': data.testedApksDir = provider('other-app'); break
    }
    def fakeGradle = [startParameter: start, rootProject: [projectDir: root],
                      taskGraph: [whenReady: { Closure action -> action([allTasks: tasks]) }]]
    try {
        new GroovyShell(new Binding([gradle: fakeGradle])).evaluate(new File(args[0]))
        assert expected == null : "accepted forbidden scenario ${scenario}"
    } catch (IllegalStateException failure) {
        assert expected != null && failure.message.contains(expected) : failure.message
    }
}
check('valid', null)
['compile', 'gate', 'missing', 'wrong-task'].each { check(it, 'task graph') }
['disabled', 'ignore-failures'].each { check(it, 'test execution') }
check('cache', 'configuration cache')
['test-dir', 'app-dir'].each { check(it, 'APK directories') }
println('10 guard scenarios passed')
