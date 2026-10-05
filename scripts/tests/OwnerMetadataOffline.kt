package com.hermesagent.mobile.device

fun main() {
    var checks = 0
    fun test(condition: Boolean) { checks++; check(condition) { "owner metadata check $checks failed" } }
    fun input(key: String = "abc") = "  FocusedWindows:\n    displayId=0, name='$key PRIVATE'\n  FocusRequests: <none>\n  Display: 0\n    Windows:\n      0: name='$key PRIVATE', id=1, displayId=0, inputConfig=NOT_TOUCH_MODAL | TRUSTED_OVERLAY, alpha=1.00, frame=[0,0][10,10], globalScale=1.000000, applicationInfo.name=PRIVATE, applicationInfo.token=PRIVATE, touchableRegion=[0,0][10,10], ownerPid=514, ownerUid=1000, dispatchingTimeout=5000ms, hasToken=PRIVATE, touchOcclusionMode=BLOCK_UNTRUSTED\n  Global Monitors: <none>\n"
    val window = "  Window #0 Window{abc u0 PRIVATE}:\n    mDisplayId=0 rootTaskId=1 mSession=Session{PRIVATE}\n    mOwnerUid=1000 showForAllUsers=false package=PRIVATE\n    mAttrs={(0,0)(fillxfill) ty=SYSTEM_DIALOG fmt=TRANSLUCENT\n"
    val ps = "  PID   UID NAME\n 514 1000 system_server\n"
    val owner = FocusOwner.Record(0,514,1000)
    val result = OwnerMetadata.reduce(input(), owner, ps, window)
    check(result.processRole == "SYSTEM_SERVER")
    check(result.windowType == "SYSTEM_DIALOG")
    check(result.inputConfig == listOf("NOT_TOUCH_MODAL", "TRUSTED_OVERLAY"))
    check(!result.toString().contains("PRIVATE") && !result.toString().contains("abc"))
    check(OwnerMetadata.reduce(input(), owner, ps.replace("514", "515"), window).processRole == "UNKNOWN")
    check(OwnerMetadata.reduce(input(), owner, ps.replace("1000", "1001"), window).processRole == "UNKNOWN")
    check(OwnerMetadata.reduce(input(), owner, ps.replace("system_server", "PRIVATE"), window).processRole == "UNKNOWN")
    check(OwnerMetadata.reduce(input(), owner, ps + "514 1000 system_server\n", window).processRole == "UNKNOWN")
    check(OwnerMetadata.reduce(input(), owner, ps, window.replace("{abc", "{def")).windowType == "UNKNOWN")
    check(OwnerMetadata.reduce(input(), owner, ps, window + window).windowType == "UNKNOWN")
    check(OwnerMetadata.reduce(input(), owner, ps, window.replace("SYSTEM_DIALOG", "PRIVATE")).windowType == "UNKNOWN")
    check(OwnerMetadata.reduce(input().replace("TRUSTED_OVERLAY", "PRIVATE"), owner, ps, window).inputConfig == null)
    check(OwnerMetadata.reduce(input(), owner.copy(ownerPid=515), ps, window).windowType == "UNKNOWN")
    check(OwnerMetadata.reduce(input(), owner, ps, window.replace("mOwnerUid=1000", "mOwnerUid=1001")).windowType == "UNKNOWN")
    check(OwnerMetadata.reduce(input().replace("NOT_TOUCH_MODAL | TRUSTED_OVERLAY", "0x0"), owner, ps, window).inputConfig == emptyList<String>())
    fun classify(title: String, dump: String? = null, source: String = input(), processes: String = ps,
                 target: FocusOwner.Record = owner): OwnerMetadata.AlertClass =
        OwnerMetadata.reduce(source, target, processes,
            dump ?: window.replace("u0 PRIVATE", "u0 $title").replace("SYSTEM_DIALOG", "SYSTEM_ALERT")).alertClass
    test(classify("Application Not Responding: PRIVATE") == OwnerMetadata.AlertClass.APPLICATION_NOT_RESPONDING)
    test(classify("Application Error: PRIVATE") == OwnerMetadata.AlertClass.APPLICATION_ERROR)
    test(classify("PRIVATE") == OwnerMetadata.AlertClass.OTHER)
    test(result.alertClass == OwnerMetadata.AlertClass.UNKNOWN)
    for (title in listOf("", " ", "Application Not Responding:", "Application Not Responding: ", "Application Error:",
                         "Application Error: ", "Application Error: PRIVATE'", "Application Error: PRIVATE\"",
                         "Application Error: PRIVATE\nINJECTED", "Application Error: PRIVATE\rINJECTED",
                         "Application Error: PRIVATE\tINJECTED", "Application Error: PRIVATE}",
                         "Application Error: PRIVATE{", "Application Error: PRIVATE\u0000")) {
        test(classify(title) == OwnerMetadata.AlertClass.UNKNOWN)
    }
    for (title in listOf("application error: PRIVATE", "PRIVATE Application Error: PRIVATE", "Application Errorish: PRIVATE")) {
        test(classify(title) == OwnerMetadata.AlertClass.OTHER)
    }
    val alert = window.replace("u0 PRIVATE", "u0 Application Error: PRIVATE").replace("SYSTEM_DIALOG", "SYSTEM_ALERT")
    for (dump in listOf("", alert.replace("{abc", "{def"), alert + alert,
                        alert.replace("mDisplayId=0", "mDisplayId=1"),
                        alert.replace("mOwnerUid=1000", "mOwnerUid=1001"),
                        alert + "    mDisplayId=1\n", alert + "    mDisplayId=0\n",
                        alert + "    mOwnerUid=1001\n", alert + "    mOwnerUid=1000\n",
                        alert.replace("mDisplayId=0", "mDisplayId=0 mDisplayId=1"),
                        alert.replace("mOwnerUid=1000", "mOwnerUid=1000 mOwnerUid=1001"),
                        alert + "    mAttrs={ty=SYSTEM_ALERT}\n",
                        alert + "    mAttrs={ty=2003}\n",
                        alert.replace("ty=SYSTEM_ALERT", "ty=SYSTEM_DIALOG ty=SYSTEM_ALERT"),
                        alert.replace("ty=SYSTEM_ALERT", "ty=SYSTEM_ALERT ty=2003"),
                        alert.replace("SYSTEM_ALERT", "SYSTEM_ERROR"),
                        alert.replace("SYSTEM_ALERT", "SYSTEM_DIALOG"),
                        alert + alert.replace("PRIVATE}", "PRIVATE'}"))) {
        test(classify("unused", dump) == OwnerMetadata.AlertClass.UNKNOWN)
    }
    for (processes in listOf("", ps.replace("514", "515"), ps.replace("1000", "1001"),
                             ps.replace("system_server", "com.android.systemui"),
                             ps.replace("system_server", "PRIVATE"), ps + "514 1000 system_server\n")) {
        test(classify("Application Error: PRIVATE", processes=processes) == OwnerMetadata.AlertClass.UNKNOWN)
    }
    for (source in listOf("", input().replace("displayId=0", "displayId=1"),
                          input().replace("ownerUid=1000", "ownerUid=1001"),
                          input().replace("ownerPid=514", "ownerPid=515"),
                          input().replace("  FocusRequests:", "    displayId=0, name='abc PRIVATE'\n  FocusRequests:"),
                          input().replace("abc PRIVATE", "abc PRIVATE'INJECTED"))) {
        test(classify("Application Error: PRIVATE", source=source) == OwnerMetadata.AlertClass.UNKNOWN)
    }
    test(classify("Application Error: PRIVATE", target=owner.copy(ownerPid=515)) == OwnerMetadata.AlertClass.UNKNOWN)
    // A later history block must not replace the current focus or supply a title.
    test(classify("PRIVATE", source=input() + "  FocusedWindows:\n    displayId=0, name='def Application Error: PRIVATE'\n") == OwnerMetadata.AlertClass.OTHER)
    val reduced = OwnerMetadata.reduce(input(), owner, ps, alert).toString()
    test(listOf("PRIVATE", "abc", "system_server", "Application Error:").none { it in reduced })
    println("15 existing owner metadata checks and $checks alert discriminator checks passed")
}
