# PANCLUB pre-bake Android overlay

The repository's v0.11 carrier restores the historical source package. The active build workflow then copies this readable overlay onto that package before regressions and APK assembly.

This overlay removes the 12 cm market fixture from the production user flow, adds structured user target-pan state and source-pan recovery quarantine, presents Korean consumer results, and adds the dynamic BF-F01 regression matrix. The market fixture and BF-F02 evidence remain available only to their existing regression and future physical-validation lanes.

`SHA256SUMS` is checked before the overlay is applied.
