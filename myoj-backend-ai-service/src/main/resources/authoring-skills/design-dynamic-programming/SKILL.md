---
name: design-dynamic-programming
description: Sharpen DP state, transitions, initialization, reachability, order, and reconstruction. Use for dynamic programming, knapsack, memoization, interval DP, digit DP, tree DP, and backtracking problems.
---
# Design dynamic programming

## DRAFT_SPECIFICATION

- Define state-driving dimensions and constraints that make the intended DP feasible.
- Clarify whether choices are ordered, reusable, capacity-limited, or required to be nonempty.
- Specify impossible states, tie-breaking, counting modulus, and reconstruction output.

## REFERENCE_SOLUTION

- State the meaning of each DP cell before writing its transition.
- Prove initialization, transition completeness, evaluation order, and final-state extraction.
- Separate unreachable sentinels from valid zero values and size memory for the largest dimensions.

## VALIDATION_PROGRAMS

- Enforce dimension, capacity, value, and dependency bounds used by the DP proof.
- Let the oracle enumerate choices or recurse with memoization only on a safe small subset.
- Use exact comparison for reconstruction and counting rules defined by the statement.

## COVERAGE_PLAN

- Cover empty-prefix behavior, smallest capacity, unreachable target, exact fit, multiple optima, and transition-order hazards.
- Target zero-value items, duplicate choices, reuse direction, sentinel collision, and reconstruction ties.
- Include dimensions just across a boundary where initialization or rolling-array order changes behavior.

## TEST_CASE_GENERATION

- Build small cases where the full state table can be reasoned about and maximum cases stressing dimensions.
- Distinguish 0/1, unbounded, grouped, interval, tree, and digit-state semantics with dedicated cases.
- Attach DP risk ids only when the constructed state path actually exercises them.

## QUALITY_REVIEW

- Compare the stated state meaning with initialization, loop order, transitions, and selected final state.
- Verify that complexity and memory match every declared dimension and value range.
- Demonstrate DP defects with a reachable state or legal choice sequence rather than a vague concern.
