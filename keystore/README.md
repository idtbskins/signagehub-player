# Release Keystore

Generate the release keystore locally and do not commit the `.jks` file.

```bash
mkdir -p keystore
keytool -genkey -v -keystore keystore/signagehub-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias signagehub -storepass signagehub2026 -keypass signagehub2026 \
  -dname "CN=SignageHub, OU=Signage, O=Bravotech, L=Rome, S=Lazio, C=IT"
```
