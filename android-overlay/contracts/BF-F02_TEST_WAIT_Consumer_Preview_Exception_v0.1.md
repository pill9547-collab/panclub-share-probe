# BF-F02 TEST_WAIT Consumer Preview Exception v0.1

Status: **PRODUCT-OWNER APPROVED NARROW EXCEPTION**.

This exception permits a separate Korean consumer preview only for the verified **Easy Vegan Vanilla Cake — The Curious Chickpea**, selected **Two 6-inch cakes** fixture, initially targeting round **12 cm × 2**.

The underlying BF-F02 result remains `TEST_WAIT`, `fitApplied=false`, and `productionReady=false`. The preview must say **실제 베이크 검증 전** and must not be represented or exported as production-validated, bake-validated, execution-authorized, or a final kitchen recipe.

Consumer presentation may round the unchanged calculated decimal to at most two decimal places with `HALF_UP`, remove unnecessary trailing zeroes, and retain `g`. This is display formatting only. Exact source masses, exact rational results, and full internal decimal results remain preserved in diagnostics.

This exception does not authorize arbitrary-recipe preview access, cup-to-gram conversion, egg-mass assumptions, frosting scaling, bake-time scaling, pan merge/split, Korean 호-to-cm inference, production FIT, executable worksheet export, or promotion of BF-F02. Pin `559290847491964725` and other unsupported recipes remain fail-closed.
