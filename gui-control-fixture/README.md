# GUI control device fixture

Disposable, test-only Android APK with a native counter/scroll surface and a
platform `Instrumentation` actor. It has no host app dependency, network
permission, credentials, or private data. Uninstall after device verification.

The actor instruments itself so it cannot restart the real accessibility
service's host process. It opens the host's native accessibility settings entry,
chooses this exact fixture package, and checks real window capture, screenshot
coordinate mapping, confirmed taps, refused/confirmed swipes, and leaving the
allowed app. It never selects another application or exports screenshots.

Build using the same project Android SDK/JDK setup:

```text
gradlew :gui-control-fixture:assembleDebug
adb install -r -t gui-control-fixture/build/outputs/apk/debug/gui-control-fixture-debug.apk
```

The live scenario requires explicit human authorization before temporarily
enabling ClawMate's accessibility service. Snapshot and restore the original
permission configuration in a `finally` block, including on failure/timeout.
Run with the service already authorized:

```text
adb shell am instrument -w -r -e guiControlGranted true com.clawmate.gui.fixture/com.clawmate.shared.GuiControlExternalRunner
```

Success is `scenarioPassed=true` plus the `guiControlFixture` status marker. This
is one SDK scenario, not a JUnit result. The actor itself does not change system
permissions. Normal migration regression excludes this explicit opt-in scenario.

If the device vendor presents an installation CAPTCHA, the human must complete
it on the phone. The actor and build scripts do not solve or bypass that gate.
Vendor accessibility safety prompts may also require physical confirmation on
the phone after the separate temporary-permission authorization. Complete those
before the live scenario; the actor does not confirm OEM safety dialogs. Keep
the original grant snapshot until the scenario ends, and restore it even when a
prompt blocks the test. A service connection alone is not a gesture test pass.
