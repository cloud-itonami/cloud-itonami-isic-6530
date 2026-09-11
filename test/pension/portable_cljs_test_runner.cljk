(ns pension.portable-cljs-test-runner
  "PRIMARY automated quality gate for this actor under a real
  ClojureScript host (cljs.main --target node) — the same runtime-
  priority rule as gftdcojp/cloud-itonami's ADR-0016 / the superproject
  CLAUDE.md:

      kotoba wasm runtime  >  clojurewasm  >  ClojureScript  >  nbb
      (JVM / babashka are last-resort compat, not the design target)

  The whole test suite is portable .cljc and runs UNCHANGED here and on
  the JVM (`clojure -M:dev:test`, secondary compat gate). This includes
  `pension.store-contract-test`, which exercises the langchain.db
  Datomic-API-compatible store — the kotoba-server / kotobase datom
  seam — under ClojureScript.

  Invoke from the repo root (the :test alias's :main-opts would steal
  -m if combined, hence -Sdeps for the extra path):

    clojure -Sdeps '{:paths [\"src\" \"test\"]}' \\
      -M:dev:cljs -m cljs.main --target node \\
      --output-dir target/node-out \\
      --output-to target/tests.cjs -c pension.portable-cljs-test-runner
    echo '{\"type\":\"commonjs\"}' > target/node-out/package.json
    node target/tests.cjs

  EXIT CODE. The two-step above is not decoration. `cljs.main ... -m
  <this-ns>` evaluates -main inside a node REPL environment, and the
  process a caller waits on -- the driver -- exits 0 no matter what the
  tests did, so the `js/process.exitCode` this file sets below never
  reaches anyone. Measured 2026-08-25 in cloud-itonami-isic-6492, both
  directions: the -m form printed `1 failures` and exited 0 with an
  assertion deliberately broken; the compiled bundle printed
  `1 failures` and exited 1 for the same break, including when the
  break lands inside an async callback after run-tests has returned.
  `js/process.exit` is not an escape either -- it hangs the driver.

  The package.json line is load-bearing: these repos declare
  `\"type\": \"module\"`, which makes node read Closure's emitted .js as
  ESM and die on `require`. The marker scopes target/node-out back to
  CommonJS."
  (:require [clojure.test :as t :refer [run-tests]]
            [pension.facts-test]
            [pension.governor-contract-test]
            [pension.kernels.gate-test]
            [pension.phase-test]
            [pension.registry-test]
            [pension.store-contract-test]))

#?(:cljs
   (defmethod t/report [:cljs.test/default :end-run-tests] [m]
     (when-not (t/successful? m)
       (set! (.-exitCode js/process) 1))))

(defn -main []
  (run-tests 'pension.facts-test
             'pension.registry-test
             'pension.phase-test
             'pension.kernels.gate-test
             'pension.governor-contract-test
             'pension.store-contract-test))

;; The compiled node bundle runs `cljs.nodejscli`, which calls whatever
;; `*main-cli-fn*` names. Without this the bundle loads every namespace,
;; runs no test, and exits 0 -- measured 2026-08-25, and indistinguishable
;; from a clean run in both the output and the exit code.
#?(:cljs (set! *main-cli-fn* -main))
