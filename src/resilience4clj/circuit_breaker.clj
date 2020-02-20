(ns resilience4clj.circuit-breaker
  "Functions to create and execute circuit breakers."
  (:refer-clojure :exclude [name reset!])
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str])
  (:import [io.github.resilience4j.circuitbreaker
            CircuitBreaker
            CircuitBreakerConfig
            CircuitBreakerConfig$SlidingWindowType
            CircuitBreakerRegistry]
           [java.time Duration]
           [java.util.function Predicate]))

;; -----------------------------------------------------------------------------
;; Circuit breaker configuration

(s/def ::failure-rate-threshold
  (s/and number? #(<= 0 % 100)))

(s/def ::slow-call-rate-threshold
  (s/and number? #(<= 0 % 100)))

(s/def ::slow-call-duration-threshold
  nat-int?)

(s/def ::permitted-number-of-calls-in-half-open-state
  nat-int?)

(s/def ::sliding-window-type
  #{:count-based
    :time-based})

(s/def ::sliding-window-size
  nat-int?)

(s/def ::minimum-number-of-calls
  nat-int?)

(s/def ::wait-duration-in-open-state
  nat-int?)

(s/def ::automatic-transition-from-open-to-half-open-enabled
  boolean?)

(s/def ::record-exceptions
  (s/coll-of class?))

(s/def ::ignore-exceptions
  (s/coll-of class?))

(s/def ::record-exception
  fn?)

(s/def ::ignore-exception
  fn?)

(s/def ::config
  (s/keys :opt-un [::failure-rate-threshold
                   ::slow-call-rate-threshold
                   ::slow-call-duration-threshold
                   ::permitted-number-of-calls-in-half-open-state
                   ::sliding-window-type
                   ::sliding-window-size
                   ::minimum-number-of-calls
                   ::wait-duration-in-open-state
                   ::automatic-transition-from-open-to-half-open-enabled
                   ::record-exceptions
                   ::ignore-exceptions
                   ::record-exception
                   ::ignore-exception]))

(s/def ::name
  (s/or :string (s/and string? not-empty)
        :keyword keyword?))

(defn- build-config [config]
  (let [{:keys [failure-rate-threshold
                slow-call-rate-threshold
                slow-call-duration-threshold
                permitted-number-of-calls-in-half-open-state
                sliding-window-type
                sliding-window-size
                minimum-number-of-calls
                wait-duration-in-open-state
                automatic-transition-from-open-to-half-open-enabled
                record-exceptions
                ignore-exceptions
                record-exception
                ignore-exception]} config]
    (cond-> (CircuitBreakerConfig/custom)

      failure-rate-threshold
      (.failureRateThreshold failure-rate-threshold)

      slow-call-rate-threshold
      (.slowCallRateThreshold slow-call-rate-threshold)

      slow-call-duration-threshold
      (.slowCallDurationThreshold (Duration/ofMillis slow-call-duration-threshold))

      permitted-number-of-calls-in-half-open-state
      (.permittedNumberOfCallsInHalfOpenState permitted-number-of-calls-in-half-open-state)

      sliding-window-type
      (as-> cfg
          (let [t (if (= sliding-window-type :count-based)
                    CircuitBreakerConfig$SlidingWindowType/COUNT_BASED
                    CircuitBreakerConfig$SlidingWindowType/TIME_BASED)]
            (.slidingWindowType cfg t)))

      sliding-window-size
      (.slidingWindowSize sliding-window-size)

      minimum-number-of-calls
      (.minimumNumberOfCalls minimum-number-of-calls)

      wait-duration-in-open-state
      (.waitDurationInOpenState (Duration/ofMillis wait-duration-in-open-state))

      automatic-transition-from-open-to-half-open-enabled
      (.automaticTransitionFromOpenToHalfOpenEnabled automatic-transition-from-open-to-half-open-enabled)

      record-exceptions
      (.recordExceptions (into-array java.lang.Class record-exceptions))

      ignore-exceptions
      (.ignoreExceptions (into-array java.lang.Class ignore-exceptions))

      record-exception
      (.recordException (reify Predicate (test [_ ex] (record-exception ex))))

      ignore-exception
      (.ignoreException (reify Predicate (test [_ ex] (ignore-exception ex))))

      :always
      (.build))))

;; -----------------------------------------------------------------------------
;; Registry

(def registry
  "The global circuit breaker and config registry."
  (CircuitBreakerRegistry/ofDefaults))

(defn- build-configs-map [configs-map]
  (into {} (map (fn [[k v]] [(clojure.core/name k) (build-config v)]) configs-map)))

(defn configure-registry!
  "Overwrites the global registry with one that contains the configs-map.

  configs-map is a map whose keys are names and vals are configs. When a circuit
  breaker is created, you may specify one of the names in this map to use as the
  config for that circuit breaker.

  :default is a special name. It will be used as the config for circuit breakers
  that do not specify a config to use."
  [configs-map]
  (alter-var-root (var registry)
                  (fn [_]
                    (CircuitBreakerRegistry/of (build-configs-map configs-map)))))

;; -----------------------------------------------------------------------------
;; Creation and fetching from registry

(defn circuit-breaker!
  "Creates or fetches a circuit breaker with the specified name and config and
  stores it in the global registry.

  The config value can be either a config map or the name of a config map stored
  in the global registry.

  If the circuit breaker already exists in the global registry, the config value
  is ignored."
  ([name]
   {:pre [(s/valid? ::name name)]}
   (.circuitBreaker registry (clojure.core/name name)))
  ([name config]
   {:pre [(s/valid? ::name name)
          (s/valid? (s/or :name ::name :config ::config) config)]}
   (if (s/valid? ::name config)
     (.circuitBreaker registry (clojure.core/name name) config)
     (.circuitBreaker registry (clojure.core/name name) (build-config config)))))

(defn circuit-breaker
  "Creates a circuit breaker with the specified name and config."
  [name config]
  {:pre [(s/valid? ::name name)
         (s/valid? ::config config)]}
  (CircuitBreaker/of (clojure.core/name name) (build-config config)))

;; -----------------------------------------------------------------------------
;; Execution

(defn execute
  "Apply args to f within a context protected by the circuit breaker."
  [^CircuitBreaker circuit-breaker f & args]
  (.executeCallable circuit-breaker #(apply f args)))

(defmacro with-circuit-breaker
  "Executes body within a context protected by the circuit breaker.

  `circuit-breaker` is either a circuit breaker or the name of one in the global
  registry. If you provide a name and a circuit breaker of that name does not
  already exist in the global registry, one will be created with the `:default`
  config."
  [circuit-breaker & body]
  `(let [cb# (if (s/valid? ::name ~circuit-breaker)
               (circuit-breaker! (clojure.core/name ~circuit-breaker))
               ~circuit-breaker)]
     (execute cb# (fn [] ~@body))))

;; -----------------------------------------------------------------------------
;; Managing state

(defn reset!
  "Resets the circuit breaker to its original state.

  Resets all metrics collected and effectively empties the contents of its ring
  buffers."
  [^CircuitBreaker circuit-breaker]
  (.reset circuit-breaker))

(defn disable!
  "Transitions the circuit breaker to the `:disabled` state.

  In the `:disabled` state, the circuit breaker will not automatically
  transition to other states, collect metrics, or send events. All calls will be
  allowed to pass.

  You must manually transition out of the `:disabled` state."
  [^CircuitBreaker circuit-breaker]
  (.transitionToDisabledState circuit-breaker))

(defn close!
  "Transitions the circuit breaker to the `:closed` state."
  [^CircuitBreaker circuit-breaker]
  (.transitionToClosedState circuit-breaker))

(defn open!
  "Transitions the circuit breaker to the `:open` state."
  [^CircuitBreaker circuit-breaker]
  (.transitionToOpenState circuit-breaker))

(defn half-open!
  "Transitions the circuit breaker to the `:half-open` state."
  [^CircuitBreaker circuit-breaker]
  (.transitionToHalfOpenState circuit-breaker))

(defn force-open!
  "Transitions the circuit breaker to the `:forced-open` state.

  In the `:forced-open` state, the circuit breaker will not automatically
  transition to other states, collect metrics, or send events. No call will be
  allowed to pass.

  You must manually transition out of the `:forced-open` state."
  [^CircuitBreaker circuit-breaker]
  (.transitionToForcedOpenState circuit-breaker))

;; -----------------------------------------------------------------------------
;; Circuit breaker properties

(defn name
  "Gets the name of the circuit breaker."
  [^CircuitBreaker circuit-breaker]
  (.getName circuit-breaker))

(defn state
  "Gets the current state of the circuit breaker."
  [^CircuitBreaker circuit-breaker]
  (-> circuit-breaker
      (.getState)
      (str/lower-case)
      (str/replace #"_" "-")
      (keyword)))
