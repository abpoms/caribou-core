(ns caribou.model-association
  (:require [clojure.string :as string]
            [caribou.util :as util]
            [caribou.field-protocol :as field]))

(defn join-table-name
  "construct a join table name out of two link names"
  [a b]
  (string/join "_" (sort (map util/slugify [a b]))))

(defn table-columns
  "Return a list of all columns for the table corresponding to this model."
  [slug]
  (let [model (@field/models (keyword slug))]
    (apply
     concat
     (map
      (fn [field]
        (map
         #(name (first %))
         (field/table-additions field (-> field :row :slug))))
      (vals (model :fields))))))

(defn link-keys
  "Find all related keys given by this link field."
  [field]
  (let [reciprocal (-> field :env :link)
        from-name (-> field :row :slug)
        from-key (keyword (str from-name "_id"))
        to-name (reciprocal :slug)
        to-key (keyword (str to-name "_id"))
        join-key (keyword (join-table-name from-name to-name))]
    {:from from-key :to to-key :join join-key}))

(defn retrieve-links
  "Given a link field and a row, find all target rows linked to the given row
   by this field."
  ([field content]
     (retrieve-links field content {}))
  ([field content opts]
     (let [{from-key :from to-key :to join-key :join} (link-keys field)
           target (@field/models (-> field :row :target_id))
           target-slug (target :slug)
           locale (if (:locale opts) (str (name (:locale opts)) "_") "")
           field-names (map #(str target-slug "." %)
                            (table-columns target-slug))
           field-select (string/join "," field-names)
           join-query "select %1 from %2 inner join %3 on (%2.id = %3.%7%4) where %3.%7%5 = %6"
           params [field-select target-slug join-key from-key to-key (content :id) locale]]
       (apply (partial util/query join-query) params))))

(defn present?
  [x]
  (and (not (nil? x))
       (or (number? x) (keyword? x) (= (type x) Boolean) (not (empty? x)))))

(defn model-join-conditions
  "Find all necessary table joins for this query based on the arbitrary
   nesting of the include option."
  [model prefix opts]
  (let [fields (:fields model)]
    (filter
     identity
     (apply
      concat
      (map
       (fn [field]
         (field/join-conditions field (name prefix) opts))
       (vals fields))))))

(defn assoc-field
  [content field opts]
  (assoc
    content
    (keyword (-> field :row :slug))
    (field/field-from field content opts)))

(defn from
  "takes a model and a raw db row and converts it into a full
  content representation as specified by the supplied opts.
  some opts that are supported:
    include - a nested hash of association includes.  if a key matches
    the name of an association any content associated to this item through
    that association will be inserted under that key."
  [model content opts]
  (reduce
   #(assoc-field %1 %2 opts)
   content
   (vals (model :fields))))

(defn model-natural-orderings
  "Find all orderings between included associations that depend on the association position column
   of the given model."
  [model prefix opts]
  (filter
   identity
   (flatten
    (map
     (fn [order-key]
       (if-let [field (-> model :fields order-key)]
         (field/natural-orderings
          field (name prefix)
          (assoc opts :include (-> opts :include order-key)))))
     (keys (:include opts))))))

(defn model-models-involved
  [model opts all]
  (reduce #(field/models-involved %2 opts %1) all (-> model :fields vals)))

(defn span-models-involved
  [field opts all]
  (if-let [down (field/with-propagation :include opts (-> field :row :slug)
                  (fn [down]
                    (let [target (@field/models (-> field :row :target_id))]
                      (model-models-involved target down all))))]
    down
    all))

(defn model-where-conditions
  "Builds the where part of the uberquery given the model, prefix and
   given map of the where conditions."
  [model prefix opts]
  (let [eyes
        (filter
         identity
         (map
          (fn [field]
            (field/build-where field prefix opts))
          (vals (:fields model))))]
    (string/join " and " (flatten eyes))))

(defn model-select-fields
  "Build a set of select fields based on the given model."
  [model prefix opts]
  (let [fields (vals (:fields model))
        sf (fn [field]
             (field/select-fields model field (name prefix) opts))
        model-fields (map sf fields)]
    (set (apply concat model-fields))))

(defn model-build-order
  "Builds out the order component of the uberquery given whatever ordering map
   is found in opts."
  [model prefix opts]
  (filter
   identity
   (flatten
    (map
     (fn [field]
       (field/build-order field prefix opts))
     (vals (:fields model))))))

(defn model-render
  "render a piece of content according to the fields contained in the model
  and given by the supplied opts"
  [model content opts]
  (let [fields (vals (:fields model))]
    (reduce
     (fn [content field]
       (field/render field content opts))
     content fields)))

(defn subfusion
  [model prefix skein opts]
  (let [fields (vals (:fields model))
        archetype
        (reduce
         (fn [archetype field]
           (field/fuse-field field prefix archetype skein opts))
         {} fields)]
    archetype))

(defn part-fusion
  [this target prefix archetype skein opts]
  (let [slug (keyword (-> this :row :slug))
        fused
        (field/with-propagation :include opts slug
          (fn [down]
            (let [value (subfusion target (str prefix "$" (name slug))
                                   skein down)]
              (if (:id value)
                (assoc archetype slug value)
                archetype))))]
    (or fused archetype)))

(defn fusion
  "Takes the results of the uberquery, which could have a map for each
   item associated to a given piece of content, and fuses them into a
   single nested map representing that content."
  [model prefix fibers opts]
  (let [model-key (util/prefix-key prefix "id")
        order (distinct (map model-key fibers))
        world (group-by model-key fibers)
        fused (util/map-vals #(subfusion model prefix % opts) world)]
    (map #(fused %) order)))

(defn collection-fusion
  [this prefix archetype skein opts]
  (let [slug (keyword (-> this :row :slug))
        nesting 
        (field/with-propagation :include opts slug
          (fn [down]
            (let [target (@field/models (-> this :row :target_id))
                  value (fusion target (str prefix "$" (name slug)) skein down)
                  protected (filter :id value)]
              (assoc archetype slug protected))))]
    (or nesting archetype)))

(defn join-order
  [field target prefix opts]
  (let [slug (keyword (-> field :row :slug))]
    (field/with-propagation :order opts slug
      (fn [down]
        (model-build-order target (str prefix "$" (name slug)) down)))))

(defn join-fusion
  ([this target prefix archetype skein opts]
     (join-fusion this target prefix archetype skein opts identity))
  ([this target prefix archetype skein opts process]
     (let [slug (keyword (-> this :row :slug))
           value (subfusion target (str prefix "$" (name slug)) skein opts)]
       (if (:id value)
         (assoc archetype slug (process value))
         archetype))))

(defn join-render
  [this target content opts]
  (let [slug (keyword (-> this :row :slug))]
    (if-let [sub (slug content)]
      (update-in
       content [slug]
       (fn [part]
         (model-render target part opts)))
      content)))

(defn part-render
  [this target content opts]
  (if-let [include (:include opts)]
    (let [slug (keyword (-> this :row :slug))
          down {:include (slug include)}]
      (if-let [sub (slug content)]
        (update-in
         content [slug] 
         (fn [part]
           (model-render target part down)))
        content))
    content))
