(ns resilience4clj.circuit-breaker-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [resilience4clj.circuit-breaker :as cb :refer [with-circuit-breaker]])
  (:import [io.github.resilience4j.circuitbreaker
            CallNotPermittedException
            CircuitBreakerConfig$SlidingWindowType]
           [io.github.resilience4j.core
            ConfigurationNotFoundException]
           [java.time Duration ZonedDateTime]))

(defn- take-with-timeout!! [ch]
  (let [timeout-chan (async/timeout 5)]
    (async/alt!!
      ch ([v] v)
      timeout-chan :timeout)))

;; -----------------------------------------------------------------------------
;; Configuration

(deftest test-build-config
  ;; NOTE: There are no ways to access the recordExceptions, recordException,
  ;;       ignoreExceptions, and ignoreException fields directly, so they are
  ;;       not included in this test
  (let [build-config #'cb/build-config
        config-map {:failure-rate-threshold 1
                    :slow-call-rate-threshold 2
                    :slow-call-duration-threshold 3
                    :permitted-number-of-calls-in-half-open-state 4
                    :max-wait-duration-in-half-open-state 80
                    :sliding-window-size 5
                    :minimum-number-of-calls 6
                    :wait-duration-in-open-state 70
                    :automatic-transition-from-open-to-half-open-enabled true}
        config (build-config config-map)]
    (is (= 1.0 (.getFailureRateThreshold config)))
    (is (= 2.0 (.getSlowCallRateThreshold config)))
    (is (= 3 (.. config getSlowCallDurationThreshold toMillis)))
    (is (= 4 (.getPermittedNumberOfCallsInHalfOpenState config)))
    (is (= 80 (.. config getMaxWaitDurationInHalfOpenState toMillis)))
    (is (= 5 (.getSlidingWindowSize config)))
    (is (= 6 (.getMinimumNumberOfCalls config)))
    (is (= 70 (.. config getWaitDurationInOpenState toMillis)))
    (is (true? (.isAutomaticTransitionFromOpenToHalfOpenEnabled config)))))

(deftest test-build-config--record-exceptions
  (let [breaker (cb/circuit-breaker :some-name
                                    {:record-exceptions [NullPointerException
                                                         ArrayIndexOutOfBoundsException]})]
    (try
      (with-circuit-breaker breaker
        (throw (NullPointerException.)))
      (catch Throwable _))
    (is (= 1 (.. breaker getMetrics getNumberOfFailedCalls)))

    (try
      (with-circuit-breaker breaker
        (throw (ArrayIndexOutOfBoundsException.)))
      (catch Throwable _))
    (is (= 2 (.. breaker getMetrics getNumberOfFailedCalls)))

    (try
      (with-circuit-breaker breaker
        (throw (ex-info "some other exception" {})))
      (catch Throwable _))
    (is (= 2 (.. breaker getMetrics getNumberOfFailedCalls)))))

(deftest test-build-config--ignore-exceptions
  (let [breaker (cb/circuit-breaker :some-name
                                    {:ignore-exceptions [NullPointerException
                                                         ArrayIndexOutOfBoundsException]})]
    (try
      (with-circuit-breaker breaker
        (throw (NullPointerException.)))
      (catch Throwable _))
    (is (zero? (.. breaker getMetrics getNumberOfFailedCalls)))

    (try
      (with-circuit-breaker breaker
        (throw (ArrayIndexOutOfBoundsException.)))
      (catch Throwable _))
    (is (zero? (.. breaker getMetrics getNumberOfFailedCalls)))

    (try
      (with-circuit-breaker breaker
        (throw (ex-info "some other exception" {})))
      (catch Throwable _))
    (is (= 1 (.. breaker getMetrics getNumberOfFailedCalls)))))

(deftest test-build-config--record-failure-predicate
  (let [breaker (cb/circuit-breaker
                 :some-name
                 {:record-failure-predicate #(= "record this" (.getMessage %))})]
    (try
      (with-circuit-breaker breaker
        (throw (ex-info "not this" {})))
      (catch Throwable _))
    (is (zero? (.. breaker getMetrics getNumberOfFailedCalls)))

    (try
      (with-circuit-breaker breaker
        (throw (ex-info "record this" {})))
      (catch Throwable _))
    (is (= 1 (.. breaker getMetrics getNumberOfFailedCalls)))))

(deftest test-build-config--ignore-exception-predicate
  (let [breaker (cb/circuit-breaker
                 :some-name
                 {:ignore-exception-predicate #(= "ignore this" (.getMessage %))})]
    (try
      (with-circuit-breaker breaker
        (throw (ex-info "record this" {})))
      (catch Throwable _))
    (is (= 1 (.. breaker getMetrics getNumberOfFailedCalls)))

    (try
      (with-circuit-breaker breaker
        (throw (ex-info "ignore this" {})))
      (catch Throwable _))
    (is (= 1 (.. breaker getMetrics getNumberOfFailedCalls)))))

(deftest test-build-config--sliding-window-type
  (let [build-config #'cb/build-config]
    (testing ":count-based"
      (let [config (build-config {:sliding-window-type :count-based})]
        (is (= CircuitBreakerConfig$SlidingWindowType/COUNT_BASED
               (.getSlidingWindowType config)))))

    (testing ":time-based"
      (let [config (build-config {:sliding-window-type :time-based})]
        (is (= CircuitBreakerConfig$SlidingWindowType/TIME_BASED
               (.getSlidingWindowType config)))))

    (testing "neither :count-based nor :time-based"
      (is (thrown? java.lang.IllegalArgumentException
                   (build-config {:sliding-window-type :other}))))))

;; -----------------------------------------------------------------------------
;; Registry

(deftest test-registry--given-config-maps
  (let [reg (cb/registry {:some   {:sliding-window-size 5}
                          "other" {:sliding-window-size 6}})]
    (testing "the default configuration"
      (let [cfg (.getConfiguration reg "default")]
        (is (true? (.isPresent cfg)))
        (is (= 100 (.. cfg get getSlidingWindowSize)))))

    (testing "the added configurations"
      (testing "the 'some' configuration"
        (let [some-cfg (.getConfiguration reg "some")]
          (is (true? (.isPresent some-cfg)))
          (is (= 5 (.. some-cfg get getSlidingWindowSize)))))

      (testing "the 'other' configuration"
        (let [other-cfg (.getConfiguration reg "other")]
          (is (true? (.isPresent other-cfg)))
          (is (= 6 (.. other-cfg get getSlidingWindowSize))))))))

(deftest test-registry--override-default
  (let [reg (cb/registry {:default {:sliding-window-size 10}})
        cfg (.getDefaultConfig reg)]
    (is (= 10 (.getSlidingWindowSize cfg)))))

(deftest test-get-all-circuit-breakers
  (let [reg (cb/registry)
        cb1 (atom nil)
        cb2 (atom nil)]
    (is (empty? (cb/all-circuit-breakers reg)))

    (reset! cb1 (cb/circuit-breaker! reg :some-circuit-breaker))
    (is (= #{@cb1} (cb/all-circuit-breakers reg)))

    (reset! cb2 (cb/circuit-breaker! reg :other-circuit-breaker))
    (is (= #{@cb1 @cb2} (cb/all-circuit-breakers reg)))))

(deftest test-get-all-circuit-breakers--no-registry-provided
  (let [reg (cb/registry)
        breaker (cb/circuit-breaker! reg :some-name {})]
    (with-redefs [cb/default-registry reg]
      (is (= #{breaker} (cb/all-circuit-breakers))))))

(deftest add-configuration!
  (let [reg (cb/registry)]
    (cb/add-configuration! reg :my-config {:sliding-window-size 6})

    (let [cfg-opt (.getConfiguration reg "my-config")]
      (is (true? (.isPresent cfg-opt)))
      (is (= 6 (.. cfg-opt get getSlidingWindowSize))))))

(deftest add-configuration!--no-registry-provided
  (let [reg (cb/registry)]
    (with-redefs [cb/default-registry reg]
      (cb/add-configuration! :my-config {:sliding-window-size 6})

      (let [cfg-opt (.getConfiguration reg "my-config")]
        (is (true? (.isPresent cfg-opt)))
        (is (= 6 (.. cfg-opt get getSlidingWindowSize)))))))

(deftest test-find
  (let [reg (cb/registry)
        breaker (cb/circuit-breaker! reg :some-name {})]
    (is (= breaker (cb/find reg :some-name)))))

(deftest test-find--no-matching-name
  (let [reg (cb/registry)]
    (is (nil? (cb/find reg :some-name)))))

(deftest test-find--no-registry-provided
  (let [reg (cb/registry)
        breaker (cb/circuit-breaker! reg :some-name {})]
    (with-redefs [cb/default-registry reg]
      (is (= breaker (cb/find :some-name))))))

(deftest test-remove!
  (let [reg (cb/registry)
        breaker (cb/circuit-breaker! reg :some-name {})]
    (is (= #{breaker} (cb/all-circuit-breakers reg)) "before removal")

    (let [result (cb/remove! reg :some-name)]
      (is (= breaker result))
      (is (empty? (cb/all-circuit-breakers reg))))))

(deftest test-remove!--no-registry-provided
  (let [reg (cb/registry)
        breaker (cb/circuit-breaker! reg :some-name {})]
    (with-redefs [cb/default-registry reg]
      (is (= #{breaker} (cb/all-circuit-breakers reg)) "before removal")

      (let [removed (cb/remove! :some-name)]
        (is (= breaker removed))
        (is (empty? (cb/all-circuit-breakers reg)))))))

(deftest test-remove!--no-matching-name
  (let [reg (cb/registry)
        breaker (cb/circuit-breaker! reg :some-name {})]
    (is (= #{breaker} (cb/all-circuit-breakers reg)) "before removal")

    (let [result (cb/remove! reg :other-name)]
      (is (nil? result))
      (is (= #{breaker} (cb/all-circuit-breakers reg))))))

(deftest test-replace!
  (let [reg (cb/registry)
        breaker (cb/circuit-breaker! reg :some-name {})
        new (cb/circuit-breaker! reg :some-name {})
        result (cb/replace! reg :some-name new)]
    (is (= breaker result))
    (is (= #{new} (cb/all-circuit-breakers reg)))))

(deftest test-replace!--no-matching-name
  (let [reg (cb/registry)
        breaker (cb/circuit-breaker :some-name {})
        result (cb/replace! reg :some-name breaker)]
    (is (nil? result))
    (is (empty? (cb/all-circuit-breakers reg)))))

(deftest test-replace!--mismatched-name
  ;; This is an interesting case because normally the registry will
  ;; have circuit breakers with names that match the name in the
  ;; registry itself. But using replace! you can change that.
  ;;
  ;; This test demonstrates that the end result of a replace! can
  ;; be a little unexpected...
  (let [reg (cb/registry)
        orig (cb/circuit-breaker! reg :some-name {})
        new (cb/circuit-breaker :other-name {})
        result (cb/replace! reg :some-name new)]
    (is (= result orig))
    (is (= #{new} (cb/all-circuit-breakers reg)))

    (is (= "other-name" (cb/name (cb/find reg :some-name))))
    (is (nil? (cb/find reg :other-name)))))

(deftest test-replace!--no-registry-provided
  (let [reg (cb/registry)]
    (with-redefs [cb/default-registry reg]
      (let [old (cb/circuit-breaker! reg :some-name {})
            new (cb/circuit-breaker :some-name {})
            replaced (cb/replace! :some-name new)]
        (is (= old replaced))
        (is (= #{new} (cb/all-circuit-breakers reg)))))))

;; -----------------------------------------------------------------------------
;; Registry Events

(deftest test-emit-registry-events!
  (let [reg (cb/registry)
        event-chan (async/chan 1)
        first-breaker (atom nil)
        second-breaker (cb/circuit-breaker :some-breaker {})]
    (cb/emit-registry-events! reg event-chan)

    (testing "when a circuit breaker is added to the registry"
      (reset! first-breaker (cb/circuit-breaker! reg :some-breaker))
      (let [event (take-with-timeout!! event-chan)]
        (is (= {:event-type :added
                :added-entry @first-breaker}
               (dissoc event :creation-time)))))

    (testing "when a circuit breaker is replaced in the registry"
      (cb/replace! reg :some-breaker second-breaker)
      (let [event (take-with-timeout!! event-chan)]
        (is (= {:event-type :replaced
                :old-entry @first-breaker
                :new-entry second-breaker}
               event))))

    (testing "when a circuit breaker is removed from the registry"
      (cb/remove! reg :some-breaker)
      (let [event (take-with-timeout!! event-chan)]
        (is (= {:event-type :removed
                :removed-entry second-breaker}
               event))))))

(deftest test-emit-registry-events!--no-registry-provided
  (let [reg (cb/registry)
        event-chan (async/chan 1)]
    (with-redefs [cb/default-registry reg]
      (cb/emit-registry-events! event-chan)
      (cb/circuit-breaker! reg :some-name))

    (let [event (take-with-timeout!! event-chan)]
      (is (= :added (:event-type event))))))

(deftest test-emit-registry-events!--with-only-filter
  (let [reg (cb/registry)
        event-chan (async/chan 1)]
    (cb/emit-registry-events! reg event-chan :only [:added])

    (testing "it raises the added event"
      (cb/circuit-breaker! reg :some-name)
      (let [event (take-with-timeout!! event-chan)]
        (is (= :added (:event-type event)))))

    (testing "it does not raise the removed event"
      (cb/remove! reg :some-name)
      (let [event (take-with-timeout!! event-chan)]
        (is (= :timeout event))))))

(deftest test-emit-registry-events!--with-exclude-filter
  (let [reg (cb/registry)
        event-chan (async/chan 1)]
    (cb/emit-registry-events! reg event-chan :exclude [:added])

    (testing "it does not raise the added event"
      (cb/circuit-breaker! reg :some-name)
      (let [event (take-with-timeout!! event-chan)]
        (is (= :timeout event))))

    (testing "it raises the removed event"
      (cb/remove! reg :some-name)
      (let [event (take-with-timeout!! event-chan)]
        (is (= :removed (:event-type event)))))))

(deftest test-emit-registry-events!-only-filter-trumps-exclude-filter
  (let [reg (cb/registry)
        event-chan (async/chan 1)]
    (cb/emit-registry-events! reg event-chan :only [:added] :exclude [:added])

    (testing "it raises the added event"
      (cb/circuit-breaker! reg :some-name)
      (let [event (take-with-timeout!! event-chan)]
        (is (= :added (:event-type event)))))

    (testing "it does not raise the removed event"
      (cb/remove! reg :some-name)
      (let [event (take-with-timeout!! event-chan)]
        (is (= :timeout event))))))

;; -----------------------------------------------------------------------------
;; Creation and fetching from registry

(deftest test-circuit-breaker!
  (testing "given only a name, it looks up the circuit breaker in the default-registry"
    (let [reg (cb/registry)]
      (with-redefs [cb/default-registry reg]
        (let [breaker (cb/circuit-breaker! :some-name)
              breaker2 (cb/circuit-breaker! :some-name)]
          (is (= breaker breaker2))
          (is (= #{breaker} (cb/all-circuit-breakers reg)))))))

  (testing "given a registry and a name"
    (let [reg (cb/registry)
          default-reg (cb/registry)]
      (with-redefs [cb/default-registry default-reg]
        (let [breaker (cb/circuit-breaker! reg :some-name)
              breaker2 (cb/circuit-breaker! reg :some-name)]
          (is (= breaker breaker2))
          (is (= #{breaker} (cb/all-circuit-breakers reg)))
          (is (empty? (cb/all-circuit-breakers default-reg))))))))

(deftest test-circuit-breaker!-with-matching-config-name
  (let [reg (cb/registry {"myConfig" {:sliding-window-size 6}})
        default-reg (cb/registry)
        breaker (cb/circuit-breaker! reg :some-breaker "myConfig")]
    (is (= (.get (.getConfiguration reg "myConfig"))
           (.getCircuitBreakerConfig breaker)))
    (is (= #{breaker} (cb/all-circuit-breakers reg)))
    (is (empty? (cb/all-circuit-breakers default-reg)))))

(deftest test-circuit-breaker!-with-nonmatching-config-name
  (let [reg (cb/registry {})]
    (is (thrown? ConfigurationNotFoundException
                 (cb/circuit-breaker! reg :some-breaker "myConfig")))))

(deftest test-circuit-breaker!-with-config-map
  (let [reg (cb/registry {})
        breaker (cb/circuit-breaker! reg :some-name {:sliding-window-size 6})]
    (is (= #{breaker} (cb/all-circuit-breakers reg)))
    (is (= 6 (.. breaker getCircuitBreakerConfig getSlidingWindowSize)))))

;; -----------------------------------------------------------------------------
;; Execution

(deftest test-execute
  (let [breaker (cb/circuit-breaker :some-name {})]
    (testing "when the circuit breaker is permitting calls"
      (let [result (cb/execute breaker mapv inc [1 2 3])]
        (is (= [2 3 4] result))))

    (testing "when the circuit breaker is not permitting calls"
      (cb/force-open! breaker)
      (is (thrown? CallNotPermittedException
                   (cb/execute breaker mapv inc [1 2 3]))))))

(deftest test-with-circuit-breaker
  (testing "it looks up the circuit breaker when a name is provided"
    (let [reg (cb/registry)
          captured (atom nil)]
      (with-redefs [cb/default-registry reg
                    cb/execute (fn [cb & _] (reset! captured cb))]
        (with-circuit-breaker :new-breaker
          true))

      (is (= #{@captured} (cb/all-circuit-breakers reg)))))

  (testing "it uses the circuit breaker when it is provided"
    (let [reg (cb/registry)
          breaker (cb/circuit-breaker :some-name {})
          captured (atom nil)]
      (with-redefs [cb/default-registry reg
                    cb/execute (fn [cb & _] (reset! captured cb))]
        (with-circuit-breaker breaker
          true))

      (is (empty? (cb/all-circuit-breakers reg)))
      (is (= breaker @captured))))

  (testing "it executes when the circuit breaker is permitting calls"
    (let [breaker (cb/circuit-breaker :some-name {})
          result (with-circuit-breaker breaker
                   (mapv inc [1 2 3]))]
      (is (= [2 3 4] result))))

  (testing "it throws an exception when the circuit breaker is not permitting calls"
    (let [breaker (cb/circuit-breaker :some-name {})]
      (cb/force-open! breaker)
      (is (thrown? CallNotPermittedException
                   (with-circuit-breaker breaker
                     (mapv inc [1 2 3])))))))

;; -----------------------------------------------------------------------------
;; Managing state

(deftest test-disable!
  (let [breaker (cb/circuit-breaker :some-name {})]
    (cb/disable! breaker)

    (is (= :disabled (cb/state breaker)))))

(deftest test-close!
  (let [breaker (cb/circuit-breaker :some-name {})]
    (cb/open! breaker)
    (cb/close! breaker)

    (is (= :closed (cb/state breaker)))))

(deftest test-open!
  (let [breaker (cb/circuit-breaker :some-name {})]
    (cb/open! breaker)

    (is (= :open (cb/state breaker)))))

(deftest test-half-open!
  (let [breaker (cb/circuit-breaker :some-name {})]
    (cb/open! breaker)
    (cb/half-open! breaker)

    (is (= :half-open (cb/state breaker)))))

(deftest test-force-open!
  (let [breaker (cb/circuit-breaker :some-name {})]
    (cb/force-open! breaker)

    (is (= :forced-open (cb/state breaker)))))

(deftest test-metrics-only!
  (let [breaker (cb/circuit-breaker :some-name {})]
    (cb/metrics-only! breaker)

    (is (= :metrics-only (cb/state breaker)))))

;; -----------------------------------------------------------------------------
;; Circuit breaker properties

(deftest test-name
  (let [breaker (cb/circuit-breaker :some-name {})]
    (is (= "some-name" (cb/name breaker)))))

(deftest test-state
  (let [breaker (cb/circuit-breaker :some-name {})]
    (is (= :closed (cb/state breaker)))

    (cb/metrics-only! breaker)
    (is (= :metrics-only (cb/state breaker)))))

(deftest test-permitting-calls?
  (let [breaker (cb/circuit-breaker :some-name {})]
    (is (true? (cb/permitting-calls? breaker)))

    (cb/force-open! breaker)
    (is (false? (cb/permitting-calls? breaker)) "after force open")

    (cb/close! breaker)
    (is (true? (cb/permitting-calls? breaker)) "after closing manually")))

;; -----------------------------------------------------------------------------
;; Circuit breaker events

(defn- check-base-event [event expected-event-type expected-circuit-breaker-name]
  (let [{:keys [event-type circuit-breaker-name creation-time]} event]
    (is (not= :timeout event))
    (is (= expected-event-type event-type))
    (is (= expected-circuit-breaker-name circuit-breaker-name))
    (is (instance? ZonedDateTime creation-time))))

(deftest test-emit-events!--on-success
  (let [event-chan (async/chan 1)
        breaker (cb/circuit-breaker :some-name {})]
    (cb/emit-events! breaker event-chan)

    (with-circuit-breaker breaker
      :done)

    (let [event (take-with-timeout!! event-chan)]
      (check-base-event event :success "some-name")
      (is (instance? Duration (:elapsed-duration event))))))

(deftest test-emit-events!--on-error
  (let [event-chan (async/chan 1)
        breaker (cb/circuit-breaker :some-name {})
        ex (ex-info "some message" {})]
    (cb/emit-events! breaker event-chan)

    (try
      (with-circuit-breaker breaker
        (throw ex))
      (catch Throwable _))

    (let [event (take-with-timeout!! event-chan)
          {:keys [throwable elapsed-duration]} event]
      (check-base-event event :error "some-name")
      (is (= ex throwable))
      (is (instance? Duration elapsed-duration)))))

(deftest test-emit-events!--on-state-transition
  (let [event-chan (async/chan 1)
        breaker (cb/circuit-breaker :some-name {})]
    (cb/emit-events! breaker event-chan)

    (cb/force-open! breaker)

    (let [event (take-with-timeout!! event-chan)
          {:keys [to-state from-state]} event]
      (check-base-event event :state-transition "some-name")
      (is (= :closed from-state))
      (is (= :forced-open to-state)))))

(deftest test-emit-events!--on-reset
  (let [event-chan (async/chan 1)
        breaker (cb/circuit-breaker :some-name {})]
    (cb/emit-events! breaker event-chan)

    (cb/reset! breaker)

    (let [event (take-with-timeout!! event-chan)]
      (check-base-event event :reset "some-name"))))

(deftest test-emit-events!--on-ignored-error
  (let [event-chan (async/chan 1)
        breaker (cb/circuit-breaker :some-name {:ignore-exception-predicate (constantly true)})
        ex (ex-info "some message" {:some :value})]
    (cb/emit-events! breaker event-chan)

    (try
      (with-circuit-breaker breaker
        (throw ex))
      (catch Throwable _))

    (let [event (async/<!! event-chan)
          {:keys [throwable elapsed-duration]} event]
      (check-base-event event :ignored-error "some-name")
      (is (= ex throwable))
      (is (instance? Duration elapsed-duration)))))

(deftest test-emit-events!--on-call-not-permitted
  (let [event-chan (async/chan 1)
        breaker (cb/circuit-breaker :some-name {})]
    ;; NOTE: cannot use force-open! here because that is
    ;; a state that does not allow events to be published
    (cb/open! breaker)
    (cb/emit-events! breaker event-chan)

    (try
      (with-circuit-breaker breaker
        true)
      (catch Throwable _))

    (let [event (take-with-timeout!! event-chan)]
      (check-base-event event :not-permitted "some-name"))))

(deftest test-emit-events!--on-fail-rate-exceeded
  (let [event-chan (async/chan 2)
        breaker (cb/circuit-breaker :some-name {:sliding-window-size 6})]

    (dotimes [_ 5]
      (try
        (with-circuit-breaker breaker
          (throw (ex-info "some ex" {})))
        (catch Throwable _)))

    (cb/emit-events! breaker event-chan)

    (try
      (with-circuit-breaker breaker
        (throw (ex-info "some ex" {})))
      (catch Throwable _))

    (let [_error-event (take-with-timeout!! event-chan)
          event (take-with-timeout!! event-chan)]
      (check-base-event event :failure-rate-exceeded "some-name"))))

(deftest test-emit-events!--slow-call-rate-exceeded
  (let [event-chan (async/chan 2)
        breaker (cb/circuit-breaker :some-name {:slow-call-duration-threshold 1
                                                :sliding-window-size 6})]
    (dotimes [_ 5]
      (with-circuit-breaker breaker
        (Thread/sleep 2)))

    (cb/emit-events! breaker event-chan)

    (with-circuit-breaker breaker
      (Thread/sleep 2))

    (let [_success-event (take-with-timeout!! event-chan)
          event (take-with-timeout!! event-chan)]
      (check-base-event event :slow-call-rate-exceeded "some-name"))))

(deftest test-emit-events!-with-only-filter
  (let [event-chan (async/chan 1)
        breaker (cb/circuit-breaker :some-name)]
    (cb/emit-events! breaker event-chan :only [:success])

    (testing "it raises the success event"
      (with-circuit-breaker breaker
        true)
      (let [event (take-with-timeout!! event-chan)]
        (is (= :success (:event-type event)))))

    (testing "it does not raise the error event"
      (try
        (with-circuit-breaker breaker
          (throw (ex-info "some ex" {})))
        (catch Throwable _))
      (let [event (take-with-timeout!! event-chan)]
        (is (= :timeout event))))))

(deftest test-emit-events!-with-exclude-filter
  (let [event-chan (async/chan 1)
        breaker (cb/circuit-breaker :some-name)]
    (cb/emit-events! breaker event-chan :exclude [:success])

    (testing "it does not raise the success event"
      (with-circuit-breaker breaker
        true)
      (let [event (take-with-timeout!! event-chan)]
        (is (= :timeout event))))

    (testing "it raises the error event"
      (try
        (with-circuit-breaker breaker
          (throw (ex-info "some ex" {})))
        (catch Throwable _))
      (let [event (take-with-timeout!! event-chan)]
        (is (= :error (:event-type event)))))))

(deftest test-emit-events!-only-filter-trumps-exclude-filter
  (let [event-chan (async/chan 1)
        breaker (cb/circuit-breaker :some-name)]
    (cb/emit-events! breaker event-chan :only [:success] :exclude [:success])

    (testing "it raises the success event"
      (with-circuit-breaker breaker
        true)
      (let [event (take-with-timeout!! event-chan)]
        (is (= :success (:event-type event)))))

    (testing "it does not raise the error event"
      (try
        (with-circuit-breaker breaker
          (throw (ex-info "some ex" {})))
        (catch Throwable _))
      (let [event (take-with-timeout!! event-chan)]
        (is (= :timeout event))))))
