---
name: cover-sequence-boundaries
description: Cover indexing, ordering, duplication, windows, and degenerate shapes in sequences. Use for arrays, strings, sorting, two pointers, sliding windows, intervals, matrices, and subarray problems.
---
# Cover sequence boundaries

## DRAFT_SPECIFICATION

- Define sequence length, index convention, ordering guarantees, duplicate policy, alphabet, and empty-case legality.
- State whether intervals are closed, open, or half-open and whether rows have equal length.
- Set ranges that expose the intended scan, sort, window, or prefix technique.

## REFERENCE_SOLUTION

- Name the invariant for every pointer, window, prefix, stack, or interval endpoint.
- Preserve behavior for length one, all equal, monotone, and fully overlapping inputs.
- Account for sorting, copying, substring creation, and container operations in complexity.

## VALIDATION_PROGRAMS

- Enforce declared lengths, alphabets, row widths, ordering promises, and interval relations.
- Let the oracle enumerate positions or subsets only inside an explicit small-data limit.
- Parse empty lines and whitespace according to the statement rather than convenience.

## COVERAGE_PLAN

- Cover single element, all equal, strict increase/decrease, alternating values, duplicates, and separated clusters.
- Target off-by-one behavior at both ends and windows that become empty or span the whole sequence.
- Include interval touching, nesting, disjointness, and complete overlap when applicable.

## TEST_CASE_GENERATION

- Build cases with visibly different shapes, not merely different random values.
- Use repetitive chunks for large uniform or periodic inputs and ranges for monotone inputs.
- Attach the exact sequence risk ids exercised by each shape.

## QUALITY_REVIEW

- Check that index and interval semantics remain identical across statement, explanation, and code.
- Compare declared lengths with parser behavior and every supplied case.
- Identify the concrete degenerate shape behind any claimed missing coverage.
