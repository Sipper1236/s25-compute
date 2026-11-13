# Build receipt

2026-11-13: Gradle assembleDebug and lintDebug succeeded against Android SDK 35 using JDK 17. APK v2 signature verified with apksigner. aapt reports dev.rayyan.compute version 0.1, min SDK 28, target SDK 35, with no VPN service permission. GLSL compute shader passed glslangValidator. Three desktop client tests passed (correct CPU digest, GPU overflow reference, rejecting incorrect GPU output).

This is a debug signed validation APK. S25 Ultra runtime execution, Adreno readback, Android background behavior, and TailSocks routing are not yet validated. Follow ../VALIDATION.md. TailSocks is an external networking companion and is not bundled.
