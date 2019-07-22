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

| State          | Transition fn | Description                                                                                                                  |
|----------------|---------------|------------------------------------------------------------------------------------------------------------------------------|
| `:closed`      | `close!`      | All calls are passed until the ring buffer is full and the failure threshold is met or exceeded.                             |
| `:open`        | `open!`       | No calls are passed.                                                                                                         |
| `:half-open`   | `half-open!`  | Only a number of calls are passed. Transitions to `:open` or `:closed` after filling its ring buffer.                        |
| `:forced-open` | `force-open!` | No calls are passed. This state cannot be reached automatically and you must transition out of it manually.                  |
| `:disabled`    | `disable!`    | All calls are passed. This state cannot be reached automatically and you must transition out of it manually.                 |
| N/A            | `reset!`      | Resets to the original state. The circuit breaker loses all metrics and effectively empties the contents of its ring buffers.|
