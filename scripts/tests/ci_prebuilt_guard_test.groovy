// Execute the actual init script with a minimal Gradle API boundary, no Gradle.
def loader = new GroovyClassLoader()
loader.parseClass('''
package com.android.build.gradle.internal.tasks
class TestPreBuildTask { String path; boolean enabled = true; List actions = [{}] }
class Fixture extends TestPreBuildTask {}
''')
def check = { String scenario, String expected ->
    def root = new File('/synthetic-checkout')
    def provider = { String name -> new Expando(get: { -> [asFile: new File(root, name)] }) }
    def data = [testApkDir: provider('app/build/outputs/apk/androidTest/debug'),
                testedApksDir: provider('app/build/outputs/apk/debug')]
    def task = [path: ':app:connectedDebugAndroidTest', enabled: true,
                ignoreFailures: false, testData: new Expando(get: { -> data })]
    def comparison = loader.loadClass('com.android.build.gradle.internal.tasks.Fixture').newInstance()
    comparison.path = ':app:preDebugAndroidTestBuild'
    def tasks = [[path: ':app:preBuild', actions: []], comparison, task]
    def start = [configurationCacheRequested: false]
    switch (scenario) {
        case 'compile': tasks.add([path: ':app:compileDebugKotlin']); break
        case 'gate': tasks.add([path: ':app:newVerificationGate']); break
        case 'missing': tasks.clear(); break
        case 'wrong-task': task.path = ':app:connectedReleaseAndroidTest'; break
        case 'disabled': task.enabled = false; break
        case 'ignore-failures': task.ignoreFailures = true; break
        case 'cache': start.configurationCacheRequested = true; break
        case 'prebuild-action': tasks[0].actions = [{ -> }]; break
        case 'variant-action': comparison.actions.add({ -> }); break
        case 'disabled-comparison': comparison.enabled = false; break
        case 'wrong-comparison': tasks[1] = new Expando(path: comparison.path, actions: [{}], enabled: true); break
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
check('prebuild-action', 'lifecycle actions')
['variant-action', 'disabled-comparison', 'wrong-comparison'].each { check(it, 'classpath comparison') }
println('14 guard scenarios passed')
