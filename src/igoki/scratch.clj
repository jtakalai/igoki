(ns igoki.scratch
  (:require
    [igoki.ui]
    [igoki.goban]
    [igoki.view]
    [igoki.game])
  (:require [kdtree])
  (:require [igoki.util :as util :refer [-->]]
            [igoki.ui :as ui])
  (:gen-class)
  (:import (org.opencv.objdetect CascadeClassifier)
           (org.opencv.highgui Highgui VideoCapture)
           (org.opencv.core MatOfRect Core Rect Point Scalar Mat Size MatOfPoint MatOfKeyPoint MatOfPoint2f Point3 TermCriteria MatOfPoint3 CvType MatOfPoint3f)
           (java.awt.image BufferedImage WritableRaster DataBufferByte)
           (java.awt Color Graphics KeyboardFocusManager KeyEventDispatcher Font RenderingHints)
           (java.io File)
           (javax.imageio ImageIO)
           (javax.swing JFrame JPanel)
           (org.opencv.imgproc Imgproc)
           (org.opencv.features2d FeatureDetector)
           (java.util List LinkedList ArrayList)
           (org.opencv.calib3d Calib3d)
           (java.awt.event KeyEvent MouseListener MouseEvent)
           (org.opencv.ml CvStatModel)))

;; This namespace represents some of the early igoki work, mostly to detect the actual Go Board.
;; it has been deprecated in favour of simply doing manual calibration due to the complexity
;; of dealing with the fickleness of variances in Go boards, lighting conditions, camera quality,
;; etc.
;;
;; It would be neat to have automatic handling, but it's not the core objective of this project to
;; detect Go boards - instead, it's focussed on bridging the gap between the digital and physical
;; game.

;; Step 1 - Define Corners of board
;; Step 2 - Verify coordinates
;; Step 3 - Choose mode: Local Kifu, OGS Kifu

(nu.pattern.OpenCV/loadShared)

(defonce camera (atom nil))



(defonce appstate
         (atom
           {:images      [{} {} {} {} {}]
            :goban-corners [[670 145] [695 900] [1320 855] [1250 220]]
            :input :camera
            :selected    -1
            :frozen      false}))



(defn update-image-mat! [slot image title]
  (swap! appstate #(assoc-in % [:images slot] {:mat image :title title})))

(defn reset-to-index! []
  (swap! appstate assoc :selected -1))

(defn rotate-slot-left! []
  (swap! appstate (fn [i] (update i :selected #(mod (dec %) (count (:images i)))))))

(defn rotate-slot-right! []
  (swap! appstate (fn [i] (update i :selected #(mod (dec %) (count (:images i)))))))

(defn select-frame! [n]
  (swap! appstate (fn [i] (assoc i :selected (mod n (count (:images i)))))))

(defn toggle-freeze! []
  (println "Freeze toggle")
  (swap! appstate update-in [:frozen] not))

(defn save-image [^BufferedImage img]
  (ImageIO/write img "png" (File. "resources/new.png")))

(defn load-image [^String file]
  (ImageIO/read (File. file)))

(defn handle-keypress [^KeyEvent e]
  (println "Key pressed: " (.getKeyCode e) " - Shift: " (.isShiftDown e) )
  (when (= (.getID e) KeyEvent/KEY_PRESSED)
    (case (.getKeyCode e)
      67 (swap! appstate assoc :input :camera)
      82 (swap! appstate assoc :input (Highgui/imread "resources/goboard.png"))
      32 (toggle-freeze!)
      27 (reset-to-index!)
      49 (select-frame! 1)
      50 (select-frame! 2)
      51 (select-frame! 3)
      52 (select-frame! 4)
      53 (select-frame! 5)
      54 (select-frame! 6)
      55 (select-frame! 7)
      56 (select-frame! 8)
      57 (select-frame! 9)
      48 (select-frame! 0)

      10 (swap! appstate assoc :accepted true)
      false)))

(defn draw-title [g title x y]
  (.setColor g (Color/BLACK))
  (.drawString g title (dec x) (dec y))
  (.setColor g (Color/WHITE))
  (.drawString g title x y))

(defn draw-index [^JFrame frame ^Graphics g {:keys [images]}]
  (let [gridsize (Math/ceil (Math/sqrt (count images)))
        gw (/ (.getWidth frame) gridsize)]
    (doseq [[c {:keys [mat title]}] (map-indexed vector images)]
      (if-let [image (if (pos? (.width mat)) (util/mat-to-buffered-image mat))]
        (let [ratio (if image (/ (.getHeight image) (.getWidth image)))
              x (* (mod c gridsize) gw)
              y (* (Math/floor (/ c gridsize)) ratio gw)]
          (.drawImage g image (int x) (int y) (int gw) (int (* ratio gw)) nil)
          (draw-title g (str title ", Slot: " c) (int (+ x 5)) (int (+ y 15)))
          )))))

(defn render [^JFrame frame ^Graphics g]
  (let [{:keys [images selected] :as state} @appstate
        {:keys [mat title] :as im} (get images selected)
        image (if (and im (pos? (.getWidth frame))) (util/mat-to-buffered-image mat))
        ratio (if image (/ (.getHeight image) (.getWidth image)))]
    (.setRenderingHints g (RenderingHints. RenderingHints/KEY_INTERPOLATION RenderingHints/VALUE_INTERPOLATION_BICUBIC))
    (if (or (= selected -1) (nil? image))
      (draw-index frame g state)
      (do
        (.drawImage g image 0 0 (.getWidth frame) (* ratio (.getWidth frame)) nil)
        (draw-title g (str title ", Slot: " selected) 5 15)))))

(defn click-mouse [^MouseEvent e]
  )

(defn window [text x y]
  (let [frame (JFrame.)]
    (.add (.getContentPane frame)
          (proxy [JPanel] []
            (paint [^Graphics g]
              (render frame g))
            ))

    (.addMouseListener
      frame
      (proxy [MouseListener] []
        (mousePressed [^MouseEvent e]
          (click-mouse e))))

    (.addKeyEventDispatcher (KeyboardFocusManager/getCurrentKeyboardFocusManager)
                            (proxy [KeyEventDispatcher] []
                              (dispatchKeyEvent [e]
                                (handle-keypress e)
                                false)))
    (doto frame
      (.setDefaultCloseOperation JFrame/DISPOSE_ON_CLOSE)
      (.setTitle text)
      (.setResizable true)
      (.setSize 800 600)
      (.setLocation x y)
      (.setVisible true))))

(defn highlight-faces [image]
  (let [face-detector (CascadeClassifier. (.getAbsolutePath (clojure.java.io/file "resources/lbpcascade_frontalface.xml")))
        face-detections (MatOfRect.)]
    (.detectMultiScale face-detector image face-detections)
    (doseq [^Rect r (seq (.toArray face-detections))]
      (Core/rectangle image (Point. (.-x r) (.-y r)) (Point. (+ (.-x r) (.-width r)) (+ (.-y r) (.-height r))) (Scalar. 0 255 0)))
    ))

(defn matofpoint-vec
  "Convert MatOfPoint to vector list [[x y] [x y] ...]"
  [mat]
  (for [p (.toList mat)]
    [(.-x p) (.-y p)]))


(def target-homography
  {:9x9
   (doto (MatOfPoint2f.)
     (.fromList (for [x (range 1 3) y (range 1 2)] (Point. (* 70.0 x) (* 70.0 y)))))
   :13x13
   (util/vec->mat (MatOfPoint2f.) [[70 70] [70 980] [980 980] [980 70]])
   :19x19
   (doto (MatOfPoint2f.)
     (.fromList (for [x (range 1 2) y (range 1 2)] (Point. (* 70.0 x) (* 70.0 y)))))})

(def current-transform (atom nil))

(defn count-perimeter [mat]
  (let [m (for [x (range (.rows mat))] (seq (.get mat x 0)))]
    (first
      (reduce
        (fn [[r [ax ay :as a]] [x y :as p]]
          (cond
            (nil? a) [r p]
            :else
            [(+ r (Math/sqrt (+ (* (- ax x) (- ax x)) (* (- ay y) (- ay y))))) p]))
        [0 nil] (concat [(last m)] m)))))

(defn find-goban [gray-img colour]
  (let [sorted (:goban-corners @appstate)
        s (map-indexed vector sorted)
        c (--> gray-img (Imgproc/Canny 100 50 3 false))]
    (update-image-mat! 5 c "Contours")
    (reduce
      (fn [[i [ax ay :as a]] [_ [x y] :as p]]
        (cond
          (nil? a) p
          :else
          (do
            (Core/line colour (Point. ax ay) (Point. x y) (Scalar. 0 0 (- 255 (* i 32))) 5)
            p)))
      nil (concat s [(first s)]))
    sorted
    )

  #_(let [contours (ArrayList.) hier (Mat.)
          c (--> gray-img (Imgproc/Canny 100 50 3 false))]
      ;; Find contours
      (Imgproc/findContours c
                            contours hier Imgproc/RETR_TREE Imgproc/CHAIN_APPROX_NONE (Point. 0 0))
      (update-image-mat! 5 c "Contours")

      ;; Find largest area 4-cornered polygon
      (let
        [[sq x _]
         (loop [[x & xs] (range 0 (.cols hier))
                [_ _ rarea :as result] nil]
           (if (nil? x)
             result
             (let [sq (MatOfPoint2f.)
                   c (nth contours x)
                   area (Imgproc/contourArea c)
                   perim (count-perimeter c)]
               (Imgproc/approxPolyDP (doto (MatOfPoint2f.) (.fromArray (.toArray c))) sq (* perim 0.02) true)
               (cond
                 (and (= 4 (.rows sq)) (> area (or rarea 0)))
                 (recur xs [sq x area])

                 :else (recur xs result)))))

         ;; Sort the corners
         sorted
         (if-not (nil? sq)
           (let [pointlist (matofpoint-vec sq)
                 closest-to-origin (first (sort-by #(apply + %) (matofpoint-vec sq)))
                 ;; Rotate corners until closest-to-origin is first
                 [p1 [_ pfy :as pf] pc [_ ply :as pl] :as rotated] (take 4 (drop-while #(not= closest-to-origin %) (concat pointlist pointlist)))]
             ;; Flip so that 'top right' point is next
             (if (< pfy ply) rotated [p1 pl pc pf])))]

        ;; Draw the actual matching contour
        (Imgproc/drawContours colour contours x
                              (Scalar. 0 0 255) 1 0 hier 1 (Point. 0 0))

        ;; Draw the "board" polygon.
        (let [s (map-indexed vector sorted)]
          (reduce
            (fn [[i [ax ay :as a]] [_ [x y] :as p]]
              (cond
                (nil? a) p
                :else
                (do
                  (Core/line colour (Point. ax ay) (Point. x y) (Scalar. 0 0 (- 255 (* i 32))) 5)
                  p)))
            nil (concat s [(first s)])))

        sorted)))


(defn old-process [w calibration frame]
  (do
    #_(highlight-faces frame)
    (let [fil (Mat.) m (Mat.) m2 (Mat.) edges (Mat.) hough (Mat.) hough-img (Mat.)
          corners (MatOfPoint.) corners2f (MatOfPoint2f.)
          timg (Mat.) detectimg (Mat.)

          dest
          (-->
            frame
            (Imgproc/cvtColor Imgproc/COLOR_BGR2GRAY)
            (Imgproc/bilateralFilter 5 (double 155) (double 105))
            )

          colour
          (-->
            dest
            (Imgproc/cvtColor Imgproc/COLOR_GRAY2BGR))
          [p1 pf _ pl :as goban-corners] (find-goban dest colour)
          goban-contour (util/vec->mat (MatOfPoint2f.) goban-corners)]
      (Imgproc/goodFeaturesToTrack dest corners 1000 0.03 15 (Mat.) 10 false 0.1)
      (.fromArray corners2f (.toArray corners))
      (Imgproc/cornerSubPix dest corners2f (Size. 11 11) (Size. -1 -1)
                            (TermCriteria. (bit-or TermCriteria/EPS TermCriteria/COUNT) 30 0.1))

      #_(println goban-corners)
      #_(println (filter
                   #(pos? (Imgproc/pointPolygonTest goban-corners % true))
                   (seq (.toArray corners2f))))


      (let
        [goban-points
         (->>
           (seq (.toArray corners2f))
           (filter
             #(> (Imgproc/pointPolygonTest goban-contour % true) -10))
           (map (fn [p] [(.-x p) (.-y p)])))
         _ (println (count goban-points))
         {:keys [size target]}
         (condp < (count goban-points)
           400 {:size 19 :target (:19x19 target-homography)}
           100 {:size 13 :target (:13x13 target-homography)}
           {:size 9 :target (:9x9 target-homography)}
           )
         sorted
         (sort-by
           (juxt
             (comp #(int (/ % 25)) (partial util/line-to-point-dist [p1 pf]))
             (comp #(int (/ % 25)) (partial util/line-to-point-dist [p1 pl])))
           (take (.rows target) goban-points))
         origpoints
         (doto (MatOfPoint2f.)
           (.fromList (map (fn [[x y]] (Point. x y)) goban-corners)))
         h (if (= (.rows target) (count goban-corners))
             (Calib3d/findHomography origpoints target Calib3d/FM_RANSAC 3))]


        (if h
          (reset! current-transform h))

        (when-let [h @current-transform]
          (let [transformed (Mat.) ih (Mat.) invert-transformed (Mat.)]
            (Core/perspectiveTransform corners2f transformed h)
            #_(Core/perspectiveTransform target invert-transformed ih)

            (Imgproc/warpPerspective frame timg h (.size frame))
            (Imgproc/warpPerspective frame detectimg h (.size frame))

            #_(Imgproc/erode detectimg detectimg (Imgproc/getStructuringElement Imgproc/MORPH_RECT (Size. 10 10)))
            (doseq [c (range 0 (.rows transformed))]
              (let [[x1 y1 :as p] (seq (.get transformed c 0))]
                #_(Core/putText hough-img (str c) (Point. x1 y1) Core/FONT_HERSHEY_COMPLEX 1 (Scalar. 0 255 0) 2)
                (Core/circle colour (Point. x1 y1) 2 (Scalar. 128 255 0) 2)
                ))
            (doseq [c (range 0 (.rows invert-transformed))]
              (let [[x1 y1 :as p] (seq (.get invert-transformed c 0))]
                #_(Core/putText hough-img (str c) (Point. x1 y1) Core/FONT_HERSHEY_COMPLEX 1 (Scalar. 0 255 0) 2)
                (Core/circle colour (Point. x1 y1) 2 (Scalar. 0 255 0 128) 10)
                ))
            )
          (Core/rectangle detectimg (Point. 70 70) (Point. 980 980) (Scalar. 0 255 0) 1)

          (doseq [x (range 1 (inc size))]
            (doseq [y (range 1 (inc size))]
              (let [p (Point. (- (* 75.0 x) 15) (- (* 75.0 y) 15))
                    roi (Rect. p (Size. 30 30))
                    m (Mat. detectimg roi)
                    a (Core/mean m)
                    c (int (first (seq (.-val a))))
                    text (cond (< c 30) "B" (> c 200) "W")]
                (when text
                  (Core/putText timg text p Core/FONT_HERSHEY_COMPLEX 1 (Scalar. 0 0 255) 1.5))
                (Core/rectangle detectimg p (Point. (+ (.-x p) 30) (+ (.-y p) 30)) (Scalar. 0 255 0) 1)
                (Core/circle timg (Point. (+ (.-x p) 15) (+ (.-y p) 15)) 5 a 5))))))

      #_(doseq [{[x y] :value :as p}
                (filter #(> (:strength (meta %)) 10) (kdtree-seq @stable-points))]
          (Core/circle colour (Point. x y)
                       (/ (Math/min (or (:strength (meta p)) 1) 100) 5) (Scalar. 255 25 25) 2))

      (doseq [p (seq (.toArray corners2f))]
        (Core/circle colour p 2 (Scalar. 255 0 255) 3))


      (update-image-mat! 0 frame "Source")
      (update-image-mat! 1 dest "Find points")
      (update-image-mat! 2 colour "Find points")
      (update-image-mat! 3 timg "Detected goban")
      (update-image-mat! 4 detectimg "Check for pieces")
      #_(when-not (.empty timg)
          (update-image-mat! 2 timg "Perspective Shifted")
          (update-image-mat! 3 detectimg "Detect Stones")
          )
      (.repaint w)
      true)))

(defn refresh-camera [w camera frame]
  (Thread/sleep 100)
  (let [{:keys [frozen calib-corners calibration input] :as state} @appstate
        read (if frozen true (if (= input :camera) (.read camera frame) false))
        frame (if (= input :camera) frame input)]
    (cond
      (not read) false
      :else
      (old-process w calibration frame)))
  (.repaint w))



(defn capture [camidx]
  (let [camera (VideoCapture. camidx)
        frame (Mat.)]
    (.read camera frame)

    (cond
      (not (.isOpened camera)) (println "Error: camera not opened")
      :else
      (do
        (update-image-mat! 0 frame "Source")
        (let [w (window "Original" 0 0)]
          (swap! appstate assoc :diag-window w)
          (doto
            (Thread.
              #(loop []
                (try
                  (refresh-camera w camera frame)
                  (catch Exception e
                    (.printStackTrace e)
                    (Thread/sleep 5000)))
                (recur)))
            (.setDaemon true)
            (.start)))))
    #_(.release camera)))
