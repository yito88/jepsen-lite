(ns lite.target
  "Target-types: how the target under test is deployed, and therefore what its
   connection lifecycle looks like and which faults can be injected into it.

   This is the second axis. The ClientAdapter binds Lite to the target's
   *protocol* and knows nothing about deployment; a target-type owns deployment
   and knows nothing about the protocol. Workloads know about neither.")

(defprotocol Connection
  "How the client bridge gets at the live target. Implemented per target-type,
   because who owns a connection's lifetime is a deployment question: an
   in-process instance is shared by every worker and outlives all of them, while
   a remote target would hand each worker its own."
  (acquire! [this]
    "A worker is opening a client. Ensure there is something to talk to.")
  (current [this]
    "The conn to use for the op being invoked right now. Read this per op --
     a fault may have replaced the target since the last one.")
  (release! [this]
    "A worker is done with its client."))

(def target-types
  "Every target-type in the design. Only those with a `build` method are
   runnable; the rest arrive in later milestones."
  [:in-process :http :local-process :compose])

(defmulti build
  "Builds the Connection for a target config, around the user's ClientAdapter."
  (fn [target _adapter] (:type target)))

(defmethod build :default [target _adapter]
  (let [type (:type target)]
    (throw (ex-info
            (if (some #{type} target-types)
              (str "Target-type " (pr-str type) " isn't implemented yet."
                   " Implemented so far: "
                   (pr-str (vec (sort (remove #{:default} (keys (methods build)))))) ".")
              (str "Unknown target-type " (pr-str type) "."
                   " Known target-types: " (pr-str target-types) "."))
            {:lite/error  :unusable-target-type
             :target-type type}))))

(defn validate!
  "Checks a target config can actually be run, before anything is built."
  [target]
  (when-not (contains? (methods build) (:type target))
    (build target nil)))
