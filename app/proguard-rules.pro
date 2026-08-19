# Phase 1 does not ship a shrunk build; these rules exist so the first release
# attempt is not a surprise. sshj resolves transports/ciphers reflectively and
# BouncyCastle registers providers by class name.
-keep class com.hierynomus.** { *; }
-keep class net.schmizz.sshj.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn net.schmizz.sshj.**
-dontwarn org.slf4j.**
