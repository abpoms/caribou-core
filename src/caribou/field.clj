(ns caribou.field
  (:require [caribou.db :as db]
            [caribou.util :as util]
            [caribou.config :as config]
            [caribou.logger :as log]
            [caribou.db.adapter.protocol :as adapter]))

(defprotocol Field
  "a protocol for expected behavior of all model fields"
  (table-additions [this field]
    "the set of additions to this db table based on the given name")
  (subfield-names [this field]
    "the names of any additional fields added to the model
    by this field given this name")

  (setup-field [this spec] "further processing on creation of field")
  (rename-model [this old-slug new-slug] "called when the model containing this field is renamed")
  (rename-field [this old-slug new-slug] "called whenever a field is renamed")
  (cleanup-field [this] "further processing on removal of field")
  (target-for [this] "retrieves the model this field points to, if applicable")
  (update-values [this content values original]
    "adds to the map of values that will be committed to the db for this row")
  (post-update [this content opts]
    "any processing that is required after the content is created/updated")
  (pre-destroy [this content]
    "prepare this content item for destruction")

  (join-fields [this prefix opts])
  (join-conditions [this prefix opts])
  (build-where [this prefix opts] "creates a where clause suitable to this field from the given where map, with fields prefixed by the given prefix.")
  (natural-orderings [this prefix opts])
  (build-order [this prefix opts])
  (field-generator [this generators])
  (fuse-field [this prefix archetype skein opts])

  (localized? [this])
  (models-involved [this opts all])

  (propagate-order [this id orderings])
  (field-from [this content opts]
    "retrieves the value for this field from this content item")
  (render [this content opts] "renders out a single field from this content item")
  (validate [this opts] "given a set of options and the models, verifies the options are appropriate and well formed for gathering"))

(defn clarify-path
  [path]
  (map
   (fn [p]
     (if (integer? p)
       p
       (keyword p)))
   path))

(defn models
  [& path]
  (let [path (clarify-path path)]
    (try 
      (get-in (deref (config/draw :models)) path)
      (catch Exception e (log/render-exception e)))))

;; functions for localized fields
(defn build-locale-field
  [prefix slug locale]
  (str prefix "." locale "-" slug))

(defn build-select-field
  [prefix slug]
  (str prefix "." slug))

(defn build-coalesce
  [prefix slug locale results]
  (let [global (build-select-field prefix slug)
        local (build-locale-field prefix slug locale)]
    (if (and results (= :clean (keyword results)))
      local
      {:coalesce [local global]})))

(defn coalesce-locale
  [model field prefix slug opts]
  (let [locale (:locale opts)]
    (if (and
         locale
         (-> field :row :localized))
      (build-coalesce prefix (name slug) locale (:results opts))
      (build-select-field prefix (name slug)))))

(defn where-operator
  [where]
  (cond
   (nil? where) ["IS" nil]
   (map? where) [(-> where keys first name) (-> where vals first)]
   (sequential? where) ["IN" where]
   :else ["=" where]))

(defn single-where
  [field prefix opts do-where]
  (let [slug (keyword (-> field :row :slug))
        where (-> opts :where slug)]
    (if (contains? (:where opts) slug)
      (do-where field prefix slug opts where))))

(defn field-where
  [field prefix opts do-where]
  (let [slug (keyword (-> field :row :slug))
        where (:where opts)]
    (cond
     (nil? where) nil
     (map? where) (single-where field prefix opts do-where))))

(defn pure-where
  [field prefix slug opts where]
  (let [model-id (-> field :row :model-id)
        model (db/find-model model-id (models))
        [operator value] (where-operator where)
        field-select (coalesce-locale model field prefix slug opts)]
    {:field field-select
     :op operator
     :value value}))

(defn process-potential-seq
  [process]
  (fn [value]
    (if (sequential? value)
      (map process value)
      (process value))))

(defn process-where
  [field prefix opts process]
  (let [pure (field-where field prefix opts pure-where)]
    (if-not (nil? (:value pure))
      (update-in pure [:value] (process-potential-seq process))
      pure)))

(def integer-conversion util/convert-int)

(defn double-conversion
  [something]
  (try 
    (condp = (type something)
      nil nil
      java.math.BigInteger (.doubleValue something)
      java.math.BigDecimal (.doubleValue something)
      (Double. something))
    (catch java.lang.NumberFormatException e nil)))

(defn integer-where
  [field prefix opts]
  (process-where field prefix opts integer-conversion))

(defn double-where
  [field prefix opts]
  (process-where field prefix opts double-conversion))

(defn boolean-where
  [field prefix opts]
  (process-where field prefix opts #(Boolean. %)))

(defn string-where
  [field prefix slug opts where]
  (pure-where field prefix slug opts where))

(defn pure-fusion
  [this prefix archetype skein opts]
  (let [slug (keyword (-> this :row :slug))
        bit (util/prefix-key prefix slug)
        containing (drop-while #(nil? (get % bit)) skein)
        value (get (first containing) bit)]
    (assoc archetype slug value)))

(defn text-fusion
  [this prefix archetype skein opts]
  (let [pure (pure-fusion this prefix archetype skein opts)
        slug (keyword (-> this :row :slug))]
    (update-in pure [slug] (partial adapter/text-value (config/draw :database :adapter)))))

(defn structure-read
  [structure]
  (let [value (adapter/text-value (config/draw :database :adapter) structure)]
    (if (and value (not (empty? value)))
      (read-string value))))

(defn structure-fusion
  [this prefix archetype skein opts]
  (let [pure (pure-fusion this prefix archetype skein opts)
        slug (keyword (-> this :row :slug))]
    (update-in
     pure [slug]
     structure-read)))
     ;; #(let [value (adapter/text-value (config/draw :database :adapter) %)]
     ;;    (if (and value (not (empty? value)))
     ;;      (read-string value))))))

(defn id-models-involved
  [field opts all]
  (conj all (-> field :row :model-id)))

(defn pure-order
  [field prefix opts]
  (let [slug (-> field :row :slug)]
    (if-let [by (get (:order opts) (keyword slug))]
      (let [model-id (-> field :row :model-id)
            model (db/find-model model-id (models))
            field-select (coalesce-locale model field prefix slug opts)]
        {:by field-select
         :direction by}))))

(defn slug-update-values
  [field content values transform]
  (let [key (keyword (-> field :row :slug))]
    (cond
     (-> field :env :link)
     (let [icon (content (keyword (-> field :env :link :slug)))]
       (if icon
         (assoc values key (transform icon))
         values))

     (contains? content key) 
     (assoc values key (transform (get content key)))

     :else values)))

(defn field-cleanup
  [field]
  (let [model-id (-> field :row :model-id)
        model (models model-id)]
    (doseq [addition (table-additions field (-> field :row :slug))]
      (db/drop-column (:slug model) (first addition)))))

(defn rename-model-index
  [old-model new-model field-slug]
  (db/drop-model-index old-model new-model field-slug)
  (db/create-index new-model field-slug))

(defn rename-index
  [field old-slug new-slug]
  (let [model (db/choose :model (-> field :row :model-id))]
    (db/drop-index (:slug model) old-slug)
    (db/create-index (:slug model) new-slug)))

(defn get-constructor
  [type]
  (get (deref (config/draw :field :constructors)) (keyword type)))

(defn add-constructor
  [key construct]
  (swap!
   (config/draw :field :constructors)
   (fn [constructors]
     (assoc constructors (keyword key) construct))))
