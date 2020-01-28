# resilience4clj-circuitbreaker

A small Clojure wrapper around the
[resilience4j CircuitBreaker module](https://resilience4j.readme.io/docs/circuitbreaker).
Requires Clojure 1.9 or later.

[![clojars badge](https://img.shields.io/clojars/v/tessellator/resilience4clj-circuitbreaker.svg)](https://clojars.org/tessellator/resilience4clj-circuitbreaker)
[![cljdoc badge](https://cljdoc.org/badge/tessellator/resilience4clj-circuitbreaker)](https://cljdoc.org/d/tessellator/resilience4clj-circuitbreaker/CURRENT)


## Quick Start

The following code defines a function `make-remote-call` that uses a circuit
breaker named `:some-name` and stored in the global registry. If the circuit
breaker does not already exist, one is created.

```clojure
(ns myproject.some-client
  (:require [clj-http.client :as http]
            [resilience4clj.circuit-breaker :refer [with-circuit-breaker]])

(defn make-remote-call []
  (with-circuit-breaker :some-name
    (http/get "https://www.example.com")))
```

Refer to the [configuration guide](/doc/01_configuration.md) for more
information on how to configure the global registry as well as individual
circuit breakers.

Refer to the [usage guide](/doc/02_usage.md) for more information on how to
use circuit breakers to protect code as well as how to manually manage the state
on a circuit breaker.

## License

Copyright © 2019-2020 Thomas C. Taylor and contributors.

Distributed under the Eclipse Public License version 2.0.
