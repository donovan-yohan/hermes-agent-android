package com.hermesagent.mobile.device

fun main() {
    fun dump(prefix: String = "abc", title: String = "SECRET", pid: String = "123", uid: String = "10001"): String =
        "  FocusedWindows:\n    displayId=0, name='$prefix $title'\n  FocusRequests: <none>\n" +
        "  Display: 0\n    Windows:\n      0: name='$prefix $title', id=1, displayId=0, inputConfig=0x0, alpha=1.00, frame=[0,0][10,10], globalScale=1.000000, applicationInfo.name=SECRET, applicationInfo.token=SECRET, touchableRegion=[0,0][10,10], ownerPid=$pid, ownerUid=$uid, dispatchingTimeout=5000ms, hasToken=SECRET, touchOcclusionMode=BLOCK_UNTRUSTED\n  Global Monitors: <none>\n"
    fun parse(text: String) = FocusOwner.reduce(text.lineSequence(), 0)
    var tests = 0
    fun test(value: Boolean) { check(value); tests++ }
    test(parse(dump()) == FocusOwner.Record(0,123,10001))
    test(!parse(dump()).toString().contains("SECRET"))
    test(parse(dump().replace("      0: name='abc", "      0: name='def")) == null) // identical title, wrong owner
    test(parse(dump() + "  FocusedWindows:\n    displayId=0, name='def SECRET'\n") == FocusOwner.Record(0,123,10001))
    test(parse(dump().replace("  FocusedWindows:", "  FocusedApplications:")) == null)
    test(parse(dump().replace("  Display: 0", "  Display: 1")) == null)
    test(parse(dump().replace("    displayId=0, name='abc SECRET'", "    displayId=0, name='abc SECRET'\n    displayId=0, name='def SECRET'")) == null)
    val line = dump().lineSequence().first { it.startsWith("      0:") }
    test(parse(dump().replace(line, "$line\n$line")) == null)
    test(parse(dump(pid="2147483648")) == null)
    test(parse(dump(uid="2147483648")) == null)
    test(parse(dump(prefix="nothex")) == null)
    test(parse(dump(title="SECRET', ownerPid=1")) == null)
    test(parse(dump(title="SECRET\n    displayId=1, name='abc forged")) == null)
    test(parse(dump().replace("ownerPid=123", "ownerPid=123, ownerPid=456")) == null)
    test(parse(dump() + "x".repeat(3 * 1024 * 1024)) == FocusOwner.Record(0,123,10001)) // history overflow is irrelevant
    test(parse(dump().replace("  FocusedWindows:", "x".repeat(16385) + "\n  FocusedWindows:")) == null)
    test(parse(dump().replace("SECRET', id", "SECRET', ownerUid=1, id")) == null)
    test(FocusOwner.reduce(dump().lineSequence(), 1) == null)
    println("$tests parser checks passed")
}
