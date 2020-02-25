(ns resilience4clj.circuit-breaker
  "Functions to create and execute circuit breakers."
  (:refer-clojure :exclude [find name reset!])
  (:require [clojure.core.async :as async]
            [clojure.string :as str])
  (:import [io.github.resilience4j.circuitbreaker
            CircuitBreaker
            CircuitBreaker$EventPublisher
            CircuitBreakerConfig
            CircuitBreakerConfig$SlidingWindowType
            CircuitBreakerRegistry]
           [io.github.resilience4j.circuitbreaker.event
            AbstractCircuitBreakerEvent
            CircuitBreakerOnCallNotPermittedEvent
            CircuitBreakerOnErrorEvent
            CircuitBreakerOnFailureRateExceededEvent
            CircuitBreakerOnIgnoredErrorEvent
            CircuitBreakerOnResetEvent
            CircuitBreakerOnSlowCallRateExceededEvent
            CircuitBreakerOnStateTransitionEvent
            CircuitBreakerOnSuccessEvent]
           [io.github.resilience4j.circuitbreaker.utils
            CircuitBreakerUtil]
           [io.github.resilience4j.core
            EventConsumer
            Registry$EventPublisher]
           [io.github.resilience4j.core.registry
            EntryAddedEvent
            EntryRemovedEvent
            EntryReplacedEvent]
           [java.time Duration]
           [java.util Map Optional]
           [java.util.function Predicate]))

(set! *warn-on-reflection* true)

(defn- optional-value [^Optional optional]
  (when (.isPresent optional)
    (.get optional)))

(defn- name? [val]
  (or (string? val)
      (keyword? val)))

(defn- keywordize-enum-value [^Object enum-value]
  (-> (.toString enum-value)
      (str/lower-case)
      (str/replace #"_" "-")
      (keyword)))

;; -----------------------------------------------------------------------------
;; Circuit breaker configuration

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
      (.slidingWindowType (condp = sliding-window-type
                            :count-based CircuitBreakerConfig$SlidingWindowType/COUNT_BASED
                            :time-based  CircuitBreakerConfig$SlidingWindowType/TIME_BASED))

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

(def default-registry
  "The default registry for storing circuit breakers and configurations."
  (CircuitBreakerRegistry/ofDefaults))

(defn- build-configs-map [configs-map]
  (into {} (map (fn [[k v]] [(clojure.core/name k) (build-config v)])
                configs-map)))

(defn registry
  "Creates a registry with default values or using a map of name/config-map pairs."
  ([]
   (CircuitBreakerRegistry/ofDefaults))
  ([configs-map]
   (let [^Map configs (build-configs-map configs-map)]
    (CircuitBreakerRegistry/of configs))))

(defn all-circuit-breakers
  "Gets all the circuit breakers in `registry`.

  Uses [[default-registry]] if `registry` is not provided."
  ([]
   (all-circuit-breakers default-registry))
  ([^CircuitBreakerRegistry registry]
   (set (.getAllCircuitBreakers registry))))

(defn add-configuration!
  "Adds `config` to the `registry` under the `name`.

  Uses [[default-registry]] if `registry` is not provided."
  ([name config]
   (add-configuration! default-registry name config))
  ([^CircuitBreakerRegistry registry name config]
   (.addConfiguration registry (clojure.core/name name) (build-config config))))

(defn find
  "Finds the circuit breaker identified by `name` in `registry`.

  Uses [[default-registry]] if `registry` is not provided."
  ([name]
   (find default-registry name))
  ([^CircuitBreakerRegistry registry name]
   (optional-value (.find registry (clojure.core/name name)))))

(defn remove!
  "Removes the circuit breaker idenfitied by `name` from `registry`.

  Uses [[default-registry]] if `registry` is not provided."
  ([name]
   (remove! default-registry name))
  ([^CircuitBreakerRegistry registry name]
   (optional-value (.remove registry (clojure.core/name name)))))

(defn replace!
  "Replaces the circuit breaker identified by `name` in `registry` with the specified `circuit-breaker`.

  Uses [[default-registry]] if `registry` is not provided."
  ([name ^CircuitBreaker circuit-breaker]
   (replace! default-registry name circuit-breaker))
  ([^CircuitBreakerRegistry registry name ^CircuitBreaker circuit-breaker]
   (optional-value (.replace registry (clojure.core/name name) circuit-breaker))))

;; -----------------------------------------------------------------------------
;; Registry events

(defn- entry-added-consumer [out-chan]
  (reify EventConsumer
    (consumeEvent [_ event]
      (let [^EntryAddedEvent e event]
       (async/offer! out-chan
                     {:event-type (keywordize-enum-value (.getEventType e))
                      :added-entry ^CircuitBreaker (.getAddedEntry e)})))))

(defn- entry-removed-consumer [out-chan]
  (reify EventConsumer
    (consumeEvent [_ event]
      (let [^EntryRemovedEvent e event]
       (async/offer! out-chan
                     {:event-type (keywordize-enum-value (.getEventType e))
                      :removed-entry ^CircuitBreaker (.getRemovedEntry e)})))))

(defn- entry-replaced-consumer [out-chan]
  (reify EventConsumer
    (consumeEvent [_ event]
      (let [^EntryReplacedEvent e event]
       (async/offer! out-chan
                     {:event-type (keywordize-enum-value (.getEventType e))
                      :old-entry ^CircuitBreaker (.getOldEntry e)
                      :new-entry ^CircuitBreaker (.getNewEntry e)})))))

(def registry-event-types
  "The event types that can be raised by a registry."
  #{:added
    :removed
    :replaced})

(defn emit-registry-events!
  "Offers registry events to `out-chan`.

  The event types are identified by [[registry-event-types]].

  This function also accepts `:only` and `:exclude` keyword params that are
  sequences of the event types that should be included or excluded,
  respectively.

  Uses [[default-registry]] if `registry` is not provided."
  ([out-chan]
   (emit-registry-events! default-registry out-chan))
  ([^CircuitBreakerRegistry registry out-chan & {:keys [only exclude]
                                                 :or {exclude []}}]
   (let [events-to-publish (if only (set only)
                               (apply disj registry-event-types exclude))]
     (let [^Registry$EventPublisher pub (.getEventPublisher registry)]
       (when (contains? events-to-publish :added)
         (.onEntryAdded pub (entry-added-consumer out-chan)))
       (when (contains? events-to-publish :removed)
         (.onEntryRemoved pub (entry-removed-consumer out-chan)))
       (when (contains? events-to-publish :replaced)
         (.onEntryReplaced pub (entry-replaced-consumer out-chan)))))
   out-chan))

;; -----------------------------------------------------------------------------
;; Creation and fetching from registry

(defn circuit-breaker!
  "Creates or fetches a circuit breaker with `name` and `config` and stores it
  in `registry`.

  The config value can be either a config map or the name of a config map stored
  in the registry. If the circuit breaker already exists in the registry, the
  config value is ignored.

  Uses [[default-registry]] if `registry` is not provided."
  ([name]
   (circuit-breaker! default-registry name))
  ([^CircuitBreakerRegistry registry name]
   (.circuitBreaker registry (clojure.core/name name)))
  ([^CircuitBreakerRegistry registry name config]
   (if (name? config)
     (.circuitBreaker registry (clojure.core/name name) (clojure.core/name config))
     (let [^CircuitBreakerConfig cfg (build-config config)]
      (.circuitBreaker registry (clojure.core/name name) cfg)))))

(defn circuit-breaker
  "Creates a circuit breaker with `name` and `config`."
  ([name]
   (circuit-breaker name {}))
  ([name config]
   (let [^CircuitBreakerConfig cfg (build-config config)]
     (CircuitBreaker/of (clojure.core/name name) cfg))))

;; -----------------------------------------------------------------------------
;; Execution

(defn execute
  "Apply `args` to `f` within a context protected by `circuit-breaker`."
  [^CircuitBreaker circuit-breaker f & args]
  (.executeCallable circuit-breaker #(apply f args)))

(defmacro with-circuit-breaker
  "Executes `body` within a context protected by `circuit-breaker`.

  `circuit-breaker` is either a circuit breaker or the name of one in the
  default registry. If you provide a name and a circuit breaker of that name
  does not already exist in the default registry, one will be created with the
  `:default` config."
  [circuit-breaker & body]
  `(let [cb# (if (instance? CircuitBreaker ~circuit-breaker)
               ~circuit-breaker
               (circuit-breaker! (clojure.core/name ~circuit-breaker)))]
     (execute cb# (fn [] ~@body))))

;; -----------------------------------------------------------------------------
;; Managing state

(defn reset!
  "Resets `circuit-breaker` to its original state.

  Resets all metrics collected and effectively empties the contents of its ring
  buffers."
  [^CircuitBreaker circuit-breaker]
  (.reset circuit-breaker))

(defn disable!
  "Transitions `circuit-breaker` to the `:disabled` state.

  In the `:disabled` state, the circuit breaker will not automatically
  transition to other states, collect metrics, or send events. All calls will be
  allowed to pass.

  You must manually transition out of the `:disabled` state."
  [^CircuitBreaker circuit-breaker]
  (.transitionToDisabledState circuit-breaker))

(defn close!
  "Transitions `circuit-breaker` to the `:closed` state."
  [^CircuitBreaker circuit-breaker]
  (.transitionToClosedState circuit-breaker))

(defn open!
  "Transitions `circuit-breaker` to the `:open` state."
  [^CircuitBreaker circuit-breaker]
  (.transitionToOpenState circuit-breaker))

(defn half-open!
  "Transitions `circuit-breaker` to the `:half-open` state."
  [^CircuitBreaker circuit-breaker]
  (.transitionToHalfOpenState circuit-breaker))

(defn force-open!
  "Transitions `circuit-breaker` to the `:forced-open` state.

  In the `:forced-open` state, the circuit breaker will not automatically
  transition to other states, collect metrics, or send events. No call will be
  allowed to pass.

  You must manually transition out of the `:forced-open` state."
  [^CircuitBreaker circuit-breaker]
  (.transitionToForcedOpenState circuit-breaker))

(defn metrics-only!
  "Transitions `circuit-breaker` to the `:metrics-only` state.

  In the `:metrics-only` state, the circuit breaker will not transition to
  other states, but it will continue to capture metrics and publish events.

  You must manually transition out of the `:metrics-only`` state."
  [^CircuitBreaker circuit-breaker]
  (.transitionToMetricsOnlyState circuit-breaker))

;; -----------------------------------------------------------------------------
;; Circuit breaker properties

(defn name
  "Gets the name of `circuit-breaker`."
  [^CircuitBreaker circuit-breaker]
  (.getName circuit-breaker))

(defn state
  "Gets the current state of `circuit-breaker`."
  [^CircuitBreaker circuit-breaker]
  (keywordize-enum-value (.getState circuit-breaker)))

(defn permitting-calls?
  "Indicates whether `circuit-breaker` is allowing calls to be executed."
  [^CircuitBreaker circuit-breaker]
  (CircuitBreakerUtil/isCallPermitted circuit-breaker))

;; -----------------------------------------------------------------------------
;; Circuit breaker events

(def event-types
  #{:success
    :error
    :state-transition
    :reset
    :ignored-error
    :not-permitted
    :failure-rate-exceeded
    :slow-call-rate-exceeded})

(defn- base-event [^AbstractCircuitBreakerEvent event]
  {:event-type (keywordize-enum-value (.getEventType event))
   :circuit-breaker-name (.getCircuitBreakerName event)
   :creation-time (.getCreationTime event)})

(defn- success-consumer [out-chan]
  (reify EventConsumer
    (consumeEvent [_ event]
      (let [^CircuitBreakerOnSuccessEvent e event]
        (async/offer! out-chan
                      (assoc (base-event e)
                             :elapsed-duration (.getElapsedDuration e)))))))

(defn- error-consumer [out-chan]
  (reify EventConsumer
    (consumeEvent [_ event]
      (let [^CircuitBreakerOnErrorEvent e event]
        (async/offer! out-chan
                      (assoc (base-event e)
                             :throwable (.getThrowable e)
                             :elapsed-duration (.getElapsedDuration e)))))))

(defn- state-transition-consumer [out-chan]
  (reify EventConsumer
    (consumeEvent [_ event]
      (let [^CircuitBreakerOnStateTransitionEvent e event]
        (async/offer! out-chan
                      (assoc (base-event e)
                             :from-state (keywordize-enum-value (.. e getStateTransition getFromState))
                             :to-state (keywordize-enum-value (.. e getStateTransition getToState))))))))

(defn- reset-consumer [out-chan]
  (reify EventConsumer
    (consumeEvent [_ event]
      (let [^CircuitBreakerOnResetEvent e event]
        (async/offer! out-chan (base-event e))))))

(defn- ignored-error-consumer [out-chan]
  (reify EventConsumer
    (consumeEvent [_ event]
      (let [^CircuitBreakerOnIgnoredErrorEvent e event]
        (async/offer! out-chan
                      (assoc (base-event e)
                             :throwable (.getThrowable e)
                             :elapsed-duration (.getElapsedDuration e)))))))

(defn- call-not-permitted-consumer [out-chan]
  (reify EventConsumer
    (consumeEvent [_ event]
      (let [^CircuitBreakerOnCallNotPermittedEvent e event]
        (async/offer! out-chan (base-event e))))))

(defn- failure-rate-exceeded-consumer [out-chan]
  (reify EventConsumer
    (consumeEvent [_ event]
      (let [^CircuitBreakerOnFailureRateExceededEvent e event]
        (async/offer! out-chan
                      (assoc (base-event e)
                             :failure-rate (.getFailureRate e)))))))

(defn- slow-call-rate-exceeded-consumer [out-chan]
  (reify EventConsumer
    (consumeEvent [_ event]
      (let [^CircuitBreakerOnSlowCallRateExceededEvent e event]
        (async/offer! out-chan
                      (assoc (base-event e)
                             :slow-call-rate (.getSlowCallRate e)))))))

(defn emit-events!
  "Offers events `circuit-breaker` to `out-chan`.

  The event types are identified by [[event-types]].

  This function also accepts `:only` and `:exclude` keyword params that are
  sequences of the event types that should be included or excluded,
  respectively."
  [^CircuitBreaker circuit-breaker out-chan & {:keys [only exclude]
                                               :or {exclude []}}]
  (let [events-to-publish (if only
                            (set only)
                            (apply disj event-types exclude))
        ^CircuitBreaker$EventPublisher pub (.getEventPublisher circuit-breaker)]
    (when (contains? events-to-publish :success)
      (.onSuccess pub (success-consumer out-chan)))
    (when (contains? events-to-publish :error)
      (.onError pub (error-consumer out-chan)))
    (when (contains? events-to-publish :state-transition)
      (.onStateTransition pub (state-transition-consumer out-chan)))
    (when (contains? events-to-publish :reset)
      (.onReset pub (reset-consumer out-chan)))
    (when (contains? events-to-publish :ignored-error)
      (.onIgnoredError pub (ignored-error-consumer out-chan)))
    (when (contains? events-to-publish :not-permitted)
      (.onCallNotPermitted pub (call-not-permitted-consumer out-chan)))
    (when (contains? events-to-publish :failure-rate-exceeded)
      (.onFailureRateExceeded pub (failure-rate-exceeded-consumer out-chan)))
    (when (contains? events-to-publish :slow-call-rate-exceeded)
      (.onSlowCallRateExceeded pub (slow-call-rate-exceeded-consumer out-chan))))
  out-chan)
