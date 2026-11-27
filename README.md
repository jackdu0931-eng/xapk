# XAPK Installer (prototype)

A lightweight Android prototype that receives `.xapk` files via file-manager Open With/share, extracts APK files, and submits all APKs to a PackageInstaller session (supporting split APK installs).

## Build

Use Android Studio with JDK 17 and Android SDK 35, or run `gradle assembleDebug` with Gradle 8.9. GitHub Actions builds a debug APK on push or manual workflow dispatch; download it from Actions > workflow run > Artifacts.

## Limitations

- Android may require enabling “Install unknown apps” for the app that opened this installer.
- The APK is a prototype and is not yet tested on-device.
- XAPK OBB files are extracted but are **not restored** to Android/obb. Only APK package installation is handled.
- File-manager MIME/extension recognition varies by app and device; if it doesn't appear in Open With, share the file to the installer or use a compatible file manager.
