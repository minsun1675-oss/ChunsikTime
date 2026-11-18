# TTOBAGI v0.3 Android release signing

The package ID stays `com.chunsik.study`. Version 0.3.0 uses versionCode 3.

## Important

- Do not commit the .jks keystore file, passwords, or base64 keystore contents to the repository.
- Back up the original .jks keystore and passwords outside GitHub in a secure location.
- GitHub Actions always builds an unsigned-for-updates **debug APK** for testing.
- A signed release build runs only once the following four GitHub Actions repository secrets are configured:
  - `TTB_KEYSTORE_BASE64`: base64 content of the keystore file (no newlines)
  - `TTB_KEYSTORE_PASSWORD`: keystore password
  - `TTB_KEY_ALIAS`: key alias, e.g. `ttobagi`
  - `TTB_KEY_PASSWORD`: private key password
- Configure under Settings > Secrets and variables > Actions > New repository secret.

### Create a new project signing key locally (once)

Use a trusted local machine and Java's keytool. Save the keystore securely.

```sh
keytool -genkeypair -v -keystore ttobagi-release.jks -alias ttobagi -keyalg RSA -keysize 3072 -validity 10000
```

Encode the JKS file as a single-line base64 string for the `TTB_KEYSTORE_BASE64` secret. Never put the result in Git, chat screenshots, logs, or issue comments.

**Migration caveat:** A previously installed debug APK signed with a different key cannot be updated directly by the new signed release. Back up study records to an external JSON file before uninstalling the old APK, then reinstall the signed release and restore records. After that, updates signed using the *same original key* preserve installed app data.
