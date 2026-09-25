# PANCLUB pre-bake Android overlay

The repository's v0.11 carrier restores the historical source package. The active build workflow then copies this readable overlay onto that package before regressions and APK assembly.

This overlay removes the 12 cm market fixture from the production user flow, adds structured user target-pan state and source-pan recovery quarantine, presents Korean consumer results, and adds the dynamic BF-F01 regression matrix. The market fixture and BF-F02 evidence remain available only to their existing regression and future physical-validation lanes.

`SHA256SUMS` is checked before the overlay is applied.

The product-owner-approved `BF-F02 TEST_WAIT Consumer Preview Exception v0.1`
adds a separate, fixture-only Korean preview for the verified Easy Vegan
Vanilla Cake 12 cm × 2 validation target. It does not change BF-F02 data,
production readiness, FIT state, or the fail-closed Pinterest path.

The generalized preview pipeline now evaluates every normal READ session through
separate geometry, technical-eligibility, consumer-authority, and presentation
stages. Property-compatible but unapproved recipes stop at authority-required
Korean copy with no ingredient output. The Easy Vegan fixture remains the only
authorized case, and only for the approved 12 cm × 2 target. Its launcher is
available only inside diagnostics; it uses the same generalized session path.
The physical-phone debug entry is `Easy Vegan 검증 예시 열기`; its visibility
requires an expanded `INTERNAL_DIAGNOSTIC` view and it delegates to the normal
`PreBakeSession` target evaluation rather than a fixture-specific presenter.

Pinterest Pin identity mismatches remain fail-closed unless `PinIdentityResolver`
finds an expected-ID-bound first-party Pin record that explicitly names the page
Pin as its `canonicalPin`, with every requested-record SEO identity and every
page `og:url`/canonical identity agreeing. Related, recommended, visually similar,
and otherwise adjacent Pin records are never identity evidence. The observed
`211174976645508` to `567523990553474836` case satisfies that narrow proof; the
policy remains `EVIDENCE_WAIT_NOT_PRODUCTION_ENABLED`.

The live alias extractor now keys on the exact strict completed-request function
call at the start of a script rather than Pinterest's optional HTML marker, and
locates a renamed response operation only at its shallow primary `.data` Pin.
The request variable and primary entity must still equal the requested Pin, at
least one requested record must explicitly bind `canonicalPin`, and all record
and page canonical identities must agree. Canonical or `og:url` metadata alone
remains insufficient.
