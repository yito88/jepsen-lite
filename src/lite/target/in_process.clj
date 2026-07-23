(ns lite.target.in-process
  "The `:in-process` target-type: the target runs inside Lite's own JVM, as an
   object the ClientAdapter opens.

   There is one instance, and every worker shares it, the way real clients share
   one server. It lives in an atom rather than being captured by each worker,
   because the crash nemesis replaces it mid-run and clients must follow it to
   the new one."
  (:require [clojure.tools.logging :refer [info]]
            [lite.client :as client]
            [lite.target :as target]))

(defrecord InProcess [adapter conn crashes lifecycle-lock]
  target/Connection
  (acquire! [this]
    ;; Workers come and go -- and a crashed process opens a fresh client -- but
    ;; they all want the instance that's live now, not one of their own. Opening
    ;; is a side effect, so it must not run inside swap!'s retryable function.
    ;; The same lock covers crash!'s whole close -> open transition: a replacement
    ;; worker that sees nil must wait for the graceful close to finish instead of
    ;; opening a second instance against the same storage concurrently.
    (locking lifecycle-lock
      (when-not @conn
        (reset! conn (client/open adapter))))
    this)

  (current [_this]
    @conn)

  (release! [_this]
    ;; A worker letting go of its client doesn't take the instance down with it.
    nil))

(defn crash!
  "Simulates a crash: destroy the instance and create a new one. Whether that
   costs any data is up to the target -- which is the interesting question.

   For the moment in between, there is no instance; ops that land in that window
   find nothing to talk to and are recorded as `:info`, which is the honest
   answer -- Lite can't know whether they took effect."
  [{:keys [adapter conn crashes lifecycle-lock]}]
  (locking lifecycle-lock
    (let [[old _] (reset-vals! conn nil)]
      (when old
        (client/close adapter old))
      (reset! conn (client/open adapter))
      (let [n (swap! crashes inc)]
        (info "Crashed the target instance" (str "(" n " so far)"))
        n))))

(defn crash-count
  "How many times this target has been crashed."
  [target]
  @(:crashes target))

(defmethod target/build :in-process [_target adapter]
  (map->InProcess {:adapter        adapter
                   :conn           (atom nil)
                   :crashes        (atom 0)
                   :lifecycle-lock (Object.)}))
