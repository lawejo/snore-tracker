# Snore Tracker

Simpel Android-app, der optager natten, finder snorken og gemmer lydklip, så man kan se og høre dem næste dag.

## Installation på tabletten

1. Åbn https://github.com/lawejo/snore-tracker/releases/latest i Chrome på tabletten.
2. Hent `snore-tracker.apk` og installer den (tillad "Installer ukendte apps" for Chrome første gang).
3. Opdateringer installeres på samme måde, oven i den gamle version. Data bevares.

## Byg

GitHub Actions bygger en signeret APK ved hvert push til `main` og udgiver den som en Release.
Kræver secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Tuning

Hver nat gemmer `scores.csv` med lydniveau og YAMNet-scores. Tærsklerne står øverst i `RecordingService.kt`.
