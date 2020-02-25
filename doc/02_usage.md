## 2. Usage

### Executing Code Protected by a Circuit Breaker

There are two ways to execute code to be protected by the circuit breaker:
`execute` and `with-circuit-breaker`.

`execute` executes a single function within the context of the circuit breaker
and applies any args to it. If the circuit breaker is in an open state, the
function is not called and an exception is thrown instead.

```clojure
> (require '[resilience4clj.circuit-breaker :as cb])
;; => nil

> (cb/execute (cb/circuit-breaker! :my-breaker) map inc [1 2 3])
;; => (2 3 4) if :my-breaker is closed (or half-open and accepting calls)
;;    OR
;;    throws an exception if :my-breaker is open (or half-open and rejecting calls)
```

`execute` is rather low-level. To make execution more convenient, this library
also includes a `with-circuit-breaker` macro that executes several forms within
a context protected by the circuit breaker. When you use the macro, you must
either provide a circuit breaker or the name of one in the global registry. If
you provide a name and a circuit breaker of that name does not already exist in
the global registry, one is created with the `:default` config.

```clojure
> (require '[resilience4clj.circuit-breaker :refer [with-circuit-breaker]])
;; => nil

> (with-circuit-breaker :my-breaker
    (http/get "https://www.example.com")
    ;; other code here
  )
;; => some value if :my-breaker is closed (or half-open and accepting calls)
;;    OR
;;    throws an exception if :my-breaker is open (or half-open and rejecting calls)
```

### State Management

In most cases, a circuit breaker will automatically transition between states.
However, there are several functions that allow you to manually transition the
circuit breaker into a specific state, even ones that cannot be reached using
the automatic transitions.

| State           | Transition fn   | Description                                                                                                                                        |
|-----------------|-----------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| `:closed`       | `close!`        | All calls are passed until the ring buffer is full and the failure threshold is met or exceeded.                                                   |
| `:open`         | `open!`         | No calls are passed.                                                                                                                               |
| `:half-open`    | `half-open!`    | Only a number of calls are passed. Transitions to `:open` or `:closed` after filling its ring buffer.                                              |
| `:forced-open`  | `force-open!`   | No calls are passed. This state cannot be reached automatically and you must transition out of it manually.                                        |
| `:disabled`     | `disable!`      | All calls are passed. This state cannot be reached automatically and you must transition out of it manually.                                       |
| `:metrics-only` | `metrics-only!` | All calls are passed, but metrics continue to be collected. This state cannot be reached automatically and you must transition out of it manually. |
| N/A             | `reset!`        | Resets to the original state. The circuit breaker loses all metrics and effectively empties the contents of its ring buffers.                      |


### Consuming Events

Both registries and circuit breakers can emit different types of events. The
`emit-registry-events!` and `emit-events!` functions accept a core.async
channel and will publish events to that channel. Events are represented as maps
containing an `:event-type` key and any additional key/value pairs that
represent the event data.

Registries emit the following types of events:
  * `:added`
  * `:removed`
  * `:replaced`

Circuit breakers emit the following types of events:
  * `:success`
  * `:error`
  * `:state-transition`
  * `:reset`
  * `:ignored-error`
  * `:not-permitted`
  * `:failure-rate-exceeded`
  * `:slow-call-rate-exceeded`

The following example demonstrates various ways to emit events to an output
channel for both registries and circuit breakers.

```clojure
(ns my-project.core
  (:require [clojure.core.async :as async]
            [resilience4clj.circuit-breaker :as cb]))

(def reg (cb/registry))

(def breaker (cb/circuit-breaker! :my-breaker))

(def output-chan (async/chan))

;; Send all registry events to output-chan
(cb/emit-registry-events! reg output-chan)

;; Send only added events to output-chan
(cb/emit-registry-events! reg output-chan :only [:added])

;; Send all events except replaced events to output-chan
(cb/emit-registry-events! reg output-chan :exclude [:replaced])

;; Send all events from the circuit breaker to output-chan
(cb/emit-events! breaker output-chan)

;; Send only error events from the circuit breaker to output-chan
(cb/emit-events! breaker output-chan :only [:error])

;; Send all events except success from the circuit breaker to output-chan
(cb/emit-events! breaker output-chan :exclude [:success])
```
