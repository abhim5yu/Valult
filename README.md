# Personal Vault

An offline-first Android app for saving essential personal information, notes and images.

## Features
- Fingerprint or secure device-lock authentication
- Local AES-GCM encrypted data file with a key held in Android Keystore
- Search across saved names, categories, tags and text
- Attach an image to an entry
- Pastel green, rounded-card interface with a dark-mode toggle
- No account or cloud sync. Internet is used only to check GitHub for a newer version (your data never leaves the device)
- In-app update check: tap ↻ or open the app; if a newer build exists it offers the download

## Build
Every push to `main` runs GitHub Actions and publishes a signed APK as a downloadable GitHub Release. All builds use the same signing key (GitHub secrets) and a higher version number each time, so a new APK installs over the old one as an update.

**Latest APK:** https://github.com/abhim5yu/Valult/releases/latest/download/PersonalVault.apk

## Important
This is an initial build. Test with non-sensitive sample data before storing real identity or banking documents. Keep a secure backup of important records; uninstalling the app or losing device keys can make encrypted data unrecoverable. The first version supports one image per entry and stores image content inside the encrypted local vault.
