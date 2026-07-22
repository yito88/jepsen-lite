(ns lite.core
  "The runner. M0 is a single sequential loop over a hardcoded op list; the
   Jepsen generator/interpreter replaces the loop in M1, so the shape here
   (config in, completed ops out) is meant to survive."
  (:require [clojure.pprint :as pprint]
            [lite.client :as client]))

(def default-ops
  "A small op list exercising :ok, :fail and :info, used when a config omits
   :ops."
  [{:f :write, :value 1}
   {:f :read,  :value nil}
   {:f :cas,   :value [2 3]}
   {:f :write, :value 4, :simulate :timeout}])

(defn- invocation
  "Normalizes a user-supplied op into an invoked Jepsen-shaped op."
  [process op]
  (merge {:value nil} op {:type :invoke, :process process}))

(defn run
  "Runs `config`:

     {:adapter <a ClientAdapter>
      :handler (fn [conn op] ...)   ; the user's op -> target-call mapping
      :ops     [...]                ; optional; `default-ops` otherwise
      :target  {:type :in-process}} ; carried for later milestones; unused in M0

   Opens a conn, applies every op in order through the adapter, closes the conn,
   then prints and returns the vector of completed ops."
  [{:keys [adapter handler ops] :or {ops default-ops}}]
  (let [;; `invoke` takes no handler argument, so the adapter carries it. The
        ;; config is the user-facing place to put it; bind it in here.
        adapter (cond-> adapter handler (assoc :handler handler))
        conn (client/open adapter)
        history (try
                  (into [] (map-indexed
                            (fn [i op]
                              (client/invoke adapter conn
                                             (invocation i op))))
                        ops)
                  (finally
                    (client/close adapter conn)))]
    (run! pprint/pprint history)
    history))
