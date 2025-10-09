# Test Filter - Intelligent Test Selection Based on Source Analysis

## Problem Statement

When working on large Clojure projects, running the entire test suite on every change is time-consuming and inefficient. We want to run only the tests that could be affected by source code changes.

### Core Approach
1. Build a **source graph database** of fully-qualified symbols showing "uses" relationships
2. Store this graph with the **git revision** it was built from
3. On subsequent runs, use **git diff** to identify changed lines/files
4. **Walk the dependency graph backwards** from tests to find transitive dependencies
5. Run only tests whose transitive dependencies include changed code

### Example Flow
```
Source Change: modify function `foo/bar` at line 42
↓
Consult Graph: which symbols use `foo/bar`?
↓
Transitive Walk: `baz/qux` uses `foo/bar`, `app/handler` uses `baz/qux`, `app-test/handler-test` tests `app/handler`
↓
Test Selection: run `app-test/handler-test` (and any other tests that transitively depend on `foo/bar`)
```

## Key Decisions & Research

### ✅ Source Analysis Library: clj-kondo
**Decision: Use clj-kondo for source analysis**

Rationale:
- Already in deps.edn
- Fast, production-ready static analyzer
- Exports rich analysis data including:
  - `var-definitions`: all defined vars with location, namespace, metadata
  - `var-usages`: all var usages with caller context (`:from-var`)
  - `namespace-definitions` and `namespace-usages`
- Provides line/column ranges for precise change detection
- No macroexpansion needed (static analysis)
- Handles `.clj` and `.cljc` files (`:clj` side)

Alternative Considered:
- `tools.analyzer`: Lower-level, requires macroexpansion, used as foundation for other tools
- Verdict: clj-kondo is higher-level, faster, and sufficient for our needs

### Graph Libraries to Consider
- `loom`: Clojure graph library with good traversal algorithms
- `ubergraph`: More feature-rich, supports attributes on edges/nodes
- `clojure.set`: Built-in, might be sufficient for simple graph operations

### Git Integration
- `clj-jgit`: JGit wrapper for Clojure
- Shell out to `git` command: simpler, fewer dependencies
- Decision: Start with shell git, add library if needed

## Architecture

### Data Model

#### Symbol Node
```clojure
{:symbol 'my.ns/foo          ; fully-qualified symbol
 :type :var                  ; or :namespace
 :file "src/my/ns.clj"
 :line 42
 :end-line 47
 :defined-by 'defn           ; defn, deftest, def, etc.
 :metadata {:private false
            :macro false
            :test? false}}
```

#### Uses Edge
```clojure
{:from 'my.ns/foo
 :to 'other.ns/bar
 :context 'my.ns/foo}        ; which var contains this usage
```

#### Source Graph Database
```clojure
{:revision "abc123def456"    ; git commit SHA
 :analyzed-at #inst "2024-..."
 :nodes {symbol -> node-data}
 :edges [{:from :to :context}]
 :files {"src/my/ns.clj" {:symbols [...]
                          :revision "abc123"}}}
```

### Components

1. **Analyzer** - Use clj-kondo to build source graph
2. **Graph Builder** - Transform clj-kondo output into graph database
3. **Change Detector** - Use git diff to find changed lines
4. **Test Selector** - Walk graph to find affected tests
5. **Cache Manager** - Persist/load source graph, handle incremental updates

## Implementation Plan

### Phase 1: Foundation ✅ COMPLETE (2025-10-09)
- [x] Research source analysis libraries
- [x] Research graph libraries
- [x] Design data model
- [x] Set up project structure
  - [x] Create core namespaces
  - [x] Add required dependencies
  - [x] Set up dev environment

### Phase 2: clj-kondo Integration ✅ COMPLETE (2025-10-09)
- [x] Implement clj-kondo analyzer wrapper
  - [x] Run clj-kondo with analysis output
  - [x] Parse EDN/JSON output
  - [x] Filter to :clj files only (handle .cljc)
- [x] Transform analysis to graph nodes
  - [x] Extract var-definitions as nodes
  - [x] Extract namespace-definitions as nodes
  - [x] Identify test vars (`:defined-by 'clojure.test/deftest`)
- [x] Transform analysis to graph edges
  - [x] Use var-usages to create "uses" edges
  - [x] Track `:from-var` for context
  - [x] Handle namespace requires/imports

### Phase 3: Graph Operations ✅ COMPLETE (2025-10-09)
- [x] Choose and integrate graph library (loom)
- [x] Build directed graph from nodes/edges
- [x] Implement graph traversal
  - [x] Backward walk from test (find all dependencies)
  - [x] Transitive closure of "uses" relationships
- [x] Implement test identification
  - [x] Find all test vars (`:defined-by 'clojure.test/deftest`)
  - [x] Support custom test identification (e.g., fulcro-spec via :test metadata)

### Phase 4: Git Integration ✅ COMPLETE (2025-10-09)
- [x] Implement git operations
  - [x] Get current git revision
  - [x] Get git diff between revisions
  - [x] Parse diff to extract changed files and line ranges
- [x] Map changes to symbols
  - [x] Match changed lines to symbol definitions
  - [x] Handle deleted/added symbols
  - [x] Track file renames

### Phase 5: Cache & Incremental Updates ✅ COMPLETE (2025-10-09)
- [x] Implement graph persistence
  - [x] Serialize graph to EDN
  - [x] Save with git revision metadata
  - [x] Load cached graph
- [x] Implement incremental updates
  - [x] Diff current vs cached revision
  - [x] Re-analyze only changed files
  - [x] Update graph incrementally
  - [x] Garbage collect removed symbols

### Phase 6: Test Selection Logic ✅ COMPLETE (2025-10-09)
- [x] Implement main test selection algorithm
  - [x] Load/build source graph
  - [x] Detect changes since last revision
  - [x] Find changed symbols
  - [x] Walk graph backwards from all tests
  - [x] Collect tests with changed dependencies
- [x] Handle edge cases
  - [x] New files (no cache entry)
  - [x] Deleted files
  - [x] Namespace renames
  - [x] All tests if analysis cache missing

### Phase 7: CLI & Integration ✅ COMPLETE (2025-10-09)
- [x] Build command-line interface
  - [x] Analyze command (build cache)
  - [x] Select command (output test list)
  - [x] Status command (cache info)
  - [x] Clear command (invalidate cache)
  - [x] Options (force rebuild, verbose, etc.)
- [x] Output formats
  - [x] List of test namespaces
  - [x] List of test vars
  - [x] Integration with test runners (Kaocha, cognitect test-runner)

### Phase 8: Testing & Refinement ⏸️ NEXT
- [ ] Write tests for each component
  - [ ] Cache persistence tests
  - [ ] Incremental update tests
  - [ ] Core test selection tests
  - [ ] CLI tests
- [ ] Test on real Clojure projects
  - [ ] Test on this project itself
  - [ ] Test on larger projects
- [ ] Performance optimization
- [ ] Documentation
  - [ ] Usage guide
  - [ ] API documentation
  - [ ] Examples
  - [ ] CI/CD integration guide

## Dependencies to Add

```clojure
{:deps {org.clojure/clojure {:mvn/version "1.11.0"}
        clj-kondo/clj-kondo {:mvn/version "2025.06.05"} ; already present, may need update
        
        ;; Graph library (choose one)
        aysylu/loom {:mvn/version "1.0.2"}
        ;; OR ubergraph/ubergraph {:mvn/version "0.8.2"}
        
        ;; Data processing
        org.clojure/data.json {:mvn/version "2.5.0"} ; if using JSON output
        
        ;; Git (optional, can shell out)
        ;; clj-jgit/clj-jgit {:mvn/version "1.0.2"}
        }}
```

## Known Challenges & Solutions

### Challenge 1: Macro-Based Test Frameworks (e.g., fulcro-spec)

**Problem:** Test frameworks like fulcro-spec use macros (e.g., `specification`) instead of `deftest`. clj-kondo doesn't create var-definitions for these macro calls, so they won't be detected as tests by our current approach.

**Analysis:**
- clj-kondo records `specification` calls in `:var-usages`, not `:var-definitions`
- The `:lint-as` config option tells clj-kondo how to lint macros but doesn't create var-definitions
- clj-kondo hooks (custom analysis extensions) could help, but require complex configuration

**Proposed Solutions:**

1. **Detect via var-usages (RECOMMENDED)**
   - Look for calls to known test-defining macros in `:var-usages`
   - Configurable list: `#{fulcro-spec.core/specification}`
   - Extract test name from the call location
   - Create pseudo-test-vars from these usages
   
   ```clojure
   (defn find-macro-tests
     "Find tests defined by macros like specification."
     [analysis test-macros]
     (let [var-usages (get-in analysis [:analysis :var-usages])
           macro-calls (filter #(contains? test-macros 
                                          (symbol (str (:to %)) (str (:name %)))) 
                              var-usages)]
       (map (fn [{:keys [from filename row end-row]}]
              {:symbol (symbol (str from) "specification-test")
               :type :test
               :file filename
               :line row
               :end-line end-row
               :defined-by 'macro-test})
            macro-calls)))
   ```

2. **Configuration-based approach**
   - Add `.test-filter.edn` config file
   - Allow users to specify custom test patterns:
     ```clojure
     {:test-macros #{fulcro-spec.core/specification
                     expectations/expect}
      :test-metadata #{:test :integration}}
     ```

3. **Heuristic: namespace pattern matching**
   - Any namespace ending in `-test` or `-spec` is treated as containing tests
   - Conservative but catches everything
   - May have false positives (tests with no changes still run)

**Recommendation:** Start with solution #1 (var-usages detection) with hardcoded support for `fulcro-spec.core/specification`, then add configuration support in #2.

---

### Challenge 2: CLJC File Support

**Problem:** Current implementation may not properly handle `.cljc` (Clojure Common) files that contain both Clojure and ClojureScript code.

**Analysis:**
- Our current code doesn't explicitly filter CLJC files
- clj-kondo analyzes CLJC files and returns analysis for both `:clj` and `:cljs` platforms
- We should process the `:clj` side of CLJC files
- Current project has 0 CLJC files (all are `.clj`)

**Proposed Solutions:**

1. **Explicit CLJC support (RECOMMENDED)**
   - clj-kondo already handles CLJC files correctly
   - Our analyzer doesn't filter by extension, so CLJC files are already included
   - Add test coverage to verify CLJC handling
   - Document that we analyze the `:clj` side only

2. **Platform-aware analysis**
   - For CLJC files with reader conditionals, clj-kondo provides platform-specific analysis
   - We could add logic to handle `#?(:clj ...)` vs `#?(:cljs ...)` blocks
   - Currently not needed since we only care about CLJ

**Recommendation:** CLJC files should already work. Add a test with a CLJC file to verify, and document the behavior.

**Status:** ✅ Likely already working, needs verification

---

### Challenge 3: Integration Tests with Broad Dependencies

**Problem:** Integration tests often require the entire system but only test a small part:

```clojure
(ns app.integration.api-test
  (:require [clojure.test :refer [deftest]]
            [app.system :as system]      ; Requires EVERYTHING
            [app.database :as db]        ; Requires EVERYTHING  
            [app.api.users :as users]))  ; Actual target under test

(deftest test-user-creation
  (let [sys (system/start)]
    ;; Only testing users/create-user
    (is (users/create-user sys {:name "Bob"}))))
```

**Analysis:**
- Dependency graph shows test depends on entire system
- ANY change would trigger this test (false positives)
- But we only care about changes to `app.api.users`
- This is the hardest problem to solve correctly

**Proposed Solutions:**

1. **Call-site analysis (RECOMMENDED for accuracy)**
   - Enhance analysis to track actual function calls in test body
   - Use clj-kondo's `:var-usages` with `:from-var` to see what the test function actually calls
   - Build "shallow dependencies" (direct calls) vs "deep dependencies" (transitive requires)
   - For integration tests, use shallow dependencies only
   
   ```clojure
   (defn find-direct-calls
     "Find functions directly called within a test var."
     [analysis test-var]
     (let [var-usages (get-in analysis [:analysis :var-usages])
           ;; Filter to calls FROM this test var
           direct-calls (filter #(= (:from-var %) test-var) var-usages)]
       (map :to direct-calls)))
   ```

2. **Annotation-based targeting**
   - Add metadata to tests to declare what they actually test:
     ```clojure
     (deftest ^{:test-target app.api.users/create-user} test-user-creation
       ...)
     ```
   - Read metadata from clj-kondo analysis
   - Only trigger test if target changes
   - Requires developer discipline

3. **Convention-based detection**
   - Detect integration tests by namespace pattern: `*.integration.*`
   - Apply different rules: use shallow dependencies only
   - Could also use `:integration` metadata on test

4. **Configuration file approach**
   - External `.test-filter.edn`:
     ```clojure
     {:integration-tests
      {app.integration.api-test/test-user-creation
       {:targets #{app.api.users/create-user}}}}
     ```
   - Most explicit but requires manual maintenance

5. **Hybrid approach (BEST)**
   - Combine multiple strategies:
     1. Check for `:test-target` metadata first (explicit)
     2. If test namespace matches `*.integration.*`, use call-site analysis (automatic)
     3. Otherwise, use full transitive dependency analysis (default)
   - This handles the common cases automatically while allowing overrides

**Recommendation:** 
- **Phase 1:** Implement convention-based detection (#3) - BEST for real-world integration tests
- **Phase 2:** Add metadata support (#2) - allow explicit targeting
- **Phase 3:** Add config file (#4) - for complex cases
- ~~**Phase 1:** Call-site analysis (#1)~~ - SKIP: Doesn't work for REST/protocol-based tests

**Why convention-based is best:**
- REST API tests interact via HTTP, not direct function calls
- WebSocket tests, gRPC tests, etc. have same issue
- Call-site analysis only sees `http/get`, `start-server`, etc. - not the actual business logic being tested
- Convention (`*.integration.*` namespaces) + metadata override is pragmatic and works for all test types
- Developers already follow naming conventions for integration tests

**Implementation notes:**
- Detect integration test namespaces by pattern: `#"\.integration\."` 
- For integration tests: run ONLY if no changes (conservative), OR use metadata/config to specify targets
- For unit tests: use full transitive dependency analysis (current behavior)
- Metadata example: `^{:test-targets #{app.api.users/create-user app.api.users/delete-user}}`

---

## Open Questions & Future Enhancements

### Open Questions
1. ~~Should we track macro expansions?~~ → Use var-usages to detect macro-based tests
2. How to handle dynamic requires? (Track conservatively - assume dependency)
3. Should we support ClojureScript? (Not initially, per requirements)
4. ~~How granular should change detection be?~~ → Line-level for changes, call-site level for integration tests
5. Should we support custom test runners beyond clojure.test? → Yes, via configuration

### Future Enhancements
- Integration with CI/CD systems
- Visualization of dependency graph
- ~~Heuristics for macro-heavy code~~ → Addressed via var-usages detection
- Support for test.check generative tests
- Parallel test execution planning
- Coverage-based refinement
- ~~Support for other test frameworks (midje, expectations)~~ → Addressed via configurable test macros
- Performance benchmarking on large codebases
- Watch mode for continuous testing

## Success Criteria

The tool successfully:
1. ✅ Analyzes Clojure source to build symbol dependency graph
2. ✅ Caches graph with git revision
3. ✅ Detects changes between git revisions
4. ✅ Identifies minimum set of tests to run
5. ✅ Updates cache incrementally
6. ✅ Integrates with existing test runners

Stretch goals:
- Reduces test execution time by >50% on incremental changes
- Handles large codebases (100k+ LOC) efficiently
- Zero false negatives (never skips a test that should run)
