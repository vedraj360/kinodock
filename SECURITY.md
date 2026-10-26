# Security and API credentials

## API keys

- Do not put API key values in source files, screenshots, documentation, or commits.
- Keep personal keys in `launcher/local.properties`. The root `.gitignore` excludes this file.
- Release builds force the Unsplash and Currents fields to empty. Do not remove this override for a public release.
- Debug APKs built with local keys contain those keys in the package and can be inspected. Do not share or upload such APKs.
- If a key was committed or otherwise shared publicly, revoke it with its provider and create a replacement. Deleting it from the current file does not remove it from Git history or old APKs.

The Android client cannot securely hide a shared secret from someone who can download and inspect the APK. A release that needs shared API access should use a server-side proxy with rate limits and secret storage, or leave the keyed feature disabled. The current public release build uses the latter approach.

## Reporting

Please avoid posting credentials or personal device details in public issue reports. For now, report security problems through a private channel to the project owner; a public repository should add a private contact method before launch.
