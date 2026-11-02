# Testing with the car's own launcher

`LauncherWheelTest` runs the head unit's launcher code against the app: its
steering-wheel handling (SwcManager, ModeManager, VolumeManager) and SAIC's car
API, so the wheel's keys are handled exactly as they are in the car.  That code
is SAIC's, so it's never in this repo: the test converts it from the launcher
APK when it's given one, and is skipped otherwise (as in the release workflow).

The APK comes from the head unit's firmware update (tested with
1100 SWI131 R27 09.08.24, a full A/B OTA zip):

```
unzip <update>.zip payload.bin
python3 tools/firmware/payload_extract.py payload.bin system system.img
debugfs -R 'rdump <launcher folder> <out-dir>' system.img
```

The launcher is `Launcher_eh32small_l.apk`, in a folder of its own with the
other system apps (`debugfs -R 'ls <folder>' system.img` lists a folder).
Keep the APK outside the repo, and delete the update and the images.

```
cd tools/buildcheck
FIRMWARE_LAUNCHER_APK=/path/to/Launcher_eh32small_l.apk gradle :app:testDebugUnitTest --rerun --tests '*LauncherWheelTest*'
```

- Gradle converts the APK with dex2jar into `app/build/launcher/launcher.jar`
  (ignored by git).  `--rerun`: Gradle doesn't notice the environment.
- `LAUNCHER_LOG=1` prints the launcher's and the app's logs.
- What the launcher needs from the rest of the car is stood in for: Android's
  MediaController (its commands reach the app's session on the main thread;
  its state is the one the app last set), SAIC's car audio service (checked
  against the firmware's own interface codes), and SAIC's additions to Android
  (empty).  The launcher's work thread is the main thread here.
