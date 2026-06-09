(ns orca.synth-test
  "Pure deterministic tests for the Part-A synthetic generator. No CmdStan: these
   pin determinism (same seed ⇒ identical output), the base-rate ↔ intercept
   mapping, slope direction on the empirical logit, categorical proportions, and
   the N×K design-matrix shape `attr_logit.stan` requires."
  (:require
   [clojure.test :refer [deftest is testing]]
   [orca.synth :as synth]
   [orca.util :as util]))

(defn- close?
  ([a b] (close? a b 1e-9))
  ([a b eps] (< (abs (- (double a) (double b))) (double eps))))

(deftest logit-test
  (testing "logit is the inverse of sigmoid and hits the documented base rate"
    (is (close? -3.4760986898352733 (synth/logit 0.03) 1e-9))
    (is (close? 0.0 (synth/logit 0.5)))
    (is (close? 0.03 (util/sigmoid (synth/logit 0.03)) 1e-12))
    (is (close? 0.7 (synth/inv-logit (synth/logit 0.7)) 1e-12))))

(deftest cholesky-test
  (testing "L·Lᵀ reconstructs the covariance"
    (let [cov [[1.0 0.5] [0.5 1.0]]
          l   (synth/cholesky cov)
          ll  (fn [i j] (reduce + (map #(* (get-in l [i %]) (get-in l [j %]))
                                       (range 2))))]
      (is (close? 1.0 (ll 0 0) 1e-12))
      (is (close? 0.5 (ll 0 1) 1e-12))
      (is (close? 1.0 (ll 1 1) 1e-12)))))

(deftest deterministic-test
  (testing "same seed ⇒ identical output"
    (let [cfg {:n 200 :base-rate 0.1 :seed 42
               :predictors [{:dist :normal} {:dist :categorical :probs [0.6 0.4]}]
               :true-slopes [0.5 -0.3]}
          a   (synth/simulate-attr cfg)
          b   (synth/simulate-attr cfg)]
      (is (= (:X a) (:X b)))
      (is (= (:y a) (:y b)))
      (is (= (:truth a) (:truth b)))))
  (testing "different seed ⇒ different draws"
    (let [base {:n 200 :base-rate 0.1
                :predictors [{:dist :normal}] :true-slopes [0.5]}]
      (is (not= (:y (synth/simulate-attr (assoc base :seed 1)))
                (:y (synth/simulate-attr (assoc base :seed 2))))))))

(deftest dimensions-test
  (testing "X is N×K (row-major), y is length N, truth aligns to K"
    (let [r (synth/simulate-attr
              {:n 50 :base-rate 0.2 :seed 7
               :predictors [{:dist :normal}
                            {:dist :mvnormal :mean [0 0] :cov [[1 0.3] [0.3 1]]}
                            {:dist :categorical :probs [0.5 0.5]}]
               :true-slopes [0.4 0.1 0.2 -0.5]})]
      (is (= 50 (:N r)))
      (is (= 4 (:K r)))
      (is (= 50 (count (:X r))))
      (is (every? #(= 4 (count %)) (:X r)))
      (is (= 50 (count (:y r))))
      (is (every? #(or (= 0 %) (= 1 %)) (:y r)))
      (is (= #{"alpha" "beta.1" "beta.2" "beta.3" "beta.4"}
             (set (keys (:truth r))))))))

(deftest base-rate-recovery-test
  (testing "with all slopes 0, empirical incident rate ≈ base rate at large N"
    (let [base-rate 0.07
          r (synth/simulate-attr
              {:n 40000 :base-rate base-rate :seed 42
               :predictors [{:dist :normal} {:dist :normal}]
               :true-slopes [0.0 0.0]})
          emp (util/mean (:y r))]
      (is (close? base-rate emp 0.01)))))

(deftest slope-direction-test
  (testing "empirical logit responds to a known POSITIVE slope upward"
    ;; Split on the predictor sign; a positive slope ⇒ higher rate when x>0.
    (let [r (synth/simulate-attr
              {:n 60000 :base-rate 0.2 :seed 42
               :predictors [{:dist :normal}] :true-slopes [1.0]})
          pairs (map vector (map first (:X r)) (:y r))
          rate  (fn [pred] (let [g (filter (comp pred first) pairs)]
                             (/ (double (reduce + (map second g))) (count g))))
          hi    (rate pos?)
          lo    (rate neg?)]
      (is (> hi lo))
      (is (> (synth/logit hi) (synth/logit lo)))))
  (testing "a NEGATIVE slope drives the rate the other way"
    (let [r (synth/simulate-attr
              {:n 60000 :base-rate 0.2 :seed 42
               :predictors [{:dist :normal}] :true-slopes [-1.0]})
          pairs (map vector (map first (:X r)) (:y r))
          rate  (fn [pred] (let [g (filter (comp pred first) pairs)]
                             (/ (double (reduce + (map second g))) (count g))))]
      (is (< (rate pos?) (rate neg?))))))

(deftest categorical-proportions-test
  (testing "draw-categorical empirical proportions ≈ probs"
    (let [probs [0.6 0.3 0.1]
          rng   (synth/make-rng 42)
          draws (synth/draw-categorical rng probs 50000)
          freq  (frequencies draws)
          prop  (fn [k] (/ (double (get freq k 0)) (count draws)))]
      (is (close? 0.6 (prop 0) 0.01))
      (is (close? 0.3 (prop 1) 0.01))
      (is (close? 0.1 (prop 2) 0.01)))))

(deftest gen-attr-data-test
  (testing "slopes declared per-predictor derive the same result as :true-slopes"
    (let [a (synth/gen-attr-data
              {:n 100 :base-rate 0.05 :seed 3
               :predictors [{:dist :normal :slope 0.5}
                            {:dist :categorical :probs [0.5 0.5] :slope -0.2}]})
          b (synth/simulate-attr
              {:n 100 :base-rate 0.05 :seed 3
               :predictors [{:dist :normal} {:dist :categorical :probs [0.5 0.5]}]
               :true-slopes [0.5 -0.2]})]
      (is (= (:y a) (:y b)))
      (is (= (:truth a) (:truth b)))
      (is (= [0.5 -0.2] (:betas a))))))
