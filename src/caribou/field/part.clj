(ns caribou.field.part
  (:require [clojure.string :as string]
            [caribou.field :as field]
            [caribou.util :as util]
            [caribou.db :as db]
            [caribou.validation :as validation]
            [caribou.association :as assoc]))


(defn part-where
  [field prefix opts]
  (let [slug (-> field :row :slug)]
    (assoc/with-propagation :where opts slug
      (fn [down]
        (let [model (field/models (-> field :row :model-id))
              target (field/models (-> field :row :target-id))
              part (-> field :row :slug)
              part-id-slug (keyword (str part "-id"))
              part-id-field (-> model :fields part-id-slug)
              part-select (field/coalesce-locale model part-id-field prefix
                                                  (name part-id-slug) opts)
              id-field (-> target :fields :id)
              table-alias (str prefix "$" slug)
              field-select (field/coalesce-locale model id-field table-alias
                                                   "id" opts)
              subconditions (assoc/model-where-conditions target table-alias down)]
          {:field part-select
           :op "in"
           :value {:select field-select
                   :from [(:slug target) table-alias]
                   :where subconditions}})))))

(defrecord PartField [row env]
  field/Field

  (table-additions [this field] [])
  (subfield-names
    [this field]
    (if (-> this :row :map)
      [(str field "-id") (str field "-position") (str field "-key")]
      [(str field "-id") (str field "-position")]))

  (setup-field [this spec]
    (let [model-id (:model-id row)
          model (db/find-model model-id (field/models))
          id-slug (str (:slug row) "-id")
          target (db/find-model (:target-id row) (field/models))
          reciprocal-name (or (:reciprocal-name spec) (:name model))
          localized (-> this :row :localized)
          base-fields [{:name (util/titleize id-slug)
                        :type "integer"
                        :editable false
                        :locked (:locked row)
                        :localized localized
                        :reference (:slug target)
                        :dependent (:dependent spec)}
                       {:name (util/titleize (str (:slug row) "-position"))
                        :type "position"
                        :locked (:locked row)
                        :localized localized
                        :editable false}]

          part-fields (if (or (:map spec) (:map row))
                        (conj
                         base-fields
                         {:name (util/titleize (str (:slug row) "-key"))
                          :localized localized
                          :locked (:locked row)
                          :type "string"
                          :editable false})
                        base-fields)]
      (if (or (nil? (:link-id row)) (zero? (:link-id row)))
        (let [collection ((resolve 'caribou.model/create) :field
                           {:name reciprocal-name
                            :type "collection"
                            :locked (:locked row)
                            :localized localized
                            :model-id (:target-id row)
                            :target-id model-id
                            :link-id (:id row)})]
          (db/update :field ["id = ?" (util/convert-int (:id row))] {:link-id (:id collection)})))

      ((resolve 'caribou.model/update) :model model-id {:fields part-fields})))

  (rename-model [this old-slug new-slug]
    (let [field (db/choose :field (-> this :row :id))]
      (field/rename-model-index old-slug new-slug (str (:slug field) "-id"))))

  (rename-field [this old-slug new-slug]
    (field/rename-index this (str old-slug "-id") (str new-slug "-id")))

  (cleanup-field [this]
    (let [model (field/models (:model-id row))
          fields (:fields model)
          base-slugs ["id" "position"]
          additional (if (:map row)
                       (conj base-slugs "key")
                       base-slugs)
          slugs (map #(keyword (str (:slug row) "-" %)) additional)
          id-slug (keyword (str (:slug row) "-id"))]
      (db/drop-index (:slug model) id-slug)
      (doseq [slug slugs]
        ((resolve 'caribou.model/destroy) :field (-> fields slug :row :id)))
      (try
        (do ((resolve 'caribou.model/destroy) :field (-> env :link :id)))
        (catch Exception e (str e)))))

  (target-for [this] (field/models (-> this :row :target-id)))

  (update-values [this content values original] values)

  (post-update [this content opts] content)

  (pre-destroy [this content] content)

  (join-fields [this prefix opts]
    (assoc/with-propagation :include opts (:slug row)
      (fn [down]
        (let [target (field/models (:target-id row))]
          (assoc/model-select-fields target (str prefix "$" (:slug row))
                                     down)))))

  (join-conditions [this prefix opts]
    (assoc/with-propagation :include opts (:slug row)
      (fn [down]
        (let [model (field/models (:model-id row))
              target (field/models (:target-id row))
              id-slug (keyword (str (:slug row) "-id"))
              id-field (-> model :fields id-slug)
              table-alias (str prefix "$" (:slug row))
              field-select (field/coalesce-locale model id-field prefix
                                                   (name id-slug) opts)
              downstream (assoc/model-join-conditions target table-alias down)]
          (cons
           {:table [(:slug target) table-alias]
            :on [field-select (str table-alias ".id")]}
           downstream)))))

  (build-where
    [this prefix opts]
    (part-where this prefix opts))

  (natural-orderings [this prefix opts]
    (let [target (field/models (:target-id row))
          downstream (assoc/model-natural-orderings target (str prefix "$" (:slug row)) opts)]
      downstream))

  (build-order [this prefix opts]
    (assoc/join-order this (field/models (:target-id row)) prefix opts))

  (fuse-field [this prefix archetype skein opts]
    (assoc/part-fusion this (field/models (-> this :row :target-id)) prefix archetype skein opts))

  (localized? [this] false)

  (propagate-order [this id orderings])
  (models-involved [this opts all]
    (assoc/span-models-involved this opts all))

  (field-from [this content opts]
    (assoc/with-propagation :include opts (:slug row)
      (fn [down]
        (if-let [pointing (content (keyword (str (:slug row) "-id")))]
          (let [collector (db/choose (-> (field/target-for this) :slug) pointing)]
            (assoc/from (field/target-for this) collector down))))))

  (render [this content opts]
    (assoc/part-render this (field/models (:target-id row)) content opts))

  (validate [this opts] (validation/for-assoc this opts)))

(defn constructor
  [row]
  (let [link (db/choose :field (row :link-id))]
    (PartField. row {:link link})))
                      
