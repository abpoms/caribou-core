(ns caribou.field.collection
  (:require [clojure.string :as string]
            [caribou.field :as field]
            [caribou.util :as util]
            [caribou.logger :as log]
            [caribou.db :as db]
            [caribou.validation :as validation]
            [caribou.association :as assoc]))


(defn collection-join-conditions
  [field prefix opts]
  (let [slug (-> field :row :slug)]
    (assoc/with-propagation :include opts slug
      (fn [down]
        (let [model (field/models (-> field :row :model-id))
              target (field/models (-> field :row :target-id))
              link (-> field :env :link :slug)
              link-id-slug (keyword (str link "-id"))
              id-field (-> target :fields link-id-slug)
              table-alias (str prefix "$" slug)
              field-select (field/coalesce-locale
                            target id-field table-alias
                            (name link-id-slug) opts)
              downstream (assoc/model-join-conditions target table-alias down)
              params [(util/dbize (:slug target))
                      (util/dbize table-alias)
                      (util/dbize prefix)
                      field-select]]
          (concat
           [(util/clause "left outer join %1 %2 on (%3.id = %4)" params)]
           downstream))))))

(defn collection-where
  [field prefix opts]
  (let [slug (-> field :row :slug)]
    (assoc/with-propagation :where opts slug
      (fn [down]
        (let [model (field/models (-> field :row :model-id))
              target (field/models (-> field :row :target-id))
              link (-> field :env :link :slug)
              link-id-slug (keyword (str link "-id"))
              id-field (-> target :fields link-id-slug)
              table-alias (str prefix "$" slug)
              field-select (field/coalesce-locale
                            target id-field table-alias
                            (name link-id-slug) opts)
              subconditions (assoc/model-where-conditions
                             target table-alias down)
              params [(util/dbize prefix)
                      field-select
                      (util/dbize (:slug target))
                      (util/dbize table-alias)
                      subconditions]]
          (util/clause "%1.id in (select %2 from %3 %4 where %5)" params))))))

(defn collection-render
  [field content opts]
  (if-let [include (:include opts)]
    (let [slug (keyword (-> field :row :slug))]
      (if-let [sub (slug include)]
        (let [target (field/models (-> field :row :target-id))
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

(defn collection-propagate-order
  [this id orderings]
  (let [part (-> this :env :link)
        part-position (keyword (str (:slug part) "-position"))
        target-id (-> this :row :target-id)
        target (field/models target-id)
        target-slug (-> target :slug keyword)]
    (doseq [ordering orderings]
      ((resolve 'caribou.model/update)
       target-slug (:id ordering) {part-position (:position ordering)}))))

(defn collection-post-update
  [field content opts]
  (if-let [collection (get content (-> field :row :slug keyword))]
    (let [part-field (-> field :env :link)
          part-id-key (-> part-field :slug (str "-id") keyword)
          part-key-key (-> part-field :slug (str "-key") keyword)
          model (field/models (:model-id part-field))
          model-key (-> model :slug keyword)
          updated (doseq [part collection]
                    (let [part (if (map? part)
                                 part
                                 (assoc (last part)
                                   part-key-key (name (first part))))
                          part-opts (assoc part part-id-key (:id content))]
                      ((resolve 'caribou.model/create) model-key part-opts)))]
      (assoc content (keyword (-> field :row :slug)) updated))
    content))

(defrecord CollectionField [row env]
  field/Field
  (table-additions [this field] [])
  (subfield-names [this field] [])

  (setup-field
    [this spec]
    (if (or (nil? (:link-id row)) (zero? (:link-id row)))
      (let [model (db/find-model (:model-id row) (field/models))
            target (db/find-model (:target-id row) (field/models))
            map? (or (:map spec) (:map row))
            reciprocal-name (or (:reciprocal-name spec) (:name model))
            part ((resolve 'caribou.model/create) :field
                   {:name reciprocal-name
                    :type "part"
                    :map map?
                    :model-id (:target-id row)
                    :target-id (:model-id row)
                    :link-id (:id row)
                    :dependent (:dependent row)})]
        (db/update :field ["id = ?" (util/convert-int (:id row))]
                   {:link-id (:id part)}))))

  (rename-model [this old-slug new-slug])
  (rename-field [this old-slug new-slug])

  (cleanup-field
    [this]
    (try
      ((resolve 'caribou.model/destroy) :field (-> env :link :id))
      (catch Exception e (str e))))

  (target-for
    [this]
    (field/models (:target-id row)))

  (update-values
    [this content values]
    (let [removed (keyword (str "removed-" (:slug row)))]
      (if (assoc/present? (content removed))
        (let [ex (map util/convert-int (string/split (content removed) #","))
              part (env :link)
              part-key (keyword (str (:slug part) "-id"))
              target (field/models (:target-id row) :slug)]
          (doseq [gone ex]
            (if (:dependent row)
              ((resolve 'caribou.model/destroy) target gone)
              ((resolve 'caribou.model/update) target gone {part-key nil}))))))
    values)

  (post-update
    [this content opts]
    (collection-post-update this content opts))

  (pre-destroy
    [this content]
    (if (and content (or (row :dependent) (-> env :link :dependent)))
      (let [parts (field/field-from
                   this content
                   {:include {(keyword (:slug row)) {}}})
            target (keyword (get (field/target-for this) :slug))]
        (doseq [part parts]
          ((resolve 'caribou.model/destroy) target (:id part)))))
    content)

  (join-fields
    [this prefix opts]
    (assoc/with-propagation :include opts (:slug row)
      (fn [down]
        (let [target (field/models (:target-id row))]
          (assoc/model-select-fields target (str prefix "$" (:slug row))
                                     down)))))

  (join-conditions
    [this prefix opts]
    (collection-join-conditions this prefix opts))

  (build-where
    [this prefix opts]
    (collection-where this prefix opts))

  (natural-orderings
    [this prefix opts]
    (let [model (field/models (:model-id row))
          target (field/models (:target-id row))
          link (-> this :env :link :slug)
          link-position-slug (keyword (str link "-position"))
          position-field (-> target :fields link-position-slug)
          table-alias (str prefix "$" (:slug row))
          field-select (field/coalesce-locale
                        target position-field table-alias
                        (name link-position-slug) opts)
          downstream (assoc/model-natural-orderings target table-alias opts)]
      [(str field-select " asc") downstream]))

  (build-order [this prefix opts]
    (assoc/join-order this (field/models (row :target-id)) prefix opts))

  (field-generator [this generators]
    generators)

  (fuse-field
    [this prefix archetype skein opts]
    (assoc/collection-fusion this prefix archetype skein opts))

  (localized? [this] false)

  (models-involved [this opts all]
    (assoc/span-models-involved this opts all))

  (field-from
    [this content opts]
    (assoc/with-propagation :include opts (:slug row)
      (fn [down]
        (let [link (-> this :env :link :slug)
              parts (db/fetch (-> (field/target-for this) :slug)
                              (str link "_id = %1 order by %2 asc")
                              (content :id)
                              (str link "_position"))]
          (map #(assoc/from (field/target-for this) % down) parts)))))

  (propagate-order [this id orderings]
    (collection-propagate-order this id orderings))

  (render
    [this content opts]
    (collection-render this content opts))

  (validate [this opts] (validation/for-assoc this opts)))

(defn constructor
  [row]
  (let [link (if (row :link-id)
               (db/choose :field (row :link-id)))]
    (CollectionField. row {:link link})))
