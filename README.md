# Hidden XAPK Installer (prototype)

This is a small Android Studio Kotlin project. It deliberately has **no MAIN/LAUNCHER intent filter**; it is invoked by file-open/share intents only.

## Current prototype capabilities
- Opens content URIs from file managers
- Extracts ZIP/XAPK contents safely against path traversal
- Collects APK files and installs them in a single PackageInstaller session (base + splits)
- Requests Android's permission to install unknown apps

## Not yet implemented
- Automatic OBB placement. If an archive includes OBB files, the UI warns. Android storage restrictions mean OBB restoration needs a user-selected folder flow.
- XAPK-specific manifest validation and APK compatibility checks
- Device testing and APK signing

## Build
Open this folder in Android Studio (recent version), allow Gradle sync, then Build > Build APK(s).
Target SDK 35; min SDK 23. Test on a spare device first.

## How to use
Once built and installed, tap an XAPK in your file manager and choose this app. If the file manager only offers sharing, Share > XAPK Installer.
If Android does not list it, use the file manager's "Open with" or share route; MIME types vary across file managers.
