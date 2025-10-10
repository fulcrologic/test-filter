# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Test Filter is an intelligent test selection tool for Clojure projects that uses static analysis (clj-kondo) and git history to determine the minimum set of tests needed after code changes. It builds a symbol dependency graph and walks it backwards from tests to find what needs re-running.

**Important**: This codebase and documentation was completely written by Claude Code. The original author has not verified all functionality.

## Common Commands

### Development & Testing
```bash
# Start a REPL with dev environment
clojure -M:dev

# Run all tests
clojure -M:test -m kaocha.runner

# Analyze the codebase and build dependency cache
clojure -M:cli analyze

# Select affected tests (verbose mode shows stats)
clojure -M:cli select -v

# Run only affected tests
clojure -M:cli select -o kaocha | xargs clojure -M:test -m kaocha.runner

# Check cache status
clojure -M:cli status

# Clear the cache
clojure -M:cli clear
```

### REPL-Based Development
The `src/dev/user.clj` namespace provides helper functions:
```clojure
(require '[com.fulcrologic.test-filter.core :as tf])

;; Full analysis with verbose output
(tf/analyze! :paths ["src/main" "src/demo" "src/test"])

;; Select affected tests
(def result (tf/select-tests :verbose true))

;; View results
(tf/print-tests (:tests result) :format :namespaces)
(:stats result)

;; Run tests using Kaocha REPL integration
(require '[kaocha.repl :as k])
(apply k/run (:tests result))
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

3. **Git** (`com.fulcrologic.test-filter.git`)
   - Wraps git commands for revision comparisons
   - Parses unified diff format to extract changed line ranges
   - Maps line changes to symbol changes using the graph
   - Supports partial SHAs, branch names, tags, and relative refs (HEAD~3)

4. **Cache** (`com.fulcrologic.test-filter.cache`)
   - Persists graph to `.test-filter-cache.edn` with git revision
   - Supports incremental updates (re-analyze only changed files)
   - Cache invalidation when revision changes

5. **Core** (`com.fulcrologic.test-filter.core`)
   - Main test selection algorithm coordinating all components
   - Smart revision detection: uncommitted changes vs. committed changes
   - Provides `analyze!` and `select-tests` public API

6. **CLI** (`com.fulcrologic.test-filter.cli`)
   - Commands: analyze, select, status, clear
   - Output formats: vars, namespaces, kaocha
   - Integration with external test runners

### Data Flow

```
1. Analyze Phase:
   clj-kondo → Analyzer → Symbol Graph → Cache (.test-filter-cache.edn)

2. Selection Phase:
   Git Diff → Changed Symbols → Graph Traversal → Affected Tests

3. Smart Revision Detection:
   - If uncommitted changes: compare HEAD to working directory
   - If no uncommitted changes: compare HEAD^ to HEAD
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

.test-filter-cache.edn - Generated cache file (gitignored)
```

## Important Implementation Details

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

### Git Revision Comparison
The system automatically detects what to compare:
- **With uncommitted changes**: compares HEAD to working directory
- **No uncommitted changes**: compares HEAD^ to HEAD (previous commit)
- Users can override with `:from-revision` and `:to-revision` options

## Testing Philosophy

Test namespaces follow the pattern `com.fulcrologic.test-filter.*-test` for unit tests, with integration tests under `*.integration.*`.

The project uses Kaocha as the test runner. The demo tests in `src/demo/` can be used to verify test selection logic.

## Dependencies

- `clj-kondo` - Static analysis of Clojure code
- `loom` - Graph library for dependency walking
- `tools.cli` - Command-line parsing
- `kaocha` (test alias) - Test runner
- `fulcro-spec` (test alias) - Alternative test framework support
