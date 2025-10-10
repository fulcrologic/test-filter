# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Test Filter is an intelligent test selection tool for Clojure projects that uses static analysis (clj-kondo) and content-hash comparison to determine the minimum set of tests needed after code changes. It builds a symbol dependency graph and walks it backwards from tests to find what needs re-running.

**Important**: This codebase and documentation was completely written by Claude Code. The original author has not verified all functionality.

### Two-Cache Architecture

1. **Analysis Cache** (`.test-filter-cache.edn`) - Ephemeral, NOT committed
   - Snapshot of current codebase structure and content hashes
   - Completely overwritten on each `analyze!` command
   - Used to communicate between CLI steps in same session

2. **Success Cache** (`.test-filter-success.edn`) - Persistent, COMMITTED
   - Contains content hashes of successfully verified symbols
   - Only updated by `mark-verified!` after tests pass
   - Persists across sessions and builds
   - This is the baseline for detecting changes

## Common Commands

### Development & Testing
```bash
# Start a REPL with dev environment
clojure -M:dev

# Run all tests
clojure -M:test -m kaocha.runner

# Analyze the codebase (overwrites analysis cache)
clojure -M:cli analyze

# Select affected tests (compares current vs success cache)
clojure -M:cli select -v

# Run only affected tests
clojure -M:cli select -o kaocha | xargs clojure -M:test -m kaocha.runner

# Mark tests as verified (updates success cache)
clojure -M:cli mark-verified

# Check cache status
clojure -M:cli status

# Clear caches
clojure -M:cli clear          # Clear analysis cache only
clojure -M:cli clear --all    # Clear both caches
```

### REPL-Based Development
The `src/dev/user.clj` namespace provides helper functions:
```clojure
(require '[com.fulcrologic.test-filter.core :as tf])

;; 1. Analyze - generates current state
(tf/analyze! :paths ["src/main" "src/demo" "src/test"])

;; 2. Select affected tests (compares current vs success cache)
(def selection (tf/select-tests :verbose true))

;; View selection details
selection
;; => {:tests [...]
;;     :changed-symbols #{...}
;;     :changed-hashes {...}
;;     :graph {...}
;;     :stats {...}}

(tf/print-tests (:tests selection) :format :namespaces)

;; 3. Run tests using Kaocha REPL integration
(require '[kaocha.repl :as k])
(apply k/run (:tests selection))

;; 4. Mark as verified after tests pass
(tf/mark-verified! selection)           ; Mark all selected tests
(tf/mark-verified! selection [test-1])  ; Mark specific tests
```

## Architecture

### Core Components

1. **Analyzer** (`com.fulcrologic.test-filter.analyzer`)
   - Uses clj-kondo to extract var definitions, namespace definitions, and usage relationships
   - Filters to CLJ-only (excludes CLJS files and CLJS side of CLJC files)
   - Detects tests via `deftest` and custom macros (e.g., `fulcro-spec.core/specification`)
   - Extracts test metadata for integration test handling

2. **Graph** (`com.fulcrologic.test-filter.graph`)
   - Builds directed dependency graph using Loom library
   - Models "uses" relationships: `A -> B` means "A uses B"
   - Provides transitive dependency walking to find what depends on what
   - Special handling for integration tests (namespace pattern `*.integration.*`)

3. **Content** (`com.fulcrologic.test-filter.content`)
   - Extracts function source code from files
   - Normalizes content (strips docstrings, formatting, whitespace)
   - Generates SHA256 hashes for semantic comparison
   - Ignores cosmetic changes (docstrings, formatting, comments)

4. **Cache** (`com.fulcrologic.test-filter.cache`)
   - **Analysis Cache**: Ephemeral snapshot of current codebase state
     - Completely overwritten on each analyze
     - Contains graph structure and current content hashes
   - **Success Cache**: Persistent baseline of verified symbols
     - Only updated by `mark-verified!`
     - Maps symbols to their last-verified content hashes

5. **Core** (`com.fulcrologic.test-filter.core`)
   - Main test selection algorithm coordinating all components
   - Provides `analyze!`, `select-tests`, and `mark-verified!` public API
   - Compares current hashes vs success cache to detect changes

6. **Git** (`com.fulcrologic.test-filter.git`)
   - Optional: Can be used to detect which files changed
   - Not required for core functionality (content hashing handles change detection)

7. **CLI** (`com.fulcrologic.test-filter.cli`)
   - Commands: analyze, select, mark-verified, status, clear
   - Output formats: vars, namespaces, kaocha
   - Integration with external test runners

### Data Flow

```
1. Analyze Phase:
   clj-kondo → Analyzer → Symbol Graph → Content Hashing
   → Analysis Cache (.test-filter-cache.edn)

2. Selection Phase:
   Load Analysis Cache (current hashes)
   Load Success Cache (verified hashes)
   Compare Hashes → Changed Symbols
   Graph Traversal → Affected Tests
   Return Selection Object

3. Verification Phase:
   User runs tests (external)
   mark-verified! → Update Success Cache with current hashes
```

### Key Data Structures

**Symbol Node**:
```clojure
{:symbol 'my.ns/foo
 :type :var                    ; or :namespace or :test
 :file "src/my/ns.clj"
 :line 42
 :end-line 47
 :defined-by 'defn             ; or 'clojure.test/deftest
 :metadata {:test? false
            :integration? false
            :test-targets #{...}}}  ; explicit test dependencies
```

**Dependency Edge**:
```clojure
{:from 'my.ns/foo   ; nil if top-level
 :to 'other.ns/bar
 :file "src/my/ns.clj"
 :line 45}
```

**Selection Object** (returned by `select-tests`):
```clojure
{:tests [my.app-test/foo-test my.app-test/bar-test]
 :changed-symbols #{my.app/foo my.app/baz}
 :changed-hashes {my.app/foo "sha256..." 
                  my.app/baz "sha256..."}
 :graph {...}  ; Full dependency graph
 :stats {:total-tests 12
         :selected-tests 2
         :changed-symbols 2}}
```

## Project Structure

```
src/
  main/com/fulcrologic/test_filter/
    core.clj      - Main API (analyze!, select-tests)
    analyzer.clj  - clj-kondo integration
    graph.clj     - Loom-based graph operations
    git.clj       - Git operations
    cache.clj     - Persistence layer
    cli.clj       - Command-line interface

  dev/
    user.clj      - REPL helpers

  test/com/fulcrologic/test_filter/
    *_test.clj    - Unit tests
    integration/  - Integration tests

  demo/          - Demo/example code

.test-filter-cache.edn    - Analysis cache (ephemeral, gitignored)
.test-filter-success.edn  - Success cache (persistent, committed)
```

## Important Implementation Details

### Cache Management

**Analysis Cache** (`.test-filter-cache.edn`):
- Ephemeral snapshot of current codebase
- Should be in `.gitignore`
- Completely overwritten on each `analyze!`
- Contains: graph structure, current content hashes, analyzed paths

**Success Cache** (`.test-filter-success.edn`):
- Persistent baseline of verified symbols
- Should be COMMITTED to git
- Only updated by `mark-verified!` after tests pass
- Contains: map of symbol -> verified content hash
- This is what determines if tests need to run

### CLJC File Handling
Only the `:clj` side of CLJC files is analyzed. Pure `.cljs` files are completely ignored. This is handled in `extract-var-definitions`, `extract-namespace-definitions`, and `extract-var-usages` in analyzer.clj.

### Test Detection
- Standard: Vars defined with `clojure.test/deftest` or having `:test` metadata
- Macro-based: Tests using `fulcro-spec.core/specification` (detected via var-usages)
- Integration tests: Detected by `*.integration.*` namespace pattern

### Integration Test Strategy
Tests marked as integration tests (`:integration? true` metadata or namespace pattern) are handled specially:
- If `:test-targets` metadata exists: run only if those symbols changed
- Otherwise: run conservatively (always run, since dependencies are broad)

### Content-Based Change Detection

The system uses SHA256 hashing of normalized code to detect changes:

1. **Normalization**: Strips docstrings, comments, and normalizes whitespace
2. **Hashing**: Generates SHA256 hash of normalized content
3. **Comparison**: Current hash vs. success cache hash
4. **Result**: Only actual logic changes trigger tests

This means:
- ✓ Formatting changes don't trigger tests
- ✓ Docstring changes don't trigger tests  
- ✓ Comment changes don't trigger tests
- ✓ Only semantic/logic changes trigger tests

## Testing Philosophy

Test namespaces follow the pattern `com.fulcrologic.test-filter.*-test` for unit tests, with integration tests under `*.integration.*`.

The project uses Kaocha as the test runner. The demo tests in `src/demo/` can be used to verify test selection logic.

## Dependencies

- `clj-kondo` - Static analysis of Clojure code
- `loom` - Graph library for dependency walking
- `tools.cli` - Command-line parsing
- `kaocha` (test alias) - Test runner
- `fulcro-spec` (test alias) - Alternative test framework support
