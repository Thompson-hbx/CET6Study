# Pinned FSRS-6 Kotlin engine

Source: https://github.com/bee-san/bee-fsrs/tree/98f3ed75a9b7f3fa7243320721671557908eea2d

Only the nine FSRS-6 sources are included. No FSRS-7, package-repository credentials, or transitive dependencies.
See LICENSE (Autumn Skerritt, MIT) and py-fsrs-LICENSE (Open Spaced Repetition, MIT).
The same notices are packaged in the APK assets and displayed from the word page.

Local changes relative to the pinned sources:

1. `DefaultFsrsEngine.nextState`: long-term stability uses previous difficulty, matching actual py-fsrs v6.3.1 execution; difficulty is updated independently.
2. `DefaultFsrsEngine.nextIntervalDays`: ties-to-even rounding via Math.rint, matching Python round rather than Java half-up rounding.
3. Remove `@JvmRecord` from three data classes for the existing Android API 26 build; data and validation are unchanged.

No other math or parameter changes. Original and integrated hashes are in SOURCE_HASHES.json.
The application pins a policy/engine identity in every new review and schedule; upgrades must not silently reinterpret those records.

Tests:

- `app/src/test/java/dev/bee/fsrs/Json.kt` and `FsrsEngineFixtureTest.kt` derive from the same MIT source. The test uses readAllBytes for Android's compile classpath; assertions are unchanged.
- The original 38-case fixture is retained here for comparison. It was generated from a formula snapshot; it is not treated as an independent oracle.
- `python tools/generate_fsrs_oracle.py` runs the pinned py-fsrs v6.3.1 source and generates the offline fixtures in app/testdata. 31 numeric cases are recalculated; seven Kotlin validation cases are retained. A second fixture contains 30 actual learning/relearning transitions.
- No pip installation is required. A typing.Self alias replaces a type-only typing_extensions.Self import on Python 3.14. Upstream executable source bytes are unchanged; scheduler Git blob is verified.

See FSRS_AUDIT.md for the rejected FSRS-Kotlin candidate, maintenance assessment and migration design.
