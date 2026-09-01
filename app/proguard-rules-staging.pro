# R8 configuration applied ONLY to the `staging` (beta) build type, layered on
# top of proguard-rules.pro via proguardFile() in app/build.gradle.kts.
#
# Why: a staging APK exists to be tested, and an obfuscated stack trace is
# useless without the exact matching mapping.txt. Skipping the renaming step
# makes the in-app crash screen and `adb logcat` report real class and method
# names, so a crash can be diagnosed straight from the TV screen.
#
# What this does NOT change: code shrinking, tree shaking and R8 optimization
# still run, as do resource shrinking and the baseline profile. Those are the
# parts that affect runtime performance, so the A/B comparison against the
# Play Store build stays valid. Renaming affects DEX size only.
-dontobfuscate
