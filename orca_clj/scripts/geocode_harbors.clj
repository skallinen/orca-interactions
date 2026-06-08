#!/usr/bin/env bb
;; geocode_harbors.clj
;;
;; INCREMENTAL harbor geocoder for the orca route planner.
;;
;; This script is for FUTURE re-runs when new harbor name strings appear in the
;; dataset. It is NOT run as part of the initial M1 bootstrap: the committed
;; route-planner/data/harbor_coords.edn was produced by Claude (model
;; claude-opus-4-8) reasoning over the place names DIRECTLY -- no API call.
;; This script reproduces that process programmatically via the Anthropic
;; Messages API so the cache can be extended without manual effort.
;;
;; Behaviour:
;;   1. Read route-planner/data/harbor_strings.edn (all distinct names).
;;   2. Read the existing route-planner/data/harbor_coords.edn cache.
;;   3. Compute the set of names NOT yet in the cache.
;;   4. If none are missing, exit (nothing to do).
;;   5. Otherwise send ONLY the missing names to the Claude API and ask for
;;      {:lat :lon :confidence :note} per name.
;;   6. MERGE the results into the cache and write it back (existing entries are
;;      never overwritten -- the cache is authoritative for names already keyed).
;;
;; Run (requires ANTHROPIC_API_KEY in the environment):
;;   ANTHROPIC_API_KEY=sk-... bb orca_clj/scripts/geocode_harbors.clj
;;
;; -----------------------------------------------------------------------------
;; PROMPT / OUTPUT CONTRACT
;; -----------------------------------------------------------------------------
;; The model is asked to act as a maritime geocoder for the Atlantic Iberian
;; coast, Bay of Biscay, Strait of Gibraltar and nearby outliers (Madeira,
;; Canaries, Azores, UK, France/Brittany, Morocco). For every input string it
;; must return one object keyed by the EXACT input string with:
;;   :lat  decimal degrees North (positive)
;;   :lon  decimal degrees East  (NEGATIVE for west -- almost all of these)
;;   :confidence  :high | :med | :low   (see policy below)
;;   :note  a short human-readable note
;;
;; CONFIDENCE POLICY (kept identical to the manual bootstrap):
;;   :high  named harbor/marina/port with a well-known fixed location, or a
;;          clear spelling/format variant of one.
;;   :med   a town/cape/bay/area where a representative coastal point is used,
;;          or a messy/compound string resolved to a clear place by reasoning
;;          (e.g. "Rota, Bay of Cadiz" -> Rota; "Sortie Gibraltar" -> Gibraltar).
;;   :low   a non-harbor / country-level / very vague string for which only a
;;          representative coordinate can be given (e.g. "UK", "France",
;;          "Biscay Bay", "Madeira").
;;
;; The model must output ONLY EDN (a single map), no prose, so it can be read
;; back with clojure.edn/read-string.

(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def strings-path "route-planner/data/harbor_strings.edn")
(def coords-path "route-planner/data/harbor_coords.edn")

(def api-url "https://api.anthropic.com/v1/messages")
(def model "claude-opus-4-8")
(def api-version "2023-06-01")
(def max-tokens 8192)

(defn elog [& args]
  (binding [*out* *err*] (apply println args)))

(def system-prompt
  (str "You are a precise maritime geocoder for sailing harbors and ports on "
       "the Atlantic coast of the Iberian Peninsula, the Bay of Biscay, the "
       "Strait of Gibraltar, and nearby outliers (Madeira, the Canary Islands, "
       "the Azores, the UK, France/Brittany, and Morocco). "
       "Given a list of harbor name strings -- many of which are misspelled, in "
       "different languages, or compound descriptions -- resolve each to the "
       "real harbor/port location. "
       "Latitudes are degrees North (positive). Longitudes are degrees East and "
       "are NEGATIVE for west; essentially all of these locations are west of "
       "Greenwich (a Mediterranean outlier like Ibiza may be positive). "
       "Assign :confidence as follows: "
       ":high = a named harbor/marina/port with a well-known fixed location, or "
       "a clear spelling/format variant of one; "
       ":med = a town/cape/bay/area for which a representative coastal point is "
       "used, or a messy/compound string resolved by reasoning; "
       ":low = a non-harbor, country-level, or very vague string for which only "
       "a representative coordinate is possible. "
       "Respond with ONLY a single EDN map, no prose and no code fences. "
       "Key each entry by the EXACT input string. Each value must be a map "
       "{:lat <number> :lon <number> :confidence <:high|:med|:low> :note <short string>}."))

(defn user-prompt [names]
  (str "Geocode these harbor name strings. Return one EDN map keyed by the exact "
       "input strings.\n\n"
       (str/join "\n" (map pr-str names))))

(defn call-claude
  "Send the missing names to the Claude API and return the model's raw text."
  [api-key names]
  (let [body {:model model
              :max_tokens max-tokens
              :system system-prompt
              :messages [{:role "user" :content (user-prompt names)}]}
        resp (http/post api-url
                        {:headers {"x-api-key" api-key
                                   "anthropic-version" api-version
                                   "content-type" "application/json"}
                         :body (json/generate-string body)})
        parsed (json/parse-string (:body resp) true)]
    (->> (:content parsed)
         (filter #(= "text" (:type %)))
         (map :text)
         (str/join))))

(defn strip-fences
  "Remove any accidental ```edn / ``` code fences from the model output."
  [s]
  (-> s
      (str/replace #"(?s)```(?:edn|clojure)?" "")
      (str/trim)))

(defn -main []
  (let [api-key (System/getenv "ANTHROPIC_API_KEY")
        names (edn/read-string (slurp strings-path))
        cache (if (.exists (io/file coords-path))
                (edn/read-string (slurp coords-path))
                {})
        missing (->> names (remove #(contains? cache %)) vec)]
    (elog "harbor strings :" (count names))
    (elog "cached         :" (count cache))
    (elog "missing        :" (count missing))
    (cond
      (empty? missing)
      (elog "Nothing to do -- every harbor string is already in the cache.")

      (str/blank? api-key)
      (do (elog "ERROR: ANTHROPIC_API_KEY is not set; cannot geocode missing names.")
          (elog "Missing names:")
          (doseq [m missing] (elog "  " m))
          (System/exit 1))

      :else
      (let [raw (call-claude api-key missing)
            new-entries (edn/read-string (strip-fences raw))
            ;; keep existing cache authoritative; only add genuinely new keys
            merged (reduce-kv (fn [m k v] (if (contains? m k) m (assoc m k v)))
                              cache new-entries)
            still-missing (remove #(contains? merged %) missing)]
        (elog "model returned :" (count new-entries) "entries")
        (when (seq still-missing)
          (elog "WARNING: still unresolved after API call:")
          (doseq [m still-missing] (elog "  " m)))
        (spit coords-path
              (with-out-str
                (binding [*print-length* nil]
                  (prn merged))))
        (elog "wrote" coords-path "(" (count merged) "entries )")))))

(-main)
