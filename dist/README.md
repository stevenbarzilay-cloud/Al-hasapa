# dist

The latest Android TV build (1.4.0-tv, the optimised release build), published here so that the Downloader app on the TV can fetch it from a fixed address:

    https://raw.githubusercontent.com/stevenbarzilay-cloud/Al-hasapa/ccr-e79b67fd-boiaic/dist/al-hasapa-tv.apk

It is the release build (R8-shrunk, no debug overhead) signed with the repository's own debug key (`app/debug.keystore`), like every
other build, so it installs over the previous one (debug or release) and keeps the logins. Every new version replaces the file (and adds its size to the repository's history).
