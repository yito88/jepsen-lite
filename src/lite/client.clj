(ns lite.client
  "The ClientAdapter abstraction: the protocol a user implements to bind Jepsen
   Lite to their target's *protocol*, plus the outcome signalling
   (`fail!` / `info!`) and the exception -> `:type` wrapping their handlers rely
   on.

   Nothing in this namespace knows how the target is deployed (in-process,
   local-process, http, compose, ...). That is the other, orthogonal axis and it
   must never leak in here.")

(defprotocol ClientAdapter
  "Binds Jepsen Lite to one target protocol.

   `open` and `close` must be idempotent / safely re-runnable: they are called
   repeatedly over a run (a later milestone implements the crash nemesis as
   close -> open)."
  (open [this]
    "Establishes a connection / instance and returns the conn handle.")
  (invoke [this conn op]
    "Applies one op against conn by running the user's handler through
     `complete`, and returns the completed, `:type`-tagged op.")
  (close [this conn]
    "Tears conn down. Must tolerate an already-closed or nil conn."))

;; ## Outcome signalling
;;
;; Users signal an outcome from inside their handler by throwing, rather than by
;; returning a sentinel value: an early-return-via-throw reads more naturally in
;; a handler for users who are not seasoned Clojure programmers. Do not change
;; this without discussion.

(def ^:private outcome-key :lite/outcome)

(defn fail!
  "Signals a *certain* failure from inside a handler: the operation definitely
   did not take effect (CAS mismatch, rejected write, ...). Yields `:type :fail`."
  [msg]
  (throw (ex-info (str "lite/fail! " (pr-str msg))
                  {outcome-key :fail, :lite/detail msg})))

(defn info!
  "Signals an *indeterminate* outcome from inside a handler: the operation may
   or may not have taken effect (timeout, dropped connection, ...). Yields
   `:type :info`."
  [reason]
  (throw (ex-info (str "lite/info! " (pr-str reason))
                  {outcome-key :info, :lite/detail reason})))

(defn- signalled-outcome
  "The outcome a `fail!`/`info!` throwable carries, or nil for anything else."
  [^Throwable t]
  (when (instance? clojure.lang.IExceptionInfo t)
    (get (ex-data t) outcome-key)))

(defn- unexpected-error
  "Describes an exception the handler did not deliberately signal."
  [^Throwable t]
  {:type    :unexpected-exception
   :class   (.getName (class t))
   :message (.getMessage t)})

(defn complete
  "Runs `(handler conn op)` and turns its result into a completed op:

     returns normally     -> `:ok`, return value attached as `:value`
     throws `fail!`       -> `:fail`, msg attached as `:error`
     throws `info!`       -> `:info`, reason attached as `:error`
     throws anything else -> `:info` (an unexpected error is indeterminate; we
                             never call it `:ok`, nor a clean `:fail`)

   All other fields of the invoked op (`:f`, `:process`, ...) are preserved."
  [handler conn op]
  (try
    (assoc op :type :ok, :value (handler conn op))
    (catch Throwable t
      (case (signalled-outcome t)
        :fail (assoc op :type :fail, :error (:lite/detail (ex-data t)))
        :info (assoc op :type :info, :error (:lite/detail (ex-data t)))
        (assoc op :type :info, :error (unexpected-error t))))))
