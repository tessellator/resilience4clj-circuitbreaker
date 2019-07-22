## 1. Configuration

### Project Dependencies

resilience4clj-circuitbreaker is distributed through
[Clojars](https://clojars.org) with the identifier
`tessellator/resilience4clj-circuitbreaker`. You can find the version
information for the latest release at
https://clojars.org/tessellator/resilience4clj-circuitbreaker.

The library has a hidden dependency on SLF4J, and you will need to include a
dependency and related configuration for a specific logging implementation.

As an example, you could include the following in your project dependencies:

```
[org.slf4j/slf4j-log4j12 <VERSION>]
```

If you do not configure logging, you will see some SLF4J warnings output and
will not receive logs the underlying circuit breaker library.

### Configuration Options

The following table describes the options available when configuring circuit
breakers as well as default values. A `config` is a map that contains any of the
keys in the table. Note that a `config` without a particular key will use the
default value (e.g., `{}` selects all default values).

| Configuration Option                                   | Default Value       | Description                                                                                |
|--------------------------------------------------------|---------------------|--------------------------------------------------------------------------------------------|
| `:failure-rate-threshold`                              |                  50 | The percentage over which the circuit breaker will trip open                               |
| `:ring-buffer-size-in-half-open-state`                 |                  10 | The ring buffer size in the half-open state                                                |
| `:ring-buffer-size-in-closed-state`                    |                 100 | The ring buffer size in the closed state                                                   |
| `:wait-duration-in-open-state`                         |               60000 | The number of milliseconds to wait to transition from open to half-open                    |
| `:automatic-transition-from-open-to-half-open-enabled` |             `false` | When `true`, no call is needed to occur to transition from open to half-open               |
| `:record-exceptions`                                   |                  [] | A vector of exception types which should count as failures                                 |
| `:ignore-exceptions`                                   |                  [] | A vector of exception types which should not count as failures                             |
| `:record-failure`                                      | `(constantly true)` | A predicate that receives an exception and determines whether it should count as a failure |

A `config` can be used to configure the global registry or a single circuit
breaker when it is created.

### Global Registry

This library creates a single global `registry` The registry may contain
`config` values as well as circuit breaker instances.

`configure-registry!` overwrites the existing registry with one containing one
or more config values. `configure-registry!` takes a map of name/config value
pairs. When a circuit breaker is created, it may refer to one of these names to
use the associated config. Note that the name `:default` (or `"default"`) is
special in that circuit breakers that are created without a providing or naming
a config with use this default config.

The function `circuit-breaker!` will look up or create a circuit breaker in the
global registry. The function accepts a name and optionally the name of a config
or a config map.

```clojure
(ns myproject.core
  (:require [resilience4clj.circuit-breaker :as cb])

;; The following creates two configs: the default config and the FailFaster
;; config. The default config uses only the defaults and will be used to create
;; circuit breakers that do not specify a config to use.
(cb/configure-registry! {"default"    {}
                         "FailFaster" {:failure-rate-threshold 20}})


;; create a circuit breaker named :name using the "default" config from the
;; registry and store the result in the registry
(cb/circuit-breaker! :name)

;; create a circuit breaker named :fail-faster using the "FailFaster" config
;; from the registry and store the result in the registry
(cb/circuit-breaker! :fail-faster "FailFaster")

;; create a circuit breaker named :custom-config using a custom config map
;; and store the result in the registry
(cb/circuit-breaker! :custom-config {:ring-buffer-size-in-closed-state 10})
```

### Custom Circuit Breakers

While convenient, it is not required to use the global registry. You may instead
choose to create circuit breakers and manage them yourself.

In order to create a circuit breaker that is not made available globally, use
the `circuit-breaker` function, which accepts a name and config map.

The following code creates a new circuit-breaker with the default config options.

```clojure
(ns myproject.core
  (:require [resilience4clj.circuit-breaker :as cb]))

(def my-breaker (cb/circuit-breaker :my-breaker {}))
```
