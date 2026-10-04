package com.hermesagent.mobile.device

fun main() {
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
    println("15 owner metadata checks passed")
}
