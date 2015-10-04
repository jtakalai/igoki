(ns igoki.ui
  (:require [quil.core :as q]
            [igoki.util :as util])
  (:import (org.opencv.highgui VideoCapture)
           (org.opencv.core Mat)
           (javax.swing SwingUtilities JFrame JFileChooser)
           (org.opencv.video Video)))

(defn setup []
  (q/smooth)
  (q/frame-rate 5)
  (q/background 200))

(defn shadow-text [^String s x y]
  (q/text-align :left :bottom)
  (q/fill 0 196)
  (q/text-size 20)
  (q/text s (inc x) (inc y))

  (q/fill 255)
  (q/text-size 20)
  (q/text s x y))



(defn state [ctx] (:state @ctx))

(defmulti construct state)
(defmethod construct :default [ctx])

(defmulti destruct state)
(defmethod destruct :default [ctx])

(defmulti draw state)
(defmethod draw :default [ctx]
  (q/fill 255 64 78)
  (q/rect 0 0 (q/width) (q/height))
  (shadow-text (str "State not implemented: " (:state @ctx)) 10 25))

(defmulti mouse-dragged state)
(defmethod mouse-dragged :default [ctx])

(defmulti mouse-pressed state)
(defmethod mouse-pressed :default [ctx])

(defmulti key-pressed state)
(defmethod key-pressed :default [ctx])

(defn transition [ctx new-state]
  (destruct ctx)
  (swap! ctx assoc :state new-state)
  (construct ctx)
  ctx)

(defn start [ctx]
  (let [sketch
        (q/sketch
          :renderer :java2d
          :title "Goban panel"
          :setup setup
          :draw (partial #'draw ctx)
          :size [1280 720]
          :resizable true
          :mouse-dragged (partial #'mouse-dragged ctx)
          :mouse-pressed (partial #'mouse-pressed ctx)
          :key-pressed (partial #'key-pressed ctx))]
    (swap! ctx assoc :sketch sketch)))

;; Following code doesn't belong in here, but can move it out in due time.

(defonce ctx (atom {}))
(defn read-single [ctx camidx]
  (let [video (VideoCapture. (int camidx))
        frame (Mat.)]
    (Thread/sleep 500)
    (.read video frame)
    (swap!
      ctx update :camera assoc
      :raw frame
      :pimg (util/mat-to-pimage frame))
    (.release video)))

(defn stop-read-loop [ctx]
  (if-let [video ^VideoCapture (-> @ctx :camera :video)]
    (.release video))
  (swap! ctx update :camera assoc :stopped true :video nil))

(defn read-loop [ctx camidx]
  (let [^VideoCapture video (VideoCapture. camidx)]
    (swap! ctx update :camera assoc
           :video video
           :stopped false)
    (doto
      (Thread.
        ^Runnable
        (fn []

          (when-not (.isOpened video)
            (println "Error: Camera not opened"))
          (when-not (-> @ctx :camera :stopped)
            (try
              (let [frame (Mat.)]
                (.read video frame)
                (swap!
                  ctx update :camera assoc
                  :raw frame
                  :pimg (util/mat-to-pimage frame)))
              (Thread/sleep (or (-> @ctx :camera :read-delay) 500))
              (catch Exception e
                (println "Camera loop stopped, exception thrown")
                (stop-read-loop ctx)
                (throw e)))
            (recur))
          ))
      (.setDaemon true)
      (.start))))

(defn switch-read-loop [ctx camidx]
  (stop-read-loop ctx)
  (Thread/sleep (* 2 (or (-> @ctx :camera :read-delay) 500)))
  (read-loop ctx camidx))

(defn save-dialog [success-fn]
  (SwingUtilities/invokeLater
    #(let [frame (JFrame. "Save")
           chooser (JFileChooser.)]
      (try
        (.setAlwaysOnTop frame true)
        (when
          (= JFileChooser/APPROVE_OPTION (.showSaveDialog chooser frame))
          (success-fn (.getSelectedFile chooser)))
        (finally (.dispose frame))))))

#_(start (transition ctx :goban))