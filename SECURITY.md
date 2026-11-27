# Security policy

## Reporting a problem

If you find a security problem in Prismo, please report it privately, not in a public issue:

1. Open https://github.com/mihirvr/prismo/security/advisories/new
2. Describe what you found, how to reproduce it, and which version and operating system you used.

Only the maintainer can see the report. You will get an answer as soon as possible; Prismo is a one-person project, so
please allow a few days. Once it is fixed, the fix goes into a new release and the report can be made public, with
credit to you if you like.

## Which versions get fixes

Only the latest release. Please update to it before reporting: https://github.com/mihirvr/prismo/releases/latest

## What counts

- The Prismo app and its installers (Windows `.exe`, Linux `.deb`), as downloaded from the GitHub releases.
- The website at https://prismo.mihirr.in.

Prismo works offline: it reads your files and keeps its own data (the library database, thumbnails, settings) on your
computer. Problems where Prismo could change, delete, upload or expose your files or that data without you asking are
the most important to hear about.

The bundled libraries (FFmpeg, JavaFX, SQLite and the others in `THIRD_PARTY_NOTICES.md`) have their own maintainers. A
problem in one of them is still welcome here, so Prismo can ship an updated version.
