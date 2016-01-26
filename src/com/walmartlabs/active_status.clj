(ns com.walmartlabs.active-status
  (:require [clojure.core.async :refer [chan close! dropping-buffer go-loop put! alts!]]
            [medley.core :as medley]
            [io.aviso.ansi :as ansi]))

(defn- increment-line
  [job]
  (update job :line inc))

(defn- setup-new-job
  [jobs job-ch]
  (println)                                                 ; Add a new line for the new job
  (as-> jobs %
        (medley/map-vals increment-line %)
        (assoc % job-ch {:line 1})))

(defn- update-jobs-status
  [jobs job-ch value]
  ;; A nil indicates that the channel closed, but we still keep the final
  ;; status message for the job around. For now, we also have a leak:
  ;; we keep a reference to the close channel forever.
  (if value
    (assoc-in jobs [job-ch :status] value)
    jobs))

(defn- output-jobs
  [jobs]
  #_ (println (vals jobs))
  (doseq [{:keys [line status]} (vals jobs)]
    (print (str ansi/csi "s"                                ; save cursor position
                ansi/csi line "A"                           ; cursor up
                ansi/csi "1G"                               ; cursor horizontal absolute
                ansi/csi "K"                                ; clear to end-of-line
                ; TODO: font changes based on age of status message
                status
                ansi/csi "u"                                ; restore cursor position
                ))
    (flush)))

(defn- start-process
  [new-jobs-ch]
  ;; jobs is keyed on job channel, value is the data about that job
  (go-loop [jobs {}]
    (let [ports (into [new-jobs-ch] (keys jobs))
          [v ch] (alts! ports)]
      (cond
        (and (nil? v) (= ch new-jobs-ch))
        nil                                                 ; shutdown

        (= ch new-jobs-ch)
        (recur (setup-new-job jobs v))

        :else
        (do
          (let [jobs' (update-jobs-status jobs ch v)]
            (output-jobs jobs')
            (recur jobs')))))))

(defn status-tracker
  "Creates a new status tracker ... you should only have one of these at a time
  (or they will interfere with each other).

  The tracker runs as a core.async process; a channel is returned.

  Close the channel to shut down the status tracker.

  Otherwise, the returned channel is used when adding jobs to the tracker."
  []
  (let [ch (chan 1)]
    (start-process ch)
    ch))

(defn add-job
  "Adds a new job, returning a channel for updates to the job."
  [tracker-ch]
  (let [ch (chan (dropping-buffer 1))]
    (put! tracker-ch ch)
    ch))


