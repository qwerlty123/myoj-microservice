---
name: cover-graph-structure
description: Cover connectivity, direction, multiplicity, cycles, weights, and graph-shape extremes. Use for graphs, trees, shortest paths, traversal, union-find, topology, and connectivity problems.
---
# Cover graph structure

## DRAFT_SPECIFICATION

- State direction, weight domain, self-loop and parallel-edge policy, connectivity guarantees, and vertex numbering.
- Define unreachable states, path semantics, and whether the input is guaranteed to be a tree or DAG.
- Choose limits consistent with sparse or dense representations and the intended graph algorithm.

## REFERENCE_SOLUTION

- Derive traversal state, relaxation conditions, or component invariants from the exact graph contract.
- Handle disconnected vertices, repeated edges, cycles, and unreachable targets explicitly.
- Size recursion, queues, distance types, and adjacency storage for the worst legal graph shape.

## VALIDATION_PROGRAMS

- Validate endpoints, edge counts, weight bounds, multiplicity rules, and promised structural properties.
- Use exhaustive path or subset methods only for a sharply bounded oracle subset.
- Check tree, DAG, or connectivity promises rather than trusting labels in the request.

## COVERAGE_PLAN

- Cover isolated vertex, disconnected components, chain, star, cycle, dense subgraph, and multiple valid routes.
- Target direction reversal, parallel edges, zero or extreme weights, unreachable targets, and deep traversal.
- Separate structural risks from numeric distance overflow risks.

## TEST_CASE_GENERATION

- Generate recognizable graph shapes whose expected behavior follows from one structural property.
- Encode large chains, stars, or repeated edge patterns compactly when chunks can express them safely.
- Keep oracle cases small enough for exhaustive verification.

## QUALITY_REVIEW

- Verify every graph promise against statement wording, validator behavior, solution assumptions, and cases.
- Check that judge limits tolerate worst-case adjacency storage and traversal depth.
- Name the graph shape that demonstrates each structural defect.
