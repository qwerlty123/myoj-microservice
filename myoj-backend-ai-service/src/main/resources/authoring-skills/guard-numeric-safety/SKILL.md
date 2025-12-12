---
name: guard-numeric-safety
description: Guard integer range, operation order, modular arithmetic, and floating-point semantics. Use for math, number theory, prefix sum, binary search, counting, probability, and numeric-limit problems.
---
# Guard numeric safety

## DRAFT_SPECIFICATION

- State integer signs, inclusive bounds, divisibility conditions, modulus, and required precision.
- Ensure the mathematical result and every necessary intermediate value fit a declared representation.
- Define rounding, comparison tolerance, and impossible-state output when floating point is involved.

## REFERENCE_SOLUTION

- Bound intermediate products, sums, negation, midpoint calculations, and least common multiples before choosing types.
- Apply modular reductions in an order that preserves the intended mathematics.
- Use explicit precision and rounding behavior for decimal output.

## VALIDATION_PROGRAMS

- Reject malformed signs, out-of-range numbers, invalid denominators, and broken numeric relations.
- Compute small-data oracle values with a wider or exact representation when practical.
- Keep overflow checks explicit instead of relying on wrapped arithmetic.

## COVERAGE_PLAN

- Cover zero, one, negative values when legal, equal bounds, opposite signs, and values near type limits.
- Target overflow in intermediate operations even when the final result is small.
- Target rounding boundaries, repeated modular normalization, and binary-search endpoint movement.

## TEST_CASE_GENERATION

- Include compact cases isolating one numeric hazard and maximum cases stressing representation limits.
- Mark only genuinely small exact-arithmetic cases as oracle eligible.
- Cite numeric risk ids on the cases designed to expose them.

## QUALITY_REVIEW

- Recompute numeric bounds from the statement and compare them with the solution's chosen types.
- Check that precision language, sample formatting, and judge behavior agree.
- Flag overflow or precision claims only with the triggering expression and legal input range.
