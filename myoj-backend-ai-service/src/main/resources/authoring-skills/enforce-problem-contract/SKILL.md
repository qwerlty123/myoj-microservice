---
name: enforce-problem-contract
description: Enforce an executable and internally consistent OJ problem contract. Use for every draft, reference solution, validator, coverage plan, test generation, and quality review.
---
# Enforce the problem contract

## DRAFT_SPECIFICATION

- Define every input token, valid range, cross-field relation, and output rule in the statement.
- Make the data range distinguish the intended algorithm from slower approaches.
- Choose small samples that exercise different semantics and remain easy to verify independently.

## REFERENCE_SOLUTION

- Derive the algorithm from the stated contract and explain its invariant or correctness argument.
- Match time and memory complexity against the largest legal input.
- Parse exactly the documented format and emit only the documented output.

## VALIDATION_PROGRAMS

- Make the validator consume the complete input and enforce ranges plus cross-field relations.
- Make the oracle favor obvious correctness and accept only the small-data subset it can solve safely.
- Keep validator, oracle, and official solutions independent in implementation strategy.

## COVERAGE_PLAN

- Identify semantic partitions, parser boundaries, minimum and maximum sizes, and performance cliffs.
- Give each risk one stable short id that candidate cases can cite.
- Prefer risks capable of distinguishing a plausible wrong solution from the correct one.

## TEST_CASE_GENERATION

- Construct each input from a named risk and assign the most specific required category.
- Keep oracle-eligible cases small; represent maximum cases compactly with chunks.
- Vary structure and semantics instead of changing only literal values.

## QUALITY_REVIEW

- Compare statement, solution, judge configuration, and cases as one executable contract.
- Report a semantic defect only when its conflicting clauses or execution evidence can be named.
- Treat verified execution evidence as stronger than stylistic preference.
