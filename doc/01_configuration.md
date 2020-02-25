## 1. Configuration

### Project Dependencies

resilience4clj-circuitbreaker is distributed through
[Clojars](https://clojars.org) with the identifier
`tessellator/resilience4clj-circuitbreaker`. You can find the version
information for the latest release at
https://clojars.org/tessellator/resilience4clj-circuitbreaker.

If you are using JDK 8, you may use any of version of Clojure 1.5+. However, if
you are using JDK 9 or later, you must use Clojure 1.10+ due
to [this bug](https://clojure.atlassian.net/browse/CLJ-2284).

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

| Configuration Option                                   | Default Value        | Description                                                                                                            |
|--------------------------------------------------------|----------------------|------------------------------------------------------------------------------------------------------------------------|
| `:failure-rate-threshold`                              |                   50 | The percentage over which the circuit breaker will trip open                                                           |
| `:slow-call-rate-threshold`                            |                  100 | The percentage over which the circuit breaker will trip open                                                           |
| `:slow-call-duration-threshold`                        |                60000 | The number of milliseconds over which a call is considered slow                                                        |
| `:permitted-number-of-calls-in-half-open-state`        |                   10 | The number of permitted calls when the circuit breaker is in the half open state                                       |
| `:sliding-window-type`                                 |         :count-based | Configures the type of sliding window used when the circuit breaker is closed. Can be `:count-based` or `:time-based`. |
| `:sliding-window-size`                                 |                  100 | Configures the size of the sliding window when the circuit breaker is closed                                           |
| `:minimum-number-of-calls`                             |                   10 | The minimum number of calls required before an error rate can be calculated                                            |
| `:wait-duration-in-open-state`                         |                60000 | The number of milliseconds to wait to transition from open to half-open                                                |
| `:automatic-transition-from-open-to-half-open-enabled` |              `false` | When `true`, no call is needed to occur to transition from open to half-open                                           |
| `:record-exceptions`                                   |                   [] | A vector of exception types which should count as failures                                                             |
| `:ignore-exceptions`                                   |                   [] | A vector of exception types which should not count as failures                                                         |
| `:record-exception`                                    |  `(constantly true)` | A predicate that receives an exception and determines whether it should count as a failure                             |
| `:ignore-exception`                                    | `(constantly false)` | A predicate that receives an exception and determines whether it should be ignored                                     |

A `config` can be used to configure a registry or a circuit breaker when it is
created. You may also add configs to a registry at any point.

### Registries

A registry is an entity that stores circuit breakers and configurations. When
a circuit breaker is created with the `circuit-breaker!` function, it will be
associated with a registry and can be looked up by name in the registry
afterward.

This library creates a `default-registry` that is used when a registry is not
provided but is required. The registry may contain `config` values as
well as circuit breaker instances. In the following example code, any of the
instances of the `reg` parameter may be dropped to use the default registry.

The function `circuit-breaker!` will look up or create a circuit breaker in a
registry. The function accepts a name and optionally the name of a config
or a config map.

```clojure
(ns my-project.core
  (:require [resilience4clj.circuit-breaker :as cb]))

;; The following creates a registry with two configs: the default config and the
;; FailFaster config. The default config uses only the defaults and will be used
;; to create circuit breakers that do not specify a config to use.
;;
;; Note that the "default" configuration here is not necessary; a default config
;; with the default values is included in a registry when it is created. However,
;; you can provide a different configuration and assign it as the default config.
(def reg (cb/registry {"default"    {}
                       "FailFaster" {:failure-rate-threshold 20}}))

;; You may also add configurations after a registry has been created. The
;; following code adds a new configuration to the registry created in the
;; previous lines.
(cb/add-configuration! reg "FailFaster" {:failure-rate-threshold 20})

;; create a circuit breaker named :name using the "default" config from the
;; registry and store the result in the registry
(cb/circuit-breaker! reg :name)

;; create a circuit breaker named :fail-faster using the "FailFaster" config
;; from the registry and store the result in the registry
(cb/circuit-breaker! reg :fail-faster "FailFaster")

;; create a circuit breaker named :custom-config using a custom config map
;; and store the result in the registry
(cb/circuit-breaker! reg :custom-config {:ring-buffer-size-in-closed-state 10})
```


### Custom Circuit Breakers

While convenient, it is not required to use the global registry. You may instead
choose to create circuit breakers and manage them yourself.

In order to create a circuit breaker that is not made available globally, use
the `circuit-breaker` function, which accepts a name and optionally a config
map. If a config map is not provided one with all default values is used.

The following code creates one circuit-breaker with the default config options
and another with a lower failure rate threshold.

```clojure
(ns myproject.core
  (:require [resilience4clj.circuit-breaker :as cb]))

(def my-breaker (cb/circuit-breaker :my-breaker))

(def fail-faster (cb/circuit-breaker :fail-faster {:failure-rate-threshold 20}))
```
