(ns orca.findings-test
  "Unit tests for the pure parts of orca.findings (the per-level breakdowns,
   contingency assembly, confound checks, and the Bayesian-prediction
   arithmetic), checked against hand-computed values. MCMC fits are exercised
   end-to-end elsewhere."
  (:require
   [clojure.test :refer [deftest is testing]]
   [orca.findings :as findings]
   [tablecloth.api :as tc]))

(defn- close?
  ([a b] (close? a b 1.0e-9))
  ([a b tol] (< (Math/abs (- (double a) (double b))) tol)))

;; A tiny hand-built modeling dataset + metadata. antifoul categories ordered so
;; Black=0, Coppercoat=1; interaction 1=incident.
(def md {:categories {:antifoul ["Black" "Coppercoat"]
                      :sailing_mode ["Motoring" "Sailing"]}})

(def data
  (tc/dataset
    {:antifoul_idx [0 0 0 1 1]            ; 3 Black, 2 Coppercoat
     :interaction  [1 1 0 1 0]            ; Black: 2 inc/1 une; Copper: 1 inc/1 une
     :is_daytime   [1 0 1 1 0]            ; Night: rows 1,4 (idx 0-based)
     :depth_ord_std [1.0 2.0 0.5 -1.0 -0.5]}))

(deftest antifoul-levels-test
  (let [levels (findings/antifoul-levels data md)]
    (is (= [{:idx 0 :category "Black" :n 3 :incidents 2 :uneventful 1 :rate (/ 2.0 3)}
            {:idx 1 :category "Coppercoat" :n 2 :incidents 1 :uneventful 1 :rate 0.5}]
           levels))))

(deftest black-vs-coppercoat-test
  (let [{:keys [table black-rate copper-rate]} (findings/black-vs-coppercoat data md)]
    (testing "crosstab rows=is-black, cols=is-incident, false→true"
      ;; Coppercoat (¬black): 1 une, 1 inc → [1 1]; Black: 1 une, 2 inc → [1 2]
      (is (= [[1 1] [1 2]] table)))
    (is (close? (/ 2.0 3) black-rate))
    (is (close? 0.5 copper-rate))))

(deftest black-vs-not-black-test
  (testing "Black vs Not-Black 2×2 over all rows"
    (let [{:keys [table]} (findings/black-vs-not-black data md)]
      ;; Not-Black (Coppercoat rows 3,4): 1 une, 1 inc → [1 1]
      ;; Black (rows 0,1,2): 1 une, 2 inc → [1 2]
      (is (= [[1 1] [1 2]] table)))))

(deftest absent-category-assertion-test
  (testing "a missing category fails loudly (no silent .indexOf → -1)"
    (let [bad-md {:categories {:antifoul ["Blue" "Coppercoat"]}}] ; no "Black"
      (is (thrown-with-msg? AssertionError #"Black"
                            (findings/black-vs-coppercoat data bad-md)))
      (is (thrown-with-msg? AssertionError #"Black"
                            (findings/black-vs-not-black data bad-md))))))

(deftest daylight-levels-test
  (let [[night day] (findings/daylight-levels data)]
    (testing "Night = is_daytime 0 (rows with interaction 1,0)"
      (is (= {:level 0 :label "Night" :n 2 :incidents 1 :uneventful 1 :rate 0.5} night)))
    (testing "Day = is_daytime 1 (rows interaction 1,0,1)"
      (is (= {:level 1 :label "Daytime" :n 3 :incidents 2 :uneventful 1 :rate (/ 2.0 3)} day)))))

(deftest daylight-contingency-test
  (let [{:keys [table]} (findings/daylight-contingency data)]
    ;; rows = is-night, cols = is-incident, false→true
    ;; Day (¬night): 1 une, 2 inc → [1 2]; Night: 1 une, 1 inc → [1 1]
    (is (= [[1 2] [1 1]] table))))

(deftest confound-ttests-nil-exclusion-test
  (testing "rows where group? is nil are excluded from BOTH groups (fix #1)"
    ;; is_daytime nil on the two rows whose depth would otherwise dominate the
    ;; 'day' (false) group; with nil excluded they must not appear in either side.
    (let [d (tc/dataset
              {:is_daytime    [0 0 0 0 0 0 nil nil 1 1 1 1 1 1]
               :interaction   [1 0 1 0 1 0 1 0 1 0 1 0 1 0]
               :depth_ord_std [0.0 0.0 0.0 0.0 0.0 0.0 99.0 99.0 1.0 1.0 1.0 1.0 1.0 1.0]})
          dayv (vec (d :is_daytime))
          night? (fn [i] (let [v (nth dayv i)] (when (some? v) (= 0 (long v)))))
          [{:keys [mean-true mean-false]}]
          (findings/confound-ttests d [:depth_ord_std] night?)]
      ;; night group (is_daytime 0): depth all 0.0 → mean 0.0
      (is (close? 0.0 mean-true))
      ;; day group (is_daytime 1): depth all 1.0 → mean 1.0; the nil rows' 99.0
      ;; must NOT leak in (would pull the day mean far above 1.0)
      (is (close? 1.0 mean-false)))))

(deftest category-share-test
  (testing "per-group sailing-mode share over non-nil rows"
    ;; antifoul: Black rows 0,1,2 (sailing 0,0,1); Coppercoat rows 3,4 (sailing 1,1)
    (let [d (tc/add-column data :sailing_mode_idx [0 0 1 1 1])
          [black copper]
          (findings/category-share
            d :sailing_mode_idx (get-in md [:categories :sailing_mode])
            [["Black" (fn [i] (= 0 (nth (vec (d :antifoul_idx)) i)))]
             ["Coppercoat" (fn [i] (= 1 (nth (vec (d :antifoul_idx)) i)))]])]
      ;; Black: Motoring 2/3, Sailing 1/3
      (is (close? (/ 200.0 3) (:pct (first (:shares black)))))
      (is (close? (/ 100.0 3) (:pct (second (:shares black)))))
      ;; Coppercoat: Motoring 0/2, Sailing 2/2
      (is (close? 0.0 (:pct (first (:shares copper)))))
      (is (close? 100.0 (:pct (second (:shares copper))))))))

(deftest daylight-predictions-test
  (testing "P_night = sigmoid(alpha), P_day = sigmoid(alpha+beta), risk-ratio-mean"
    (let [fit {:draws {"alpha" [0.0 0.0] "beta_day" [0.0 0.0]}}
          {:keys [p-night p-day risk-ratio-mean]} (findings/daylight-predictions fit)]
      ;; alpha=0 → 0.5; beta=0 → 0.5; ratio 1.0
      (is (close? 0.5 (:mean p-night)))
      (is (close? 0.5 (:mean p-day)))
      (is (close? 1.0 risk-ratio-mean)))))

(deftest risk-ratio-mean-of-ratios-test
  (testing "with beta≠0 the risk ratio is E[p_n/p_d], NOT p̄_n/p̄_d"
    ;; alpha varies, beta_day = +ln(3) (day 3× the odds of night) on each draw.
    ;; per-draw p_n/p_d differs from the ratio of the pooled means, so this case
    ;; distinguishes mean-of-ratios (correct) from ratio-of-means.
    (let [alpha [0.0 -2.0]
          beta  [(Math/log 3.0) (Math/log 3.0)]
          sig   (fn [x] (/ 1.0 (+ 1.0 (Math/exp (- x)))))
          p-n   (mapv sig alpha)
          p-d   (mapv sig (map + alpha beta))
          expect-mean-of-ratios (/ (+ (/ (p-n 0) (p-d 0)) (/ (p-n 1) (p-d 1))) 2.0)
          ratio-of-means        (/ (/ (+ (p-n 0) (p-n 1)) 2.0)
                                   (/ (+ (p-d 0) (p-d 1)) 2.0))
          fit {:draws {"alpha" alpha "beta_day" beta}}
          {:keys [risk-ratio-mean]} (findings/daylight-predictions fit)]
      (is (close? expect-mean-of-ratios risk-ratio-mean))
      ;; guard: the two quantities really do differ here, so the test is meaningful
      (is (not (close? expect-mean-of-ratios ratio-of-means 1.0e-6))))))
