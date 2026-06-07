(ns orca.plot
  "Headless PNG chart helpers (XChart / Java2D) — the plotting layer for the
   analysis. The goal is *content* parity with the committed reference figures,
   not pixel parity: violin plots become XChart box plots, the rest map directly.

   Each helper writes a PNG to `path` (creating parent dirs) and returns the
   path. Charts render through AWT/Java2D in headless mode.

   Helpers:
   - `histogram`  — binned distribution of a numeric sample.
   - `bar`        — categorical bar chart (string x-axis).
   - `forest`     — horizontal mean + 89% ETI whiskers, one row per parameter
                    (the coefficient/effect plots). Whiskers are drawn as line
                    segments so the asymmetric ETI endpoints are exact.
   - `box`        — box plots, one box per group (the violin stand-in).
   - `trace`      — one line per MCMC chain (sampling diagnostics).
   - `compare`    — model-comparison dot + symmetric SE error bar (WAIC/elpd)."
  (:refer-clojure :exclude [compare])
  (:require
   [clojure.java.io :as io])
  (:import
   (java.awt Color)
   (java.util.function Function)
   (org.knowm.xchart BitmapEncoder BoxChartBuilder CategoryChartBuilder Histogram XYChartBuilder)
   (org.knowm.xchart.style.markers SeriesMarkers)))

;; Render without a display (no-op if AWT is already up; harmless on servers).
(System/setProperty "java.awt.headless" "true")

;; Two nested enum/inner classes are referenced fully-qualified (no import) so
;; the single org.knowm.xchart import line the formatter produces stays within
;; the 100-char limit.
(def ^:private png-format org.knowm.xchart.BitmapEncoder$BitmapFormat/PNG)
(def ^:private render-line org.knowm.xchart.XYSeries$XYSeriesRenderStyle/Line)
(def ^:private render-scatter org.knowm.xchart.XYSeries$XYSeriesRenderStyle/Scatter)

(defn- save!
  "Write `chart` to `path` as PNG and return `path`."
  [chart path]
  (io/make-parents path)
  (with-open [os (io/output-stream path)]
    (BitmapEncoder/saveBitmap chart os png-format))
  path)

(defn- index-label-fn
  "A `java.util.function.Function` mapping an axis value (a Double near an
   integer index) to `labels[idx]`, blank off-grid — used to put category names
   on a numeric axis."
  [labels]
  (let [v (vec labels)]
    (reify Function
      (apply [_ x]
        (let [i (Math/round (double x))]
          (if (and (>= i 0) (< i (count v))) (nth v i) ""))))))

(defn histogram
  "Histogram of numeric `xs` into `:bins` bins (default 30)."
  [path xs {:keys [title bins x-label y-label]
            :or   {title "" bins 30 x-label "value" y-label "count"}}]
  (let [data  (java.util.ArrayList. ^java.util.Collection (map double xs))
        h     (Histogram. data (int bins))
        chart (-> (CategoryChartBuilder.)
                  (.width 700) (.height 500)
                  (.title title) (.xAxisTitle x-label) (.yAxisTitle y-label)
                  (.build))]
    (.addSeries chart "x" (.getxAxisData h) (.getyAxisData h))
    (doto (.getStyler chart)
      (.setLegendVisible false)
      (.setXAxisLabelRotation 90))
    (save! chart path)))

(defn bar
  "Bar chart of `values` over string `cats`."
  [path cats values {:keys [title x-label y-label]
                     :or   {title "" x-label "" y-label "value"}}]
  (let [chart (-> (CategoryChartBuilder.)
                  (.width 800) (.height 500)
                  (.title title) (.xAxisTitle x-label) (.yAxisTitle y-label)
                  (.build))]
    (.addSeries chart "y" (vec cats) (mapv double values))
    (doto (.getStyler chart)
      (.setLegendVisible false)
      (.setXAxisLabelRotation 30))
    (save! chart path)))

(defn forest
  "Forest plot: each row `{:label :mean :lo :hi}` becomes a horizontal whisker
   (lo→hi line) with a mean marker, stacked top-to-bottom in input order."
  [path rows {:keys [title x-label]
              :or   {title "" x-label "value"}}]
  (let [rows  (vec rows)
        n     (count rows)
        ys    (vec (range n))
        chart (-> (XYChartBuilder.)
                  (.width 800) (.height (max 300 (* 60 n)))
                  (.title title) (.xAxisTitle x-label) (.yAxisTitle "")
                  (.build))]
    ;; whiskers — one short line series per row (legend suppressed)
    (doseq [[i {:keys [lo hi]}] (map-indexed vector rows)]
      (doto (.addSeries chart (str "ci" i)
                        (double-array [lo hi]) (double-array [i i]))
        (.setXYSeriesRenderStyle render-line)
        (.setMarker SeriesMarkers/NONE)
        (.setLineColor Color/GRAY)))
    ;; means — a single scatter overlay
    (doto (.addSeries chart "mean"
                      (double-array (map :mean rows)) (double-array ys))
      (.setXYSeriesRenderStyle render-scatter)
      (.setMarkerColor Color/BLACK))
    (doto (.getStyler chart)
      (.setLegendVisible false)
      (.setyAxisTickLabelsFormattingFunction
       (index-label-fn (map :label rows))))
    (save! chart path)))

(defn box
  "Box plots from `groups`, an ordered seq of `[label numbers]` pairs."
  [path groups {:keys [title y-label] :or {title "" y-label "value"}}]
  (let [chart (-> (BoxChartBuilder.)
                  (.width 800) (.height 500)
                  (.title title) (.yAxisTitle y-label)
                  (.build))]
    (doseq [[label nums] groups]
      (.addSeries chart (str label) (double-array (map double nums))))
    (doto (.getStyler chart)
      (.setLegendVisible false)
      (.setXAxisLabelRotation 30))
    (save! chart path)))

(defn trace
  "Trace plot: `chains` is a seq of per-chain numeric sequences; each becomes a
   line over its iteration index."
  [path chains {:keys [title y-label] :or {title "" y-label "value"}}]
  (let [chart (-> (XYChartBuilder.)
                  (.width 900) (.height 400)
                  (.title title) (.xAxisTitle "iteration") (.yAxisTitle y-label)
                  (.build))]
    (doseq [[i chain] (map-indexed vector chains)]
      (doto (.addSeries chart (str "chain " (inc i))
                        (double-array (range (count chain)))
                        (double-array (map double chain)))
        (.setXYSeriesRenderStyle render-line)
        (.setMarker SeriesMarkers/NONE)))
    (save! chart path)))

(defn compare
  "Model-comparison plot: each row `{:label :value :se}` becomes a point with a
   symmetric ±se vertical error bar, ordered along the x-axis."
  [path rows {:keys [title y-label] :or {title "" y-label "elpd"}}]
  (let [rows  (vec rows)
        n     (count rows)
        chart (-> (XYChartBuilder.)
                  (.width 800) (.height 500)
                  (.title title) (.xAxisTitle "") (.yAxisTitle y-label)
                  (.build))]
    (doto (.addSeries chart "model"
                      (double-array (range n))
                      (double-array (map :value rows))
                      (double-array (map :se rows)))
      (.setXYSeriesRenderStyle render-scatter))
    (doto (.getStyler chart)
      (.setLegendVisible false)
      (.setxAxisTickLabelsFormattingFunction
       (index-label-fn (map :label rows))))
    (save! chart path)))
