# dist

The latest builds, published here at fixed addresses so they can be fetched without a computer: the TV build with the Downloader app on
the TV, the phone build with the phone's browser.

**TV (2.0.2-tv):**

    https://raw.githubusercontent.com/stevenbarzilay-cloud/Al-hasapa/ccr-e79b67fd-boiaic/dist/al-hasapa-tv.apk

**Phone (2.0.2):**

    https://raw.githubusercontent.com/stevenbarzilay-cloud/Al-hasapa/ccr-e79b67fd-boiaic/dist/al-hasapa-phone.apk

Both are release builds (R8-shrunk, no debug overhead) signed with the repository's own debug key (`app/debug.keystore`), like every other
build, so each installs over the previous one (debug or release) and keeps the logins and history. Every new version replaces the files
(and adds their size to the repository's history).

These are the builds for **our own devices**. The builds handed to friends (2.0) are signed with a private key and published as a
GitHub Release, at one fixed link; see `docs/DISTRIBUTION.md`. A build from that link does not install over one from here, nor the
other way: each device stays on the track it began on.
