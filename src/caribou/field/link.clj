(ns caribou.field.link
  (:require [clojure.string :as string]
            [caribou.field :as field]
            [caribou.util :as util]
            [caribou.db :as db]
            [caribou.logger :as log]
            [caribou.validation :as validation]
            [caribou.association :as assoc]))

(defn join-table-name
  "construct a join table name out of two link names"
  [a b]
  (string/join "_" (sort (map util/slugify [a b]))))

(defn link-join-name
  "Given a link field, return the join table name used by that link."
  [field]
  (let [reciprocal (-> field :env :link)
        from-name (-> field :row :name)
        to-name (:slug reciprocal)]
    (keyword (join-table-name from-name to-name))))

(defn link-keys
  "Find all related keys given by this link field."
  [field]
  (let [reciprocal (-> field :env :link)
        from-name (-> field :row :slug)
        from-key (keyword (str from-name "_id"))
        to-name (:slug reciprocal)
        to-key (keyword (str to-name "_id"))
        join-key (keyword (join-table-name from-name to-name))]
    {:from from-key :to to-key :join join-key}))

(defn link-join-keys
  [this prefix opts]
  (let [{from-key :from to-key :to join-key :join} (link-keys this)
        from-name (-> this :row :slug)
        join-model (field/models join-key)
        join-alias (str prefix "$" from-name "_join")
        join-field (-> join-model :fields to-key)
        link-field (-> join-model :fields from-key)
        table-alias (str prefix "$" from-name)
        join-select (field/coalesce-locale
                     join-model join-field join-alias
                     (name to-key) opts)
        link-select (field/coalesce-locale
                     join-model link-field join-alias
                     (name from-key) opts)]
    {:join-key (name join-key)
     :join-alias join-alias
     :join-select join-select
     :table-alias table-alias
     :link-select link-select}))

(defn remove-link
  ([field from-id to-id]
     (remove-link field from-id to-id {}))
  ([field from-id to-id opts]
     (let [{from-key :from to-key :to join-key :join} (link-keys field)
           locale (if (:locale opts) (str (name (:locale opts)) "_") "")
           params [join-key from-key to-id to-key from-id locale]
           preexisting (first (apply (partial util/query "select * from %1 where %6%2 = %3 and %6%4 = %5") params))]
       (if preexisting
         ((resolve 'caribou.model/destroy) join-key (preexisting :id))))))

(defn- link-join-conditions
  [field prefix opts]
  (let [slug (-> field :row :slug)]
    (assoc/with-propagation :include opts slug
      (fn [down]
        (let [{:keys [join-key join-alias join-select table-alias link-select]}
              (link-join-keys field prefix opts)
              target (field/models (-> field :row :target_id))
              join-params [join-key join-alias join-select prefix]
              link-params [(:slug target) table-alias link-select]
              downstream (assoc/model-join-conditions target table-alias down)]
          (concat
           [(util/clause "left outer join %1 %2 on (%3 = %4.id)" join-params)
            (util/clause "left outer join %1 %2 on (%2.id = %3)" link-params)]
           downstream))))))

(defn- link-where
  [field prefix opts]
  (let [slug (-> field :row :slug)
        join-clause "%1.id in (select %2 from %3 %4 inner join %5 %8 on (%6 = %8.id) where %7)"]
    (assoc/with-propagation :where opts slug
      (fn [down]
        (let [{:keys [join-key join-alias join-select table-alias link-select]}
              (link-join-keys field prefix opts)
              model (field/models (-> field :row :model_id))
              target (field/models (-> field :row :target_id))
              subconditions (assoc/model-where-conditions target table-alias down)
              params [prefix join-select join-key join-alias
                      (:slug target) link-select subconditions table-alias]] 
          (util/clause join-clause params))))))

(defn- link-natural-orderings
  [field prefix opts]
  (let [slug (-> field :row :slug)
        reciprocal (-> field :env :link)
        model (field/models (-> field :row :model_id))
        target (field/models (-> field :row :target_id))
        to-name (reciprocal :slug)
        from-key (keyword (str slug "_position"))
        join-alias (str prefix "$" slug "_join")
        join-key (keyword (join-table-name slug to-name))
        join-model (field/models join-key)
        join-field (-> join-model :fields from-key)
        join-select (field/coalesce-locale
                     join-model join-field join-alias
                     (name from-key) opts)
        downstream (assoc/model-natural-orderings target (str prefix "$" slug) opts)]
    [(str join-select " asc") downstream]))

(defn- link-render
  [this content opts]
  (if-let [include (:include opts)]
    (let [slug (keyword (-> this :row :slug))]
      (if-let [sub (slug include)]
        (let [target (field/models (-> this :row :target_id))
              down {:include sub}]
          (update-in
           content [slug]
           (fn [col]
             (doall
              (map
               (fn [to]
                 (assoc/model-render target to down))
               col)))))
        content))
    content))

(defn link-rename-field
  [field old-slug new-slug]
  (let [model (field/models (:model_id (:row field)))
        target (field/models (:target_id (:row field)))
        reciprocal (-> field :env :link)
        reciprocal-slug (:slug reciprocal)
        old-join-key (keyword (str (name old-slug) "_join"))
        old-join-name (join-table-name (name old-slug) reciprocal-slug)
        new-join-key (keyword (str (name new-slug) "_join"))
        new-join-name (join-table-name (name new-slug) reciprocal-slug)
        join-model (field/models (keyword old-join-name))
        join-collection (-> model :fields old-join-key)
        old-key (keyword old-slug)
        join-target (-> join-model :fields old-key)]
    ((resolve 'caribou.model/update) :field (-> join-collection :row :id)
     {:name (util/titleize new-join-key) :slug (name new-join-key)})
    ((resolve 'caribou.model/update) :field (-> join-target :row :id)
     {:name (util/titleize new-slug) :slug (name new-slug)})
    ((resolve 'caribou.model/update) :model (:id join-model)
     {:name (util/titleize new-join-name) :slug new-join-name})))

(defn link-propagate-order
  [field id orderings]
  (let [model (field/models (:model_id (:row field)))
        slug (-> field :row :slug)
        id-slug (keyword (str slug "_id"))
        position-slug (keyword (str slug "_position"))
        reciprocal (-> field :env :link)
        reciprocal-slug (:slug reciprocal)
        join-name (join-table-name (name slug) reciprocal-slug)]
    (loop [joins (db/fetch join-name (str reciprocal-slug "_id = " id " order by " slug "_id"))
           orders (sort-by :id orderings)]
      (if (and (seq orders) (seq joins))
        (let [next-join (first joins)
              next-order (first orders)]
          (if (= (get next-join id-slug) (:id next-order))
            (do
              ((resolve 'caribou.model/update) join-name (:id next-join) {position-slug (:position next-order)})
              (recur (rest joins) (rest orders)))
            (recur (rest joins) orders)))))))

(defn link-models-involved
  [field opts all]
  (if-let [down (assoc/with-propagation :include opts (-> field :row :slug)
                  (fn [down]
                    (let [slug (-> field :row :slug)
                          reciprocal (-> field :env :link)
                          to-name (reciprocal :slug)
                          join-key (keyword (join-table-name slug to-name))
                          join-id (field/models join-key :id)
                          target (field/models (-> field :row :target_id))]
                      (assoc/model-models-involved target down (conj all join-id)))))]
    down
    all))

(defn lift-join-values
  [field prefix join-value opts]
  (let [slug (-> field :row :slug)
        {:keys [join-key join-alias join-select table-alias link-select]}
        (link-join-keys field prefix opts)
        join-model (field/models (keyword join-key))
        join-value-key (keyword (str slug "_" join-value))
        join-value-field (-> join-model :fields join-value-key)
        subprefix (str prefix "$" slug)
        value-select (field/coalesce-locale join-model join-value-field join-alias join-value-key opts)
        value-query (str value-select " as " subprefix "$" (name join-value-key))]
    value-query))

(defn link-join-fields
  [field prefix opts]
  (let [slug (-> field :row :slug)]
    (assoc/with-propagation :include opts slug
      (fn [down]
        (let [subprefix (str prefix "$" slug)
              position-query (lift-join-values field prefix "position" down)
              key-query (if (-> field :row :map) (lift-join-values field prefix "key" down))
              target (field/models (-> field :row :target_id))
              target-fields (assoc/model-select-fields target subprefix down)
              above (conj target-fields position-query)]
          (if (-> field :row :map)
            (conj above key-query)
            above))))))

(defn link
  "Link two rows by the given LinkField.  This function accepts its arguments
   in order, so that 'a' is a row from the model containing the given field."
  ([field a b]
     (link field a b {}))
  ([field a b opts]
     (let [{from-key :from to-key :to join-key :join} (link-keys field)
           target-id (-> field :row :target_id)
           target (or (field/models target-id)
                      (first (util/query "select * from model where id = %1" target-id)))
           locale (if (and (:localized target) (:locale opts))
                    (str (name (:locale opts)) "_")
                    "")
           key-slug (keyword (str (-> field :row :slug) "_key"))
           key-value (get b key-slug)
           linkage ((resolve 'caribou.model/create) (:slug target) b opts)
           params [join-key from-key (:id linkage) to-key (:id a) locale]
           query "select * from %1 where %6%2 = %3 and %6%4 = %5"
           preexisting (apply (partial util/query query) params)
           key-miasma {from-key (:id linkage) to-key (:id a)}
           key-miasma (if (and key-value (-> field :row :map))
                        (assoc key-miasma key-slug (name key-value))
                        key-miasma)]
       (if preexisting
         preexisting
         ((resolve 'caribou.model/create) join-key key-miasma opts)))))

(defn retrieve-links
  "Given a link field and a row, find all target rows linked to the given row
   by this field."
  ([field content]
     (retrieve-links field content {}))
  ([field content opts]
     (let [{from-key :from to-key :to join-key :join} (link-keys field)
           target (field/models (-> field :row :target_id))
           target-slug (target :slug)
           locale (if (:locale opts) (str (name (:locale opts)) "_") "")
           field-names (map
                        #(str target-slug "." %)
                        (assoc/table-columns target-slug))
           field-select (string/join "," field-names)
           join-query "select %1 from %2 inner join %3 on (%2.id = %3.%7%4) where %3.%7%5 = %6"
           params [field-select target-slug join-key from-key to-key (content :id) locale]
           key-slug (-> field :row :slug (str "_key") keyword)
           results (apply (partial util/query join-query) params)]
       (if (-> field :row :map)
         (assoc/seq->map results key-slug)
         results))))

(defrecord LinkField [row env]
  field/Field

  (table-additions [this field] [])
  (subfield-names [this field] [])

  (setup-field
    [this spec]
    (if (or (nil? (:link_id row)) (zero? (:link_id row)))
      (let [model (db/find-model (:model_id row) (field/models))
            target (db/find-model (:target_id row) (field/models))
            map? (or (:map spec) (:map row))
            reciprocal-name (or (:reciprocal_name spec) (:name model))
            join-name (join-table-name (:name spec) reciprocal-name)

            link
            ((resolve 'caribou.model/create)
             :field
             {:name reciprocal-name
              :type "link"
              :model_id (:target_id row)
              :target_id (:model_id row)
              :link_id (:id row)
              :map false
              :dependent (:dependent row)})

            join-model
            ((resolve 'caribou.model/create)
             :model
             {:name (util/titleize join-name)
              :join_model true
              :localized (or (:localized model) (:localized target))
              :fields
              [{:name (:name spec)
                :type "part"
                :map map?
                :dependent true
                :reciprocal_name (str reciprocal-name " Join")
                :target_id (:target_id row)}
               {:name reciprocal-name
                :type "part"
                :map false
                :dependent true
                :reciprocal_name (str (:name spec) " Join")
                :target_id (:model_id row)}]} {:op :migration})]

        (db/update :field ["id = ?" (util/convert-int (:id row))]
                   {:link_id (:id link)}))))

  (rename-model [this old-slug new-slug])
  (rename-field
    [this old-slug new-slug]
    (link-rename-field this old-slug new-slug))

  (cleanup-field [this]
    (try
      (let [join-name (link-join-name this)]
        ((resolve 'caribou.model/destroy) :model
         (field/models join-name :id))
        ((resolve 'caribou.model/destroy) :field (row :link_id)))
      (catch Exception e (str e))))

  (target-for [this] (field/models (row :target_id)))

  (update-values [this content values]
    (let [removed (get content (keyword (str "removed_" (:slug row))))]
      (if (assoc/present? removed)
        (let [ex (map util/convert-int (string/split removed #","))]
          (doall (map #(remove-link this (content :id) %) ex)))))
    values)

  (post-update [this content opts]
    (if-let [collection (get content (keyword (:slug row)))]
      (let [key-slug (keyword (str (:slug row) "_key"))
            keyed (if (:map row)
                    (map
                     (fn [[key item]]
                       (assoc item key-slug (name key)))
                     collection)
                    collection)
            linked (doseq [item keyed]
                     (link this content item opts))
            with-links (assoc content (keyword (str (:slug row) "_join")) linked)]
        (assoc content (:slug row) (retrieve-links this content opts))))
    content)

  (pre-destroy [this content]
    content)

  (join-fields [this prefix opts]
    (link-join-fields this prefix opts))

  (join-conditions [this prefix opts]
    (link-join-conditions this prefix opts))

  (build-where
    [this prefix opts]
    (link-where this prefix opts))

  (natural-orderings [this prefix opts]
    (link-natural-orderings this prefix opts))

  (build-order [this prefix opts]
    (assoc/join-order this (field/models (:target_id row)) prefix opts))

  (field-generator [this generators]
    generators)

  (fuse-field [this prefix archetype skein opts]
    (assoc/map-fusion this prefix archetype skein opts))

  (localized? [this] false)

  (propagate-order [this id orderings]
    (link-propagate-order this id orderings))

  (models-involved [this opts all]
    (link-models-involved this opts all))

  (field-from [this content opts]
    (assoc/with-propagation :include opts (:slug row)
      (fn [down]
        (let [target (field/target-for this)]
          (map
           #(assoc/from target % down)
           (retrieve-links this content opts))))))

  (render [this content opts]
    (link-render this content opts))

  (validate [this opts] (validation/for-assoc this opts)))

(defn constructor
  [row]
  (let [link (db/choose :field (row :link_id))]
    (LinkField. row {:link link})))

