# Build status

- Source implementation: COMPLETE for v0.1 scope
- Unit tests: PASS
- Debug APK build: PASS
- GitHub Actions run: #25
- Artifact: StoreGuardHub-v0.1-debug
- APK SHA-256: `e5df02926ca01df6225b347c397a90f7b7442d42eda4680c6692a6d279dda3d2`

## First real-device milestone

1. Install the debug APK.
2. Enable StoreGuard notification access for DMSS collection.
3. Enable StoreGuard accessibility service for ANSI POS screen collection.
4. Open the ANSI POS sales list and confirm at least one transaction is captured.
5. Trigger one DMSS notification and confirm one CCTV event is captured.

The current v0.1 build validates the collection/time-alignment foundation. Visitor tracking and anomaly review are intentionally deferred until the real-device collection path is verified.
