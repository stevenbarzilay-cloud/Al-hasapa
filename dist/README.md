# dist

The latest builds, published here at fixed addresses so they can be fetched without a computer: the TV build with the Downloader app on
the TV, the phone build with the phone's browser.

**TV (1.9.0-tv):**

    https://raw.githubusercontent.com/stevenbarzilay-cloud/Al-hasapa/ccr-e79b67fd-boiaic/dist/al-hasapa-tv.apk

**Phone (1.9.0):**

    https://raw.githubusercontent.com/stevenbarzilay-cloud/Al-hasapa/ccr-e79b67fd-boiaic/dist/al-hasapa-phone.apk

Both are release builds (R8-shrunk, no debug overhead) signed with the repository's own debug key (`app/debug.keystore`), like every other
build, so each installs over the previous one (debug or release) and keeps the logins and history. Every new version replaces the files
(and adds their size to the repository's history).
