<style>
section {
  font-size: 26px;
}
h1 {
  font-size: 2em;
}
h2 {
  font-size: 1.6em;
}
pre {
  font-size: 0.8em;
}
</style>

# Test Filter

## Intelligent Test Selection for Clojure

---

## The Problem

- Large Clojure codebases have extensive test suites
- Running all tests on every change is slow and expensive
- Traditional approaches:
    - Run all tests → too slow
    - Run only changed test files → misses affected tests
    - Manual test selection → error-prone and tedious

**We need to run only the tests affected by our changes**

---

## The Solution: Test Filter

An intelligent test selection tool that:

1. **Analyzes** your codebase structure and dependencies
2. **Detects** semantic code changes (not just file changes)
3. **Selects** the minimum set of tests that need to run
4. **Tracks** verified code to avoid re-running passing tests

**Key insight**: Use static analysis + content hashing to build a dependency graph, then walk it backwards from tests

---

## Architecture Overview

```
┌─────────────────┐
│   clj-kondo     │  Static Analysis
│  (Analysis)     │  
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Dependency     │  Loom-based Graph
│     Graph       │  (uses relationships)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│   Content       │  SHA256 Hashing
│   Hashing       │  (semantic changes)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│     Test        │  Graph Traversal
│   Selection     │  (affected tests)
└─────────────────┘
```

---

## Step 1: Static Analysis with clj-kondo

**Extract three things:**

1. Variable definitions (`defn`, `def`, etc.)
2. Namespace definitions (`ns`)
3. Usage relationships (who calls what)

```clojure
;; Example: src/app/core.clj
(ns app.core)

(defn helper [x]
  (inc x))

(defn main [x]
  (helper x))
```

**Extracted:**

- Vars: `app.core/helper`, `app.core/main`
- Usage: `app.core/main` → uses → `app.core/helper`

---

## Why clj-kondo?

✓ **Fast** - Analyzes entire codebases in seconds  
✓ **Accurate** - Understands Clojure semantics  
✓ **No execution** - Static analysis, no REPL needed  
✓ **Rich data** - Provides file locations, metadata, types

**Filters to CLJ only** - Ignores `.cljs` files and CLJS side of `.cljc`

---

## Step 2: Building the Dependency Graph

Using the **Loom** graph library:

```clojure
;; Graph edges represent "uses" relationships
A → B means "A uses B"

;; Example graph:
test/foo-test → main/foo → util/helper
→ util/logger
test/bar-test → main/bar
```

**Key properties:**

- Directed graph (edges have direction)
- Transitive traversal (find all dependencies)
- Reverse traversal (find all dependents)

---

## Graph Example

```clojure
;; Code relationships:
(ns app.core-test
  (:require [app.core :as sut]))

(deftest main-test
  (is (= 2 (sut/main 1))))

;; Graph edges:
app.core-test/main-test → app.core/main → app.core/helper
```

**When `app.core/helper` changes:**

- Reverse walk from tests
- Find: `app.core-test/main-test` depends on it
- **Run that test!**

---

## Step 3: Content Normalization

**Problem**: Format changes shouldn't trigger tests

**Solution**: Normalize code before hashing

```clojure
;; Original
(defn foo
  "Does something cool"
  [x]
  ;; Add one to x
  (+ x 1))

;; Normalized (stripped)
(defn foo [x]
  (+ x 1))
```

**Removes:**

- Docstrings
- Comments
- Extra whitespace
- Formatting/indentation

---

## Content Extraction

```clojure
;; Extract function body from file
(defn extract-content [file-path symbol-info]
  (let [{:keys [line end-line]} symbol-info
        lines (read-lines file-path line end-line)]
    ;; Parse as Clojure
    ;; Strip docstrings/comments
    ;; Normalize whitespace
    (normalize-clojure-form lines)))
```

**Benefits:**

- Only logic changes matter
- Refactoring doesn't break cache
- Comments/docs don't trigger tests

---

## Step 4: Content Hashing

**SHA256 hashing** of normalized content

```clojure
(defn compute-hash [normalized-content]
  (-> normalized-content
    str
    (.getBytes "UTF-8")
    (digest/sha-256)
    (codec/hex)))
```

**Result**: `"a3f2c9e4..."`

**Comparison**:

- Current hash vs. last-verified hash
- Changed? → Symbol needs retesting
- Same? → Symbol is verified, skip

---

## The Two-Cache Architecture

### 1. Analysis Cache (`.test-filter-cache.edn`)

- **Ephemeral** - NOT committed to git
- Complete snapshot of current codebase
- Contains: graph structure + current hashes
- Overwritten on every `analyze!`

### 2. Success Cache (`.test-filter-success.edn`)

- **Persistent** - COMMITTED to git
- Baseline of verified symbols
- Maps: `symbol → verified-hash`
- Only updated by `mark-verified!` after tests pass

---

## Cache Architecture Diagram

```
┌─────────────────────────────────────┐
│    Analysis Cache (Current State)   │
│                                     │
│  {:graph {...}                      │
│   :hashes {app/foo "abc123"         │
│            app/bar "def456"}}       │
└──────────────┬──────────────────────┘
               │
               │ Compare
               ▼
┌─────────────────────────────────────┐
│  Success Cache (Verified Baseline)  │
│                                     │
│  {app/foo "abc123"   ; Same! ✓      │
│   app/bar "old999"}  ; Changed! ⚠  │
└─────────────────────────────────────┘

Result: app/bar changed → find tests that depend on it
```

---

## Step 5: Change Detection

**Algorithm:**

```clojure
(defn detect-changes [current-hashes success-hashes]
  (reduce-kv
    (fn [changed sym current-hash]
      (let [verified-hash (get success-hashes sym)]
        (if (= current-hash verified-hash)
          changed                    ; No change
          (conj changed sym))))      ; Changed!
    #{}
    current-hashes))
```

**Result**: Set of changed symbols

- `#{app.core/helper app.util/logger}`

---

## Step 6: Test Selection

**Graph traversal** to find affected tests:

```clojure
(defn select-affected-tests [graph changed-symbols]
  (for [changed-sym changed-symbols
        test-node   (find-tests-using graph changed-sym)]
    test-node))
```

**Traversal strategy:**

1. Start from each changed symbol
2. Walk graph **backwards** (who uses this?)
3. Continue until reaching test nodes
4. Collect all encountered tests

---

## Test Selection Example

```
Changed: app.core/helper

Graph (reverse edges):
app.core/helper ← app.core/main ← app.core-test/main-test
                                ← app.integration-test/full-test
                ← app.util/format ← app.util-test/format-test

Selected tests:
- app.core-test/main-test
- app.integration-test/full-test
- app.util-test/format-test
```

**All tests that transitively depend on the changed symbol**

---

## Integration Test Challenge

**The problem with integration tests:**

```clojure
(deftest full-system-integration-test
  ;; Starts HTTP server
  ;; Connects to database
  ;; Calls 50+ functions
  ;; Tests entire user workflow
  ...)
```

**Graph analysis would show:**

- Test depends on 200+ symbols
- Touches 30+ namespaces
- Any change → always runs this expensive test

**Too conservative!** We need a better approach.

---

## Integration Test Solution: Metadata

**Explicitly declare what an integration test actually validates:**

```clojure
(deftest ^{:integration? true
           :test-targets [app.api/create-user
                          app.api/update-profile
                          app.db/save-user]}
  user-workflow-integration-test
  ;; This test specifically validates the user
  ;; creation and profile update workflow.
  ;; Only run if those specific functions change.
  ...)
```

**Key insight**: The test *uses* many functions, but only *validates* a few key behaviors.

---

## Integration Test Metadata: How It Works

**Detection:**

1. Namespace pattern: `*.integration.*`
2. Metadata: `:integration? true`

**Behavior:**

```clojure
;; WITH metadata
^{:test-targets [app.core/foo app.util/bar]}
→ Run ONLY if foo or bar changed

;; WITHOUT metadata  
^{:integration? true}
→ Always run (conservative fallback)
```

**Trade-off**: Manual annotation for precision vs. automatic for safety

---

## Integration Test Example: Before

```clojure
(ns app.integration.user-test
  (:require [app.api :as api]
            [app.db :as db]
            [app.auth :as auth]
            [app.validation :as val]
            [app.email :as email]))

(deftest complete-user-lifecycle
  ;; Setup: start server, database, etc.
  ;; Test creates user, updates profile, deletes
  ...)
```

**Graph analysis shows dependencies:**

- 15+ namespaces
- 100+ functions
- **Result**: Runs on almost every change (defeats the purpose!)

---

## Integration Test Example: After

```clojure
(ns app.integration.user-test
  (:require [app.api :as api]
            [app.db :as db]
            [app.auth :as auth]
            [app.validation :as val]
            [app.email :as email]))

(deftest ^{:integration? true
           :test-targets [app.api/create-user
                          app.api/update-user
                          app.api/delete-user
                          app.db/save-user
                          app.db/delete-user]}
  complete-user-lifecycle
  ;; Setup: start server, database, etc.
  ;; Test creates user, updates profile, deletes
  ...)
```

**Now runs only when those 5 specific functions change!**

---

## Why This Works

**Integration tests validate behaviors, not implementations:**

```clojure
;; The test calls 100+ functions
(-> (create-user data)           ; Key behavior #1
  (update-profile changes)     ; Key behavior #2  
  (delete-user id))            ; Key behavior #3

;; But it only VALIDATES these behaviors
;; Changes to email formatting? Don't need this test
;; Changes to validation logic? Don't need this test
;; Changes to create-user? YES, run this test!
```

**Developer knows what they're testing** - encode that knowledge in metadata.

---

## Integration Test Metadata Strategy

**When to use `:test-targets`:**

- Clear, focused behavior validation
- Subset of API surface being tested
- Want precision over conservatism

**When to skip metadata:**

- True end-to-end smoke tests
- Tests with unpredictable dependencies
- "Just run it" acceptance tests

**Result**: Flexible system for different test types

---

## Fast Iteration: patch-graph-with-local-changes

**Problem**: Re-analyzing on every edit is slow

**Solution**: Git-aware hash patching

```clojure
(defn patch-graph-with-local-changes [graph]
  (let [uncommitted (git/uncommitted-files)]
    ;; Only re-hash symbols in changed files
    (update-hashes graph uncommitted)))
```

**Workflow:**

1. Analyze once per session
2. Edit code → save
3. Patch graph (fast!)
4. Select tests
5. Repeat step 2-4

---

## Fast Iteration Workflow

```clojure
;; 1. Initial analysis (once)
(def graph (tf/analyze! :paths ["src"]))

;; 2. Fast iteration loop:
(def selection
  (tf/select-tests
    :graph (tf/patch-graph-with-local-changes graph)))

(apply k/run (:tests selection))

;; 3. Mark verified
(tf/mark-verified! selection)

;; Repeat step 2 - no re-analysis!
```

**Speed**: Milliseconds instead of seconds

---

## CLI Integration

```bash
# Full workflow
clojure -M:cli analyze                  # Generate current state
clojure -M:cli select -v                # See what needs testing
clojure -M:cli select -o kaocha | \     # Run affected tests
  xargs clojure -M:test -m kaocha.runner
clojure -M:cli mark-verified            # Update baseline

# Adopting on existing large codebase
clojure -M:cli analyze                  # Analyze everything
clojure -M:cli mark-all-verified        # Skip testing, set baseline
# Now only future changes trigger tests

# Status checking
clojure -M:cli status                   # Cache status
clojure -M:cli clear                    # Clear caches
```

**Output formats**: vars, namespaces, kaocha

---

## CLJC File Support

**Properly handles Clojure Common files:**

```clojure
(ns my-app.utils
  #?(:clj (:import [java.nio.file Paths])))

(defn normalize-path [path]
  #?(:clj  (-> (Paths/get path (into-array String []))
             (.normalize)
             (.toString))
     :cljs (.normalize js/path path)))
```

**Behavior:**

- Analyzes only the `:clj` side of CLJC files
- Ignores pure `.cljs` files completely
- Tracks dependencies correctly across platforms
- Focus on JVM Clojure testing

---

## Multiple Test Framework Support

**Detects tests from various frameworks:**

```clojure
;; clojure.test
(deftest user-registration-test
  (is (= expected (register-user data))))

;; fulcro-spec
(specification "User registration"
  (assertions
    "creates a new user"
    (register-user data) => expected))
```

**Detection methods:**

- `deftest` and `:test` metadata
- Macro usage analysis (e.g., `fulcro-spec.core/specification`)
- Custom patterns (configurable)

---

## CI/CD Integration: Traditional Approach

**Commit success cache to repository:**

```yaml
- name: Analyze codebase
  run: clojure -M:cli analyze

- name: Select affected tests
  run: |
    TESTS=$(clojure -M:cli select -o kaocha)
    if [ -n "$TESTS" ]; then
      echo "$TESTS" | xargs clojure -M:test -m kaocha.runner
    fi

- name: Mark verified
  if: success()
  run: |
    clojure -M:cli mark-verified
    git add .test-filter-success.edn
    git commit -m "Update verified test baseline"
```

---

## CI/CD Integration: GitLab Artifacts

**Alternative: Use CI artifacts instead of commits**

**Develop branch** (runs all tests):

```yaml
test:develop:
  only: [ develop ]
  script:
    - clojure -M:cli analyze
    - clojure -M:test -m kaocha.runner
    - clojure -M:cli mark-verified
  artifacts:
    paths: [ .test-filter-success.edn ]
    expire_in: never
```

**MR branch** (runs selected tests):

```yaml
test:mr:
  only: [ merge_requests ]
  before_script:
    - curl $CI_API/artifacts/develop/.test-filter-success.edn
  script:
    - clojure -M:cli analyze
    - clojure -M:cli select -o kaocha | xargs clojure -M:test
```

**Benefits**: No bot commits, cleaner history, built-in auth

---

## Performance Benefits

**Real-world production project:**

- **5,000 tests** with 20,000+ assertions
- **25 minutes** local runtime (12 minutes in CI with parallelization)
- Many slow integration tests
- Graph generation: **~80 seconds** (one-time per session)
- Patching from changes: **nearly instant**
- Typical edit: triggers **just a few tests** (seconds, not minutes)

**The old workflow:**

- Run immediate tests → pass locally
- Push to CI → integration tests fail
- **Hour+ of back-and-forth** discovering what broke
- CI cycles become expensive waiting games

**With test-filter:**

- Analyze once per session (80 seconds)
- Edit → Save → Patch graph (instant) → Run affected tests
- **Catch integration test failures locally** before CI
- Typical iteration: seconds instead of hours

---

## Accuracy Guarantees

**When test-filter says "run these tests":**

✓ All affected tests are included  
✓ No false negatives (won't miss tests)  
✓ Content-aware (not just file changes)  
✓ Transitive dependencies tracked

**May include extra tests** (conservative):

- Integration tests (when dependencies unclear)
- Tests with global side effects

**Trade-off**: Better to run extra than miss one

---

## Technology Stack

**Core libraries:**

- **clj-kondo** - Fast, accurate static analysis
- **Loom** - Graph algorithms and traversal
- **tools.reader** - EDN parsing for content hashing
- **tools.cli** - Command-line interface

**Why these choices:**

- clj-kondo: Analyzes entire codebases in seconds, no REPL needed
- Loom: Battle-tested graph library with transitive operations
- tools.reader: Parse Clojure code as data structures
- Clojure: The language that makes structural analysis elegant

---

## Data Structures

**Analysis Cache** (`.test-filter-cache.edn`):

```clojure
{:analyzed-at    "2025-01-09T10:30:00Z"
 :paths          ["src/main" "src/test"]
 :nodes          {my.ns/foo {:symbol 'my.ns/foo
                             :type   :var
                             :file   "src/my/ns.clj"
                             :line   42}}
 :edges          [{:from 'my.ns/foo :to 'other.ns/bar}]
 :content-hashes {my.ns/foo "sha256..."}}
```

**Success Cache** (`.test-filter-success.edn`):

```clojure
{my.ns/foo "sha256-of-verified-version..."
 my.ns/bar "sha256-of-verified-version..."}
```

---

## Limitations & Future Work

**Current limitations:**

- Global side effects not tracked (e.g., `alter-var-root`)
- Dynamic code loading not detected
- Macro expansion not fully tracked

**Future improvements:**

- Handle dynamic vars
- Better macro analysis
- Cross-language support (CLJS)
- Parallel test execution hints
- Watch mode for continuous testing
- Coverage-based refinement

---

## Key Takeaways

1. **Static analysis** builds dependency graph (clj-kondo)
2. **Content hashing** detects semantic changes (SHA256)
3. **Graph traversal** finds affected tests (Loom)
4. **Two caches** separate current vs. verified state
5. **Fast iteration** via git-aware patching

**Result**: Run only necessary tests, save time and money

---

## Diagnostic Tools: why? and why-not?

**Understanding test selection with `why?`**

Shows exactly why each test was selected:

```clojure
(def selection (tf/select-tests :verbose true))

(tf/why? selection)
;; app.core-test/process-data-test
;;   app.core/process-data
;;   app.util/transform (changed)
;;
;; app.integration-test/end-to-end
;;   app.api/create-user
;;   app.db/save-user (changed)
```

**Reveals the dependency chain** from test → changed symbol

---

## Finding Coverage Gaps: why-not?

**Identify untested code that depends on changes:**

```clojure
(tf/why-not? selection)
;; === Coverage Gaps: Untested Usages ===
;;
;; app.util/transform (changed)
;;   Direct usages with no test coverage:
;;     app.reports/generate-report
;;     app.export/csv-export
```

**These are risk areas:**

- Code depends on your changes
- No tests will catch breakage
- Consider adding tests before shipping

---

## Demo

```clojure
;; REPL demo
(require '[com.fulcrologic.test-filter.core :as tf])
(require '[kaocha.repl :as k])

;; Analyze
(def graph (tf/analyze! :paths ["src"]))

;; Make a change to code, save it, then:
(def selection
  (tf/select-tests
    :graph (tf/patch-graph-with-local-changes graph)))

;; See what's selected
(tf/print-tests (:tests selection) :format :namespaces)

;; Understand why tests were selected
(tf/why? selection)

;; Find coverage gaps
(tf/why-not? selection)

;; Run them
(apply k/run (:tests selection))

;; Mark verified
(tf/mark-verified! selection)
```

---

## Resources

**Project**: `com.fulcrologic/test-filter`

**Key namespaces:**

- `com.fulcrologic.test-filter.core` - Main API
- `com.fulcrologic.test-filter.analyzer` - clj-kondo integration
- `com.fulcrologic.test-filter.graph` - Dependency graph
- `com.fulcrologic.test-filter.content` - Hashing

**Documentation**: See `CLAUDE.md` in project root

---

## Questions?

Thank you!

**Test Filter** - Run less, test smarter
