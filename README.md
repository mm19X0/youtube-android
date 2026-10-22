# YT Downloader (Android app)

Native Android app (Kotlin + Jetpack Compose). Paste a YouTube link (or press **Share** in the YouTube app),
see the thumbnail, choose Video or Audio and the quality, and download to `Downloads/YT-Downloader`.

It uses [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) to read YouTube.

## Build the APK with GitHub (no Android Studio needed)
1. Create a new **public** repository on GitHub (choose license: **GNU GPLv3**).
2. Upload all the files of this folder (keep the folders, including `.github/workflows/build.yml`).
3. Open the **Actions** tab. The workflow **Build APK** starts by itself.
4. When it turns green, open the run, go to **Artifacts**, download **YT-Downloader-APK**, unzip it.
5. Send `app-debug.apk` to any Android phone (Android 10+) and install it (allow "unknown sources").

## If YouTube stops working
YouTube changes often. Open `app/build.gradle.kts` and update the number in
`NewPipeExtractor:v0.26.5` to the newest version from the NewPipeExtractor releases page, then push again.

## License
NewPipe Extractor is GPL-3.0, so this app should be shared under GPL-3.0 with its source code public.
