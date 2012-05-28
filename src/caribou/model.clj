(ns caribou.model
  (:use caribou.debug)
  (:use caribou.util)
  (:use [clojure.string :only (join split trim)])
  (:require [caribou.db :as db]
            [clj-time.core :as timecore]
            [clj-time.format :as format]
            [clj-time.coerce :as coerce]
            [clojure.java.jdbc :as sql]
            [geocoder.core :as geo]
            [clojure.java.io :as io]
            [caribou.config :as config]
            [caribou.db.adapter.protocol :as adapter]))

(import java.util.Date)
(import java.text.SimpleDateFormat)

(defn db
  [f]
  (db/call f))

(def simple-date-format (java.text.SimpleDateFormat. "MMMMMMMMM dd', 'yyyy HH':'mm"))
(defn format-date
  "given a date object, return a string representing the canonical format for that date"
  [date]
  (if date
    (.format simple-date-format date)))

(def custom-formatters
  (map #(format/formatter %)
       ["MM/dd/yy"
        "MM/dd/yyyy"
        "MMMMMMMMM dd, yyyy"
        "MMMMMMMMM dd, yyyy HH:mm"
        "MMMMMMMMM dd, yyyy HH:mm:ss"
        "MMMMMMMMM dd yyyy"
        "MMMMMMMMM dd yyyy HH:mm"
        "MMMMMMMMM dd yyyy HH:mm:ss"]))

(def time-zone-formatters
  (map #(format/formatter %)
       ["MM/dd/yy Z"
        "MM/dd/yyyy Z"
        "MMMMMMMMM dd, yyyy Z"
        "MMMMMMMMM dd, yyyy HH:mm Z"
        "MMMMMMMMM dd, yyyy HH:mm:ss Z"
        "MMMMMMMMM dd yyyy Z"
        "MMMMMMMMM dd yyyy HH:mm Z"
        "MMMMMMMMM dd yyyy HH:mm:ss Z"]))

(defn try-formatter
  [date-string formatter]
  (try
    (format/parse formatter date-string)
    (catch Exception e nil)))

(defn impose-time-zone
  [timestamp]
  (timecore/from-time-zone timestamp (timecore/default-time-zone)))

(defn read-date
  [date-string]
  (let [trimmed (trim date-string)
        default (coerce/from-string trimmed)]
    (if (empty? default)
      (let [custom (some #(try-formatter trimmed %) time-zone-formatters)]
        (if custom
          (coerce/to-timestamp custom)
          (let [custom (some #(try-formatter trimmed %) custom-formatters)]
            (if custom
              (coerce/to-timestamp (impose-time-zone custom))))))
      (coerce/to-timestamp (impose-time-zone default (timecore/default-time-zone))))))

(defprotocol Field
  "a protocol for expected behavior of all model fields"
  (table-additions [this field]
    "the set of additions to this db table based on the given name")
  (subfield-names [this field]
    "the names of any additional fields added to the model
    by this field given this name")
  (setup-field [this spec] "further processing on creation of field")
  (cleanup-field [this] "further processing on removal of field")
  (target-for [this] "retrieves the model this field points to, if applicable")
  (update-values [this content values]
    "adds to the map of values that will be committed to the db for this row")
  (post-update [this content]
    "any processing that is required after the content is created/updated")
  (pre-destroy [this content]
    "prepare this content item for destruction")
  (join-fields [this prefix opts])
  (join-conditions [this prefix opts])
  (build-where [this prefix opts] "creates a where clause suitable to this field from the given where map, with fields prefixed by the given prefix.")
  (natural-orderings [this prefix opts])
  (recompose-field [this mass opts])
  (field-from [this content opts]
    "retrieves the value for this field from this content item")
  (render [this content opts] "renders out a single field from this content item"))

(defn suffix-prefix
  [prefix]
  (if (or (nil? prefix) (empty? prefix))
    ""
    (str prefix ".")))

(defn table-fields
  "This is part of the Field protocol that is the same for all fields.
  Returns the set of fields that could play a role in the select. "
  [field]
  (concat (map first (table-additions field (-> field :row :slug)))
          (subfield-names field (-> field :row :slug))))

;; We want to enable queries of the form: 
;;
;;   select model.name as model$name, model.id as model$id,
;;     fields.name as fields$name, fields.type, fields.id as fields$id,
;;     link.name as link$name, link.id as link$id
;;   from model model
;;   inner join field fields on (model.id = fields.model_id)
;;   left outer join field link on (fields.link_id = link.id);
;;
;; For join tables (habtm) the following query is necessary:
;;
;;   select yellow.aoahoah as yellow$aoahoah, green.balloon as green$balloon,
;;     green_join.green_id as green_join$green_id,
;;     green_join.opny_id as green_join$opny_id
;;   from yellow yellow
;;   left outer join green_opny green_join on (green_join.opny_id = yellow.id)
;;   left outer join green green on (green.id = green_join.green_id);
;;
;; The left outer joins are necessary so that items without
;; associations are still found.

(defn select-fields
  "Find all necessary columns for the select query based on the given include nesting
   and fashion them into sql form."
  [field prefix opts]
  (let [columns (table-fields field)
        model-fields
        (map
         (fn [slug]
           (str prefix "." (name slug) " as " prefix "$" (name slug))) columns)
        join-model-fields
        (join-fields field (:slug field) opts)]
    (concat model-fields join-model-fields)))

(defn with-nesting
  [sign opts field includer]
  (if-let [outer (sign opts)]
    (if-let [inner (outer (keyword field))]
      (let [down (assoc opts sign inner)]
        (includer down)))))

(defn pure-where
  [prefix field where]
  (db/clause "%1.%2 = %3" [prefix field where]))

(defn string-where
  [prefix field where]
  (db/clause "%1.%2 = '%3'" [prefix field where]))

(defn field-where
  [field prefix opts do-where]
  (let [slug (keyword (-> field :row :slug))]
    (if-let [where (-> opts :where slug)]
      (do-where prefix slug where))))

(defrecord IdField [row env]
  Field
  (table-additions [this field] [[(keyword field) "SERIAL"]]) ;; "PRIMARY KEY"]])
  (subfield-names [this field] [])
  (setup-field [this spec] nil)
  (cleanup-field [this] nil)
  (target-for [this] nil)
  (update-values [this content values] values)
  (post-update [this content] content)
  (pre-destroy [this content] content)
  (join-fields [this prefix opts] [])
  (join-conditions [this prefix opts] [])
  (build-where
    [this prefix opts]
    (field-where this prefix opts pure-where))
  (natural-orderings [this prefix opts])
  (recompose-field [this mass opts])
  (field-from [this content opts] (content (keyword (:slug row))))
  (render [this content opts] content))
  
(defrecord IntegerField [row env]
  Field
  (table-additions [this field] [[(keyword field) :integer]])
  (subfield-names [this field] [])
  (setup-field [this spec] nil)
  (cleanup-field [this] nil)
  (target-for [this] nil)

  (update-values [this content values]
    (let [key (keyword (:slug row))]
      (if (contains? content key)
        (try
          (let [value (content key)
                tval (Integer. value)]
            (assoc values key tval))
          (catch Exception e values))
        values)))

  (post-update [this content] content)
  (pre-destroy [this content] content)
  (join-fields [this prefix opts] [])
  (join-conditions [this prefix opts] [])
  (build-where
    [this prefix opts]
    (field-where this prefix opts pure-where))
  (natural-orderings [this prefix opts])
  (recompose-field [this mass opts])
  (field-from [this content opts] (content (keyword (:slug row))))
  (render [this content opts] content))
  
(defrecord DecimalField [row env]
  Field
  (table-additions [this field] [[(keyword field) :decimal]])
  (subfield-names [this field] [])
  (setup-field [this spec] nil)
  (cleanup-field [this] nil)
  (target-for [this] nil)

  (update-values [this content values]
    (let [key (keyword (:slug row))]
      (if (contains? content key)
        (try
          (let [value (content key)
                tval (if (isa? (type value) String)
                       (BigDecimal. value)
                       value)]
            (assoc values key tval))
          (catch Exception e values))
        values)))

  (post-update [this content] content)
  (pre-destroy [this content] content)
  (join-fields [this prefix opts] [])
  (join-conditions [this prefix opts] [])
  (build-where
    [this prefix opts]
    (field-where this prefix opts pure-where))
  (natural-orderings [this prefix opts])
  (recompose-field [this mass opts])
  (field-from [this content opts] (content (keyword (:slug row))))
  (render [this content opts]
    (update-in content [(keyword (:slug row))] str)))
  
(defrecord StringField [row env]
  Field
  (table-additions [this field] [[(keyword field) "varchar(256)"]])
  (subfield-names [this field] [])
  (setup-field [this spec] nil)
  (cleanup-field [this] nil)
  (target-for [this] nil)
  (update-values [this content values]
    (let [key (keyword (:slug row))]
      (if (contains? content key)
        (assoc values key (content key))
        values)))

  (post-update [this content] content)
  (pre-destroy [this content] content)
  (join-fields [this prefix opts] [])
  (join-conditions [this prefix opts] [])
  (build-where
    [this prefix opts]
    (field-where this prefix opts string-where))
  (natural-orderings [this prefix opts])
  (recompose-field [this mass opts])
  (field-from [this content opts] (content (keyword (:slug row))))
  (render [this content opts] content))

(defrecord SlugField [row env]
  Field
  (table-additions [this field] [[(keyword field) "varchar(256)"]])
  (subfield-names [this field] [])
  (setup-field [this spec] nil)
  (cleanup-field [this] nil)
  (target-for [this] nil)
  (update-values [this content values]
    (let [key (keyword (:slug row))]
      (cond
       (env :link)
         (let [icon (content (keyword (-> env :link :slug)))]
           (if icon
             (assoc values key (slugify icon))
             values))
       (contains? content key) (assoc values key (slugify (content key)))
       :else values)))
  (post-update [this content] content)
  (pre-destroy [this content] content)
  (join-fields [this prefix opts] [])
  (join-conditions [this prefix opts] [])
  (build-where
    [this prefix opts]
    (field-where this prefix opts string-where))
  (natural-orderings [this prefix opts])
  (recompose-field [this mass opts])
  (field-from [this content opts] (content (keyword (:slug row))))
  (render [this content opts] content))

(defrecord TextField [row env]
  Field
  (table-additions [this field] [[(keyword field) :text]])
  (subfield-names [this field] [])
  (setup-field [this spec] nil)
  (cleanup-field [this] nil)
  (target-for [this] nil)
  (update-values [this content values]
    (let [key (keyword (:slug row))]
      (if (contains? content key)
        (assoc values key (content key))
        values)))
  (post-update [this content] content)
  (pre-destroy [this content] content)
  (join-fields [this prefix opts] [])
  (join-conditions [this prefix opts] [])
  (build-where
    [this prefix opts]
    (field-where this prefix opts string-where))
  (natural-orderings [this prefix opts])
  (recompose-field [this mass opts])
  (field-from [this content opts] (adapter/text-value @config/db-adapter (content (keyword (:slug row)))))
  (render [this content opts] content))

(defrecord BooleanField [row env]
  Field
  (table-additions [this field] [[(keyword field) :boolean]])
  (subfield-names [this field] [])
  (setup-field [this spec] nil)
  (cleanup-field [this] nil)
  (target-for [this] nil)
  (update-values [this content values]
    (let [key (keyword (:slug row))]
      (if (contains? content key)
        (try
          (let [value (content key)
                tval (if (isa? (type value) String)
                       (Boolean/parseBoolean value)
                       value)]
            (assoc values key tval))
          (catch Exception e values))
        values)))
  (post-update [this content] content)
  (pre-destroy [this content] content)
  (join-fields [this prefix opts] [])
  (join-conditions [this prefix opts] [])
  (build-where
    [this prefix opts]
    (field-where this prefix opts pure-where))
  (natural-orderings [this prefix opts])
  (recompose-field [this mass opts])
  (field-from [this content opts] (content (keyword (:slug row))))
  (render [this content opts] content))

(defn build-extract
  [prefix field [index value]]
  (db/clause "extract(%1 from %2.%3) = %4" [(name index) prefix (name field) value]))

(defn timestamp-where
  "To find something by a certain timestamp you must provide a map with keys into
   the date or time.  Example:
     (timestamp-where :created_at {:day 15 :month 7 :year 2020})
   would find all rows who were created on July 15th, 2020."
  [prefix field where]
  (join " and " (map (partial build-extract (suffix-prefix prefix) field) where)))

(defrecord TimestampField [row env]
  Field
  (table-additions [this field] [[(keyword field) "timestamp" "NOT NULL" "DEFAULT current_timestamp"]])
  (subfield-names [this field] [])
  (setup-field [this spec] nil)
  (cleanup-field [this] nil)
  (target-for [this] nil)
  (update-values
    [this content values]
    (let [key (keyword (:slug row))]
      (cond
       ;; (= key :updated_at) (assoc values key :current_timestamp)
       (contains? content key)
       (let [value (content key)
             timestamp (if (string? value) (read-date value) value)]
         (if timestamp
           (assoc values key timestamp)
           values))
       :else values)))
  (post-update [this content] content)
  (pre-destroy [this content] content)
  (join-fields [this prefix opts] [])
  (join-conditions [this prefix opts] [])
  (build-where
    [this prefix opts]
    (field-where this prefix opts timestamp-where))
  (natural-orderings [this prefix opts])
  (recompose-field [this mass opts])
  (field-from [this content opts] (content (keyword (:slug row))))
  (render [this content opts]
    (update-in content [(keyword (:slug row))] format-date)))

;; forward reference for Fields that need them
(declare make-field)
(declare model-render)
(declare invoke-model)
(declare create)
(declare update)
(declare destroy)
(declare model-select-fields)
(declare model-join-fields)
(declare model-join-conditions)
(declare model-where-conditions)
(declare model-natural-orderings)
(declare recompose)
(def models (ref {}))

(defn pad-break-id [id]
  (let [root (str id)
        len (count root)
        pad-len (- 8 len)
        pad (apply str (repeat pad-len "0"))
        halves (map #(apply str %) (partition 4 (str pad root)))
        path (join "/" halves)]
    path))

(defn asset-dir [asset]
  (pathify ["assets/" (pad-break-id (asset :id))]))

(defn asset-path [asset]
  (if (and asset (asset :filename))
    (pathify [(asset-dir asset) (asset :filename)])
    ""))

(defn build-where-table
  [table field [index value]]
  (str (name table) "." (name field) " = "))

(defn asset-where
  [field prefix where]
  ())

(defrecord AssetField [row env]
  Field
  (table-additions [this field] [])
  (subfield-names [this field] [(str field "_id")])
  (setup-field [this spec]
    (update :model (row :model_id)
            {:fields [{:name (titleize (str (:slug row) "_id"))
                       :type "integer"
                       :editable false}]} {:op :migration}))
  (cleanup-field [this]
    (let [fields ((models (row :model_id)) :fields)
          id (keyword (str (:slug row) "_id"))]
      (destroy :field (-> fields id :row :id))))
  (target-for [this] nil)
  (update-values [this content values] values)
  (post-update [this content] content)
  (pre-destroy [this content] content)

  (join-fields [this prefix opts]
    (model-select-fields (:asset @models) (:slug row) opts))

  (join-conditions [this prefix opts]
    [(db/clause "left outer join asset %1 on (%2.%1_id = %1.id)"
                [(:slug row) prefix])])

  (build-where
    [this prefix opts]
    (with-nesting :where opts (:slug row)
      (fn [down]
        (model-where-conditions (:asset @models) (:slug row) down))))

  (natural-orderings [this prefix opts])

  (recompose-field
    [this mass opts]
    (let [clay (recompose (:asset @models) (:slug row) mass {})]
      (if (:id clay) clay)))

  (field-from [this content opts]
    (let [asset-id (content (keyword (str (:slug row) "_id")))
          asset (or (db/choose :asset asset-id) {})]
      (assoc asset :path (asset-path asset))))

  (render [this content opts]
    (update-in content [(keyword (:slug row))] 
               #(model-render (:asset @models) % opts))))

(defn full-address [address]
  (join " " [(address :address)
             (address :address_two)
             (address :postal_code)
             (address :city)
             (address :state)
             (address :country)]))

(defn geocode-address [address]
  (let [code (geo/geocode (full-address address))]
    (if (empty? code)
      {}
      {:lat (-> (first code) :location :latitude)
       :lng (-> (first code) :location :longitude)})))

(defn geocode-address [address]
  (let [code (geo/geocode address)]
    (if (empty? code)
      {}
      {:lat (-> (first code) :location :latitude)
       :lng (-> (first code) :location :longitude)})))

(defrecord AddressField [row env]
  Field
  (table-additions [this field] [])
  (subfield-names [this field] [(str field "_id")])
  (setup-field [this spec]
    (update :model (row :model_id)
            {:fields [{:name (titleize (str (:slug row) "_id"))
                       :type "integer"
                       :editable false}]} {:op :migration}))
  (cleanup-field [this]
    (let [fields ((models (row :model_id)) :fields)
          id (keyword (str (:slug row) "_id"))]
      (destroy :field (-> fields id :row :id))))
  (target-for [this] nil)
  (update-values [this content values]
    (let [posted (content (keyword (:slug row)))
          idkey (keyword (str (:slug row) "_id"))
          preexisting (content idkey)
          address (if preexisting (assoc posted :id preexisting) posted)]
      (if address
        (let [geocode (geocode-address address)
              location (create :location (merge address geocode))]
          (assoc values idkey (location :id)))
        values)))
  (post-update [this content] content)
  (pre-destroy [this content] content)

  (join-fields [this prefix opts]
    (model-select-fields (:location @models) (:slug row) opts))
  (join-conditions [this prefix opts]
    [(db/clause "left outer join location %1 on (%2.%1_id = %1.id)"
                [(:slug row) prefix])])

  (build-where
    [this prefix opts]
    (with-nesting :where opts (:slug row)
      (fn [down]
        (model-where-conditions (:location @models) (:slug row) down))))

  (natural-orderings [this prefix opts])

  (recompose-field
    [this mass opts]
    (let [clay (recompose (:location @models) (:slug row) mass {})]
      (if (:id clay) clay)))

  (field-from [this content opts]
    (or (db/choose :location (content (keyword (str (:slug row) "_id")))) {}))

  (render [this content opts]
    (update-in content [(keyword (:slug row))] 
               #(model-render (:location @models) % opts))))

(defn assoc-field
  [content field opts]
  (assoc
    content
    (keyword (-> field :row :slug))
    (field-from field content opts)))

(defn from
  "takes a model and a raw db row and converts it into a full
  content representation as specified by the supplied opts.
  some opts that are supported:
    include - a nested hash of association includes.  if a key matches
    the name of an association any content associated to this item through
    that association will be inserted under that key."
  [model content opts]
  (reduce #(assoc-field %1 %2 opts) content (vals (model :fields))))

(defn present?
  [x]
  (and (not (nil? x)) (or (number? x) (not (empty? x)))))

(defn collection-where
  [field prefix opts]  
  (let [slug (-> field :row :slug)]
    (with-nesting :where opts slug
      (fn [down]
        (let [target (@models (-> field :row :target_id))
              link (-> field :env :link :slug)
              subconditions (model-where-conditions target slug down)
              params [prefix link (:slug target) slug subconditions]]
          (db/clause "%1.id in (select %4.%2_id from %3 %4 where %5)" params))))))

(defn collection-render
  [field content opts]
  (if-let [include (:include opts)]
    (let [slug (keyword (-> field :row :slug))]
      (if-let [sub (slug include)]
        (let [target (@models (-> field :row :target_id))
              down {:include sub}]
          (update-in
           content [slug]
           (fn [col]
             (doall
              (map
               (fn [to]
                 (model-render target to down))
               col)))))
        content))
    content))

(defrecord CollectionField [row env]
  Field
  (table-additions [this field] [])
  (subfield-names [this field] [])

  (setup-field [this spec]
    (if (or (nil? (row :link_id)) (zero? (row :link_id)))
      (let [model (models (row :model_id))
            target (models (row :target_id))
            reciprocal-name (or (spec :reciprocal_name) (model :name))
            part (create :field
                   {:name reciprocal-name
                    :type "part"
                    :model_id (row :target_id)
                    :target_id (row :model_id)
                    :link_id (row :id)
                    :dependent (row :dependent)})]
        (db/update :field ["id = ?" (Integer. (row :id))] {:link_id (part :id)}))))

  (cleanup-field [this]
    (try
      (do (destroy :field (-> env :link :id)))
      (catch Exception e (str e))))

  (target-for [this] (models (row :target_id)))

  (update-values [this content values]
    (let [removed (keyword (str "removed_" (:slug row)))]
      (if (present? (content removed))
        (let [ex (map #(Integer. %) (split (content removed) #","))
              part (env :link)
              part-key (keyword (str (part :slug) "_id"))
              target ((models (row :target_id)) :slug)]
          (if (row :dependent)
            (doall (map #(destroy target %) ex))
            (doall (map #(update target % {part-key nil}) ex)))
          values)
        values)))

  (post-update [this content]
    (if-let [collection (content (keyword (:slug row)))]
      (let [part (env :link)
            part-key (keyword (str (part :slug) "_id"))
            model (models (part :model_id))
            updated (doall
                     (map
                      #(create
                        (model :slug)
                        (merge % {part-key (content :id)}))
                      collection))]
        (assoc content (keyword (:slug row)) updated))
      content))

  (pre-destroy [this content]
    (if (or (row :dependent) (-> env :link :dependent))
      (let [parts (field-from this content {:include {(keyword (:slug row)) {}}})
            target (keyword ((target-for this) :slug))]
        (doall (map #(destroy target (% :id)) parts))))
    content)

  (join-fields [this prefix opts]
    (with-nesting :include opts (:slug row)
      (fn [down]
        (let [target (@models (:target_id row))]
          (model-select-fields target (:slug row) down)))))

  (join-conditions [this prefix opts]
    (with-nesting :include opts (:slug row)
      (fn [down]
        (let [target (@models (:target_id row))
              link (-> this :env :link :slug)
              downstream (model-join-conditions target (:slug row) down)
              params [(:slug target) (:slug row) prefix link]]
          (concat
           [(db/clause "left outer join %1 %2 on (%3.id = %2.%4_id)" params)]
           downstream)))))

  (build-where
    [this prefix opts]
    (collection-where this prefix opts))

  (natural-orderings [this prefix opts]
    (let [target (@models (:target_id row))
          link (-> this :env :link :slug)
          downstream (model-natural-orderings target (:slug row) opts)]
      [(db/clause "%1.%2_position asc" [prefix link]) downstream]))

  (recompose-field
    [this mass opts]
    (with-nesting :include opts (:slug row)
      (fn [down]
        (let [target (@models (:target_id row))
              clay (recompose target (:slug row) mass down)]
          (if (:id clay) [clay] [])))))

  (field-from [this content opts]
    (with-nesting :include opts (:slug row)
      (fn [down]
        (let [link (-> this :env :link :slug)
              parts (db/fetch (-> (target-for this) :slug) (str link "_id = %1 order by %2 asc") (content :id) (str link "_position"))]
          (map #(from (target-for this) % down) parts)))))

  (render [this content opts]
    (collection-render this content opts)))

(defrecord PartField [row env]
  Field

  (table-additions [this field] [])
  (subfield-names [this field] [(str field "_id") (str field "_position")])

  (setup-field [this spec]
    (let [model_id (row :model_id)
          model (models model_id)
          target (models (row :target_id))
          reciprocal-name (or (spec :reciprocal_name) (model :name))]
      (if (or (nil? (row :link_id)) (zero? (row :link_id)))
        (let [collection (create :field
                           {:name reciprocal-name
                            :type "collection"
                            :model_id (row :target_id)
                            :target_id model_id
                            :link_id (row :id)})]
          (db/update :field ["id = ?" (Integer. (row :id))] {:link_id (collection :id)})))

      (update :model model_id
        {:fields
         [{:name (titleize (str (:slug row) "_id"))
           :type "integer"
           :editable false}
          {:name (titleize (str (:slug row) "_position"))
           :type "integer"
           :editable false}]} {:op :migration})))

  (cleanup-field [this]
    (let [fields ((models (row :model_id)) :fields)
          id (keyword (str (:slug row) "_id"))
          position (keyword (str (:slug row) "_position"))]
      (destroy :field (-> fields id :row :id))
      (destroy :field (-> fields position :row :id))
      (try
        (do (destroy :field (-> env :link :id)))
        (catch Exception e (str e)))))

  (target-for [this] (models (-> this :row :target_id)))

  (update-values [this content values] values)

  (post-update [this content] content)

  (pre-destroy [this content] content)

  (join-fields [this prefix opts]
    (with-nesting :include opts (:slug row)
      (fn [down]
        (let [target (@models (:target_id row))]
          (model-select-fields target (:slug row) down)))))

  (join-conditions [this prefix opts]
    (with-nesting :include opts (:slug row)
      (fn [down]
        (let [target (@models (:target_id row))
              downstream (model-join-conditions target (:slug row) down)
              params [(:slug target) (:slug row) prefix]]
          (concat
           [(db/clause "left outer join %1 %2 on (%3.%2_id = %2.id)" params)]
           downstream)))))

  (build-where
    [this prefix opts]
    (with-nesting :where opts (:slug row)
      (fn [down]
        (let [target (@models (:target_id row))]
          (model-where-conditions target (:slug row) down)))))

  (natural-orderings [this prefix opts]
    (let [target (@models (:target_id row))
          downstream (model-natural-orderings target (:slug row) opts)]
      downstream))

  (recompose-field
    [this mass opts]
    (with-nesting :include opts (:slug row)
      (fn [down]
        (let [target (@models (:target_id row))
              clay (recompose target (:slug row) mass down)]
          (if (:id clay) clay)))))

  (field-from [this content opts]
    (with-nesting :include opts (:slug row)
      (fn [down]
        (if-let [pointing (content (keyword (str (:slug row) "_id")))]
          (let [collector (db/choose (-> (target-for this) :slug) pointing)]
            (from (target-for this) collector down))))))

  (render [this content opts]
    (if-let [include (:include opts)]
      (let [slug (keyword (:slug row))
            target (@models (:target_id row))
            down {:include (slug include)}]
        (update-in
         content [slug] 
         (fn [part]
           (if part
             (model-render target part down)))))
      content)))

(defrecord TieField [row env]
  Field

  (table-additions [this field] [])
  (subfield-names [this field] [(str field "_id")])

  (setup-field [this spec]
    (let [model_id (row :model_id)
          model (models model_id)]
      (update :model model_id
        {:fields
         [{:name (titleize (str (:slug row) "_id"))
           :type "integer"
           :editable false}]} {:op :migration})))

  (cleanup-field [this]
    (let [fields ((models (row :model_id)) :fields)
          id (keyword (str (:slug row) "_id"))]
      (destroy :field (-> fields id :row :id))))

  (target-for [this] this)

  (update-values [this content values] values)

  (post-update [this content] content)

  (pre-destroy [this content] content)

  (join-fields [this prefix opts]
    (with-nesting :include opts (:slug row)
      (fn [down]
        (let [target (@models (:model_id row))]
          (model-select-fields target (:slug row) down)))))

  (join-conditions [this prefix opts]
    (with-nesting :include opts (:slug row)
      (fn [down]
        (let [target (@models (:model_id row))
              downstream (model-join-conditions target (:slug row) down)
              params [(:slug target) (:slug row) prefix]]
          (concat
           [(db/clause "left outer join %1 %2 on (%3.%2_id = %2.id)" params)]
           downstream)))))

  (build-where
    [this prefix opts]
    (with-nesting :where opts (:slug row)
      (fn [down]
        (let [target (@models (:model_id row))]
          (model-where-conditions target (:slug row) down)))))

  (natural-orderings [this prefix opts]
    (let [target (@models (:model_id row))
          downstream (model-natural-orderings target (:slug row) opts)]
      downstream))

  (recompose-field
    [this mass opts]
    (with-nesting :include opts (:slug row)
      (fn [down]
        (let [target (@models (:model_id row))
              clay (recompose target (:slug row) mass down)]
          (if (:id clay) clay)))))

  (field-from [this content opts]
    (with-nesting :include opts (:slug row)
      (fn [down]
        (if-let [tie-key (keyword (str (:slug row) "_id"))]
          (let [model (@models (:model_id row))]
            (from model (db/choose (:slug model) (content tie-key)) down))))))

  (render [this content opts]
    (if-let [include (:include opts)]
      (let [slug (keyword (:slug row))
            target (@models (:model_id row))
            down {:include (slug include)}]
        (update-in
         content [slug] 
         (fn [tie]
           (if tie
             (model-render target tie down)))))
      content)))

(defn join-table-name
  "construct a join table name out of two link names"
  [a b]
  (join "_" (sort (map slugify [a b]))))

(defn link-join-name
  "Given a link field, return the join table name used by that link."
  [field]
  (let [reciprocal (-> field :env :link)
        from-name (-> field :row :name)
        to-name (reciprocal :slug)]
    (keyword (join-table-name from-name to-name))))

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

(defn link
  "Link two rows by the given LinkField.  This function accepts its arguments
   in order, so that 'a' is a row from the model containing the given field."
  [field a b]
  (let [{from-key :from to-key :to join-key :join} (link-keys field)
        target (models (-> field :row :target_id))
        linkage (create (target :slug) b)
        params [join-key from-key (linkage :id) to-key (a :id)]
        preexisting (apply (partial db/query "select * from %1 where %2 = %3 and %4 = %5") params)]
    (if preexisting
      preexisting
      (create join-key {from-key (linkage :id) to-key (a :id)}))))

(defn table-columns
  "Return a list of all columns for the table corresponding to this model."
  [slug]
  (let [model (models (keyword slug))]
    (apply concat
           (map (fn [field]
                  (map #(name (first %))
                       (table-additions field (-> field :row :slug))))
                (vals (model :fields))))))

(defn retrieve-links
  "Given a link field and a row, find all target rows linked to the given row
   by this field."
  [field content]
  (let [{from-key :from to-key :to join-key :join} (link-keys field)
        target (models (-> field :row :target_id))
        target-slug (target :slug)
        field-names (map #(str target-slug "." %) (table-columns target-slug))
        field-select (join "," field-names)
        query "select %1 from %2 inner join %3 on (%2.id = %3.%4) where %3.%5 = %6"
        params [field-select target-slug join-key from-key to-key (content :id)]]
    (apply (partial db/query query) params)))

(defn remove-link
  [field from-id to-id]
  (let [{from-key :from to-key :to join-key :join} (link-keys field)
        params [join-key from-key to-id to-key from-id]
        preexisting (first (apply (partial db/query "select * from %1 where %2 = %3 and %4 = %5") params))]
    (if preexisting
      (destroy join-key (preexisting :id)))))

(defn link-join-conditions
  [this prefix opts]
  (let [slug (-> this :row :slug)]
    (with-nesting :include opts slug
      (fn [down]
        (let [reciprocal (-> this :env :link)
              from-name slug
              to-name (reciprocal :slug)
              join-key (join-table-name from-name to-name)
              join-alias (str from-name "_join")
              target (@models (-> this :row :target_id))
              join-params [join-key join-alias to-name prefix]
              link-params [(:slug target) slug join-alias]
              downstream (model-join-conditions target slug down)]
          (concat
           [(db/clause "left outer join %1 %2 on (%2.%3_id = %4.id)" join-params)
            (db/clause "left outer join %1 %2 on (%2.id = %3.%2_id)" link-params)]
           downstream))))))

(defn link-where
  [field prefix opts]  
  (let [slug (-> field :row :slug)
        clause "%1.id in (select %4.%2_id from %3 %4 inner join %5 %6 on (%4.%6_id = %6.id) where %7)"]
    (with-nesting :where opts slug
      (fn [down]
        (let [target (@models (-> field :row :target_id))
              link (-> field :env :link :slug)
              subconditions (model-where-conditions target slug down)
              reciprocal (-> field :env :link)
              from-name slug
              to-name (reciprocal :slug)
              join-key (join-table-name from-name to-name)
              join-alias (str from-name "_join")
              params [prefix link join-key join-alias
                      (:slug target) slug subconditions]]
          (db/clause clause params))))))

(defn link-natural-orderings
  [this prefix opts]
  (let [slug (-> this :row :slug)
        reciprocal (-> this :env :link)
        join-alias (str slug "_join")
        target (@models (-> this :row :target_id))
        downstream (model-natural-orderings target slug opts)]
    [(db/clause "%1.%2_position asc" [join-alias slug]) downstream]))

(defn link-render
  [this content opts]
  (if-let [include (:include opts)]
    (let [slug (keyword (-> this :row :slug))]
      (if-let [sub (slug include)]
        (let [target (@models (-> this :row :target_id))
              down {:include sub}]
          (update-in
           content [slug]
           (fn [col]
             (doall
              (map
               (fn [to]
                 (model-render target to down))
               col)))))
        content))
    content))

(defrecord LinkField [row env]
  Field

  (table-additions [this field] [])
  (subfield-names [this field] [])

  (setup-field [this spec]
    (if (or (nil? (row :link_id)) (zero? (row :link_id)))
      (let [model (models (row :model_id))
            target (models (row :target_id))
            reciprocal-name (or (spec :reciprocal_name) (model :name))
            join-name (join-table-name (spec :name) reciprocal-name)
            link (create :field
                   {:name reciprocal-name
                    :type "link"
                    :model_id (row :target_id)
                    :target_id (row :model_id)
                    :link_id (row :id)
                    :dependent (row :dependent)})]
        (create :model
                {:name (titleize join-name)
                 :join_model true
                 :fields
                 [{:name (spec :name)
                   :type "part"
                   :dependent true
                   :reciprocal_name (str reciprocal-name " Join")
                   :target_id (row :target_id)}
                  {:name reciprocal-name
                   :type "part"
                   :dependent true
                   :reciprocal_name (str (spec :name) " Join")
                   :target_id (row :model_id)}]} {:op :migration})
        (db/update :field ["id = ?" (Integer. (row :id))] {:link_id (link :id)}))))

  (cleanup-field [this]
    (try
      (let [join-name (link-join-name this)]
        (destroy :model (-> @models join-name :id))
        (destroy :field (row :link_id)))
      (catch Exception e (str e))))

  (target-for [this] (models (row :target_id)))

  (update-values [this content values]
    (let [removed (content (keyword (str "removed_" (:slug row))))]
      (debug content)
      (if (present? removed)
        (let [ex (map #(Integer. %) (split removed #","))]
          (doall (map #(remove-link this (content :id) %) ex)))))
    values)

  (post-update [this content]
    (if-let [collection (content (keyword (:slug row)))]
      (let [linked (doall (map #(link this content %) collection))
            with-links (assoc content (keyword (str (:slug row) "_join")) linked)]
        (assoc content (:slug row) (retrieve-links this content))))
    content)

  (pre-destroy [this content]
    content)

  (join-fields [this prefix opts]
    (with-nesting :include opts (:slug row)
      (fn [down]
        (let [target (@models (:target_id row))]
          (model-select-fields target (:slug row) down)))))

  (join-conditions [this prefix opts]
    (link-join-conditions this prefix opts))

  (build-where
    [this prefix opts]
    (link-where this prefix opts))

  (natural-orderings [this prefix opts]
    (link-natural-orderings this prefix opts))

  (recompose-field
    [this mass opts]
    (with-nesting :include opts (:slug row)
      (fn [down]
        (let [target (@models (:target_id row))
              clay (recompose target (:slug row) mass down)]
          (if (:id clay) [clay] [])))))

  (field-from [this content opts]
    (with-nesting :include opts (:slug row)
      (fn [down]
        (let [target (target-for this)]
          (map
           #(from target % down)
           (retrieve-links this content))))))

  (render [this content opts]
    (link-render this content opts)))

(defn build-select
  [slug opts]
  (let [model ((keyword slug) @models)]))

(defn model-table-fields
  "All rows in the table governed by this model."
  [model]
  (map table-fields (vals (:fields model))))

(defn model-select-fields
  "Build a set of select fields based on the given model."
  [model prefix opts]
  (let [fields (vals (:fields model))
        sf (fn [field]
             (select-fields field (name prefix) opts))
        model-fields (map sf fields)]
    (set (apply concat model-fields))))

(defn model-join-conditions
  "Find all necessary table joins for this query based on the arbitrary
   nesting of the include option."
  [model prefix opts]
  (let [fields (:fields model)]
    (apply
     concat
     (map
      (fn [field]
        (join-conditions field prefix opts))
      (vals fields)))))

(defn model-select-query
  "Build the select query for this model by the given prefix based on the
   particular nesting of the include map."
  [model prefix opts]
  (let [selects (join ", " (model-select-fields model prefix opts))
        joins (join " " (model-join-conditions model prefix opts))]
    (join " " ["select" selects "from" (:slug model) (name prefix) joins])))

(defn model-limit-offset
  [model prefix where limit offset]
  (let [condition (if (empty? where)
                    ""
                    (str "where " where " "))
        params [prefix (:slug model) condition limit offset]]
    (db/clause "%1.id in (select %1.id from %2 %1 %3limit %4 offset %5)" params)))

(defn model-where-conditions
  [model prefix opts]
  (let [eyes
        (filter
         identity
         (map
          (fn [field]
            (build-where field prefix opts))
          (vals (:fields model))))]
    (join " and " (flatten eyes))))

(defn model-natural-orderings
  [model prefix opts]
  (let [fields (:fields model)]
    (flatten
     (map
      (fn [order-key]
        (let [field (order-key fields)]
          (natural-orderings
           field (-> field :row :slug)
           {:include (-> opts :include order-key)})))
      (keys (:include opts))))))

(defn build-order
  [prefix order]
  (if (re-find #"^[^.]+\.\w+ (de|a)sc$" order)
    order
    (str prefix "." order)))

(defn model-order-statement
  [model opts]
  (let [order
        (map
         (partial build-order (:slug model))
         (or (:order opts) ["position asc"]))
        natural (model-natural-orderings model (:slug model) opts)
        statement (join ", " (concat order natural))]
      (db/clause " order by %1" [statement])))

(defn uberquery
  "The query to bind all queries.  Returns every facet of every row given an
   arbitrary nesting of include relationships (also known as the uberjoin)."
  [model opts]
  (let [query (model-select-query model (:slug model) opts)
        where (model-where-conditions model (:slug model) opts)

        limit-offset
        (if-let [limit (:limit opts)]
          (model-limit-offset model (:slug model) where limit (or (:offset opts) 0)))

        condition (or limit-offset where)

        full-condition
        (if (not (empty? condition))
          (str " where " condition))

        order (model-order-statement model opts)]
    (db/query (str query full-condition order))))

(defn match-prefix
  "Determine whether this bit (key from an uberjoin) is governed by the given prefix."
  [prefix bit]
  (if-let [pattern (re-find (re-pattern (str "^" (name prefix) "\\$(.*)$")) (name bit))]
    [bit (keyword (last pattern))]))

(defn blank-join?
  [void]
  (or (nil? void) (not (:id void))))

(defn pretach
  "This attaches from the mass to the spark even if
   the value from the mass for the given field is nil."
  [spark field mass opts]
  (let [form (recompose-field field mass opts)
        sign (keyword (-> field :row :slug))]
    (if (or form (not (contains? spark sign)))
      (assoc spark (keyword (-> field :row :slug)) form)
      spark)))

(defn attach
  "Only attach values from the mass to the spark based on this field
   if the value for that field is not nil."
  [spark field mass opts]
  (if-let [form (recompose-field field mass opts)]
    (assoc spark (keyword (-> field :row :slug)) form)
    spark))

(defn recompose
  "Given a model and a mass of undetermined columns from an uberjoin,
   reconstitutes the values from other tables living side by side
   in the mass into a nested map based on the fields and associations
   present in the model."
  [model prefix mass opts]
  (let [patterns
        (filter
         identity
         (map
          (partial match-prefix prefix)
          (keys mass)))

        spark
        (reduce
         (fn [golem [bit pattern]]
           (assoc golem pattern (bit mass)))
         {} patterns)

        axons
        (reduce
         (fn [spark field]
           (attach spark field mass opts))
         spark (vals (:fields model)))]

    axons))

(defn renew
  "Recompose the swaths of undifferentiated uberjoin rows into nested maps
   based on the fields in the given model."
  [model prefix swaths opts]
  (map #(recompose model prefix % opts) swaths))

(declare fuse)
(declare fuse-merge-with)

(defn fuse-merge
  "Fuse two subentries of an uberjoin."
  [a b]
  (cond
   (not (and a b)) (or a b)
   (some sequential? [a b]) (fuse (concat a b))
   (some map? [a b]) (fuse-merge-with a b)
   :else a))

(defn fuse-merge-with
  [a b]
  (merge-with fuse-merge a b))

(defn reduce-fuse-merge
  [pulse]
  (reduce fuse-merge-with {} pulse))

(defn fuse
  "Take many threads from an uberquery and fuse them into maps where each
   exploded row is collapsed into a single map."
  [threads]
  (if threads
    (let [attraction (group-by #(:id %) threads)
          light (map reduce-fuse-merge (vals attraction))]
      light)))

(defn gather
  [slug opts]
  (let [model (slug @models)
        resurrected (uberquery model opts)
        threads (renew model (:slug model) resurrected opts)]
    (fuse threads)))

(defn process-include
  [include]
  (if (and include (not (empty? include)))
    (let [clauses (split include #",")
          paths (map #(map keyword (split % #"\.")) clauses)
          ubermap
          (reduce
           (fn [include path]
             (update-in include path (fn [_] {})))
           {} paths)]
      ubermap)
    {}))

(defn where-path
  [where]
  (let [[path condition] (split where #":")]
    [(map keyword (split path #"\.")) condition]))

(defn process-where
  [where]
  (if (and where (not (empty? where)))
    (let [clauses (split where #",")
          paths (map where-path clauses)
          ubermap
          (reduce
           (fn [where [path condition]]
             (update-in where path (fn [_] condition)))
           {} paths)]
      ubermap)
    {}))

(defn process-order
  [order]
  (if (and order (not (empty? order)))
    (split order #",")
    []))

(defn find-all
  [slug opts]
  (let [include (process-include (:include opts))
        where (process-where (:where opts))
        order (process-order (:order opts))]
    (gather slug (merge opts {:include include :where where :order order}))))

(defn find-one
  [slug opts]
  (let [oneify (assoc opts :limit 1)]
    (first (find-all slug oneify))))

(def field-constructors
  {:id (fn [row] (IdField. row {}))
   :integer (fn [row] (IntegerField. row {}))
   :decimal (fn [row] (DecimalField. row {}))
   :string (fn [row] (StringField. row {}))
   :slug (fn [row] 
           (let [link (db/choose :field (row :link_id))]
             (SlugField. row {:link link})))
   :text (fn [row] (TextField. row {}))
   :boolean (fn [row] (BooleanField. row {}))
   :timestamp (fn [row] (TimestampField. row {}))
   :asset (fn [row] (AssetField. row {}))
   :address (fn [row] (AddressField. row {}))
   :collection (fn [row]
                 (let [link (if (row :link_id) (db/choose :field (row :link_id)))]
                   (CollectionField. row {:link link})))
   :part (fn [row]
           (let [link (db/choose :field (row :link_id))]
             (PartField. row {:link link})))
   :tie (fn [row] (TieField. row {}))
   :link (fn [row]
           (let [link (db/choose :field (row :link_id))]
             (LinkField. row {:link link})))
   })

(def base-fields [{:name "Id" :type "id" :locked true :immutable true :editable false}
                  {:name "Position" :type "integer" :locked true}
                  {:name "Status" :type "integer" :locked true}
                  {:name "Locale Id" :type "integer" :locked true :editable false}
                  {:name "Env Id" :type "integer" :locked true :editable false}
                  {:name "Locked" :type "boolean" :locked true :immutable true :editable false}
                  {:name "Created At" :type "timestamp" :locked true :immutable true :editable false}
                  {:name "Updated At" :type "timestamp" :locked true :editable false}])

(defn make-field
  "turn a row from the field table into a full fledged Field record"
  [row]
  ((field-constructors (keyword (row :type))) row))

(defn model-render
  "render a piece of content according to the fields contained in the model
  and given by the supplied opts"
  [model content opts]
  (let [fields (vals (:fields model))]
    (reduce
     (fn [content field]
       (render field content opts))
     content fields)))

(def lifecycle-hooks (ref {}))

(defn make-lifecycle-hooks
  "establish the set of functions which are called throughout the lifecycle
  of all rows for a given model (slug).  the possible hook points are:
    :before_create     -- called for create only, before the record is made
    :after_create      -- called for create only, now the record has an id
    :before_update     -- called for update only, before any changes are made
    :after_update      -- called for update only, now the changes have been committed
    :before_save       -- called for create and update
    :after_save        -- called for create and update
    :before_destroy    -- only called on destruction, record has not yet been removed
    :after_destroy     -- only called on destruction, now the db has no record of it"
  [slug]
  (if (not (@lifecycle-hooks (keyword slug)))
    (let [hooks {(keyword slug)
                 {:before_create  (ref {})
                  :after_create   (ref {})
                  :before_update  (ref {})
                  :after_update   (ref {})
                  :before_save    (ref {})
                  :after_save     (ref {})
                  :before_destroy (ref {})
                  :after_destroy  (ref {})}}]
      (dosync
       (alter lifecycle-hooks merge hooks)))))

(defn run-hook
  "run the hooks for the given model slug given by timing.
  env contains any necessary additional information for the running of the hook"
  [slug timing env]
  (let [kind (lifecycle-hooks (keyword slug))]
    (if kind
      (let [hook (kind (keyword timing))]
        (reduce #((hook %2) %1) env (keys @hook))))))

(defn add-hook
  "add a hook for the given model slug for the given timing.
  each hook must have a unique id, or it overwrites the previous hook at that id."
  [slug timings id func]
  (let [timings (if (keyword? timings) [timings] timings)]
    (doseq [timing timings]
      (if-let [model-hooks (lifecycle-hooks (keyword slug))]
        (if-let [hook (model-hooks (keyword timing))]
          (let [hook-name (keyword id)]
            (dosync
             (alter hook merge {hook-name func})))
            (throw (Exception. (format "No model lifecycle hook called %s" timing))))
        (throw (Exception. (format "No model called %s" slug)))))))

(defn create-model-table
  "create an table with the given name."
  [name]
  (db/create-table (keyword name) []))

(def invoke-models)

(defn add-model-hooks []
  (add-hook :model :before_create :build_table (fn [env]
    (create-model-table (slugify (-> env :spec :name)))
    env))
  
  (add-hook :model :before_create :add_base_fields (fn [env]
    (assoc-in env [:spec :fields] (concat (-> env :spec :fields) base-fields))))

  ;; (add-hook :model :before_save :write_migrations (fn [env]
  ;;   (try                                                 
  ;;     (if (and (not (-> env :spec :locked)) (not (= (-> env :opts :op) :migration)))
  ;;       (let [now (.getTime (Date.))
  ;;             code (str "(use 'caribou.model)\n\n(defn migrate []\n  ("
  ;;                       (name (-> env :op)) " :model " (list 'quote (-> env :spec)) " {:op :migration}))\n(migrate)\n")]
  ;;         (with-open [w (io/writer (str "app/migrations/migration-" now ".clj"))]
  ;;           (.write w code))))
  ;;     (catch Exception e env))
  ;;   env))

  (add-hook :model :after_create :invoke (fn [env]
    (if (-> env :content :nested)
      (create :field
              {:name "Parent Id" :model_id (-> env :content :id) :type "integer"}))
    env))
  
  (add-hook :model :after_update :rename (fn [env]
    (let [original (-> env :original :slug)
          slug (-> env :content :slug)]
      (if (not (= original slug))
        (db/rename-table original slug)))
    env))

  (add-hook :model :after_save :invoke_all (fn [env]
    (invoke-models)
    env))

  (add-hook :model :after_destroy :cleanup (fn [env]
    (db/drop-table (-> env :content :slug))
    (invoke-models)
    env)))
  
(defn add-field-hooks []
  (add-hook :field :before_save :check_link_slug (fn [env]
    (assoc env :values 
      (if (-> env :spec :link_slug)
        (let [model_id (-> env :spec :model_id)
              link_slug (-> env :spec :link_slug)
              fetch (db/fetch :field "model_id = %1 and slug = '%2'" model_id link_slug)
              linked (first fetch)]
          (assoc (env :values) :link_id (linked :id)))
        (env :values)))))
  
  (add-hook :field :after_create :add_columns (fn [env]
    (let [field (make-field (env :content))
          model_id (-> env :content :model_id)
          model (models model_id)
          slug (if model
                 (model :slug)
                 ((db/choose :model model_id) :slug))
          default (-> env :spec :default_value)]
      (doall (map #(db/add-column slug (name (first %)) (rest %)) (table-additions field (-> env :content :slug))))
      (setup-field field (env :spec))
      (if (present? default)
        (db/set-default slug (-> env :content :slug) default))
      env)))
  
  (add-hook :field :after_update :reify_field (fn [env]
    (let [field (make-field (env :content))
          original (-> env :original :slug)
          slug (-> env :content :slug)
          odefault (-> env :original :default_value)
          default (-> env :content :default_value)
          model (models (-> field :row :model_id))
          spawn (apply zipmap (map #(subfield-names field %) [original slug]))
          transition (apply zipmap (map #(map first (table-additions field %)) [original slug]))]
      (if (not (= original slug))
        (do (doall (map #(update :field (-> ((model :fields) (keyword (first %))) :row :id) {:name (last %)}) spawn))
            (doall (map #(db/rename-column (model :slug) (first %) (last %)) transition))))
      (if (and (not (empty? default)) (not (= odefault default)))
        (db/set-default (model :slug) slug default)))
    env))

  (add-hook :field :after_destroy :drop_columns (fn [env]
    (try                                                  
      (let [model (models (-> env :content :model_id))
            field ((model :fields) (keyword (-> env :content :slug)))]
        (do (cleanup-field field))
        (doall (map #(db/drop-column ((models (-> field :row :model_id)) :slug) (first %)) (table-additions field (-> env :content :slug))))
        env)
      (catch Exception e env)))))

(defn invoke-model
  "translates a row from the model table into a nested hash with references
  to its fields in a hash with keys being the field slugs
  and vals being the field invoked as a Field protocol record."
  [model]
  (let [fields (db/query "select * from field where model_id = %1" (model :id))
        field-map (seq-to-map #(keyword (-> % :row :slug)) (map make-field fields))]
    (make-lifecycle-hooks (model :slug))
    (assoc model :fields field-map)))

(defn invoke-models
  "call to populate the application model cache in model/models.
  (otherwise we hit the db all the time with model and field selects)
  this also means if a model or field is changed in any way that model will
  have to be reinvoked to reflect the current state."
  []
  (let [rows (db/query "select * from model")
        invoked (doall (map invoke-model rows))]
     (add-model-hooks)
     (add-field-hooks)
     (dosync
      (alter models 
        (fn [in-ref new-models] new-models)
        (merge (seq-to-map #(keyword (% :slug)) invoked)
               (seq-to-map #(% :id) invoked))))))

(defn create
  "slug represents the model to be updated.
  the spec contains all information about how to update this row,
  including nested specs which update across associations.
  the only difference between a create and an update is if an id is supplied,
  hence this will automatically forward to update if it finds an id in the spec.
  this means you can use this create method to create or update something,
  using the presence or absence of an id to signal which operation gets triggered."
  ([slug spec]
     (create slug spec {}))
  ([slug spec opts]
     (if (present? (spec :id))
       (update slug (spec :id) spec opts)
       (let [model (models (keyword slug))
             values (reduce #(update-values %2 spec %1) {} (vals (dissoc (model :fields) :updated_at)))
             env {:model model :values values :spec spec :op :create :opts opts}
             _save (run-hook slug :before_save env)
             _create (run-hook slug :before_create _save)
             content (db/insert slug (dissoc (_create :values) :updated_at))
             merged (merge (_create :spec) content)
             _after (run-hook slug :after_create (merge _create {:content merged}))
             post (reduce #(post-update %2 %1) (_after :content) (vals (model :fields)))
             _final (run-hook slug :after_save (merge _after {:content post}))]
         (_final :content)))))

(defn rally
  "pull a set of content up through the model system with the given options."
  ([slug] (rally slug {}))
  ([slug opts]
     (let [model (models (keyword slug))
           order (or (opts :order) "asc")
           order-by (or (opts :order_by) "position")
           limit (str (or (opts :limit) 30))
           offset (str (or (opts :offset) 0))
           where (str (or (opts :where) "1=1"))]
       (doall (map #(from model % opts) (db/query "select * from %1 where %2 order by %3 %4 limit %5 offset %6" slug where order-by order limit offset))))))

(defn update
  "slug represents the model to be updated.
  id is the specific row to update.
  the spec contains all information about how to update this row,
  including nested specs which update across associations."
  ([slug id spec]
     (update slug id spec {}))
  ([slug id spec opts]
     (let [model (models (keyword slug))
           original (db/choose slug id)
           values (reduce #(update-values %2 (assoc spec :id id) %1) {} (vals (model :fields)))
           env {:model model :values values :spec spec :original original :op :update :opts opts}
           _save (run-hook slug :before_save env)
           _update (run-hook slug :before_update _save)
           success (db/update slug ["id = ?" (Integer. id)] (_update :values))
           content (db/choose slug id)
           merged (merge (_update :spec) content)
           _after (run-hook slug :after_update (merge _update {:content merged}))
           post (reduce #(post-update %2 %1) (_after :content) (vals (model :fields)))
           _final (run-hook slug :after_save (merge _after {:content post}))]
       (_final :content))))

(defn destroy
  "destroy the item of the given model with the given id."
  [slug id]
  (let [model (models (keyword slug))
        content (db/choose slug id)
        env {:model model :content content :slug slug}
        _before (run-hook slug :before_destroy env)
        pre (reduce #(pre-destroy %2 %1) (_before :content) (vals (model :fields)))
        deleted (db/delete slug "id = %1" id)
        _after (run-hook slug :after_destroy (merge _before {:content pre}))]
    (_after :content)))

(defn progenitors
  "if the model given by slug is nested,
  return a list of the item given by this id along with all of its ancestors."
  ([slug id] (progenitors slug id {}))
  ([slug id opts]
     (let [model (models (keyword slug))]
       (if (model :nested)
         (let [field-names (table-columns slug)
               base-where (db/clause "id = %1" [id])
               recur-where (db/clause "%1_tree.parent_id = %1.id" [slug])
               before (db/recursive-query slug field-names base-where recur-where)]
           (doall (map #(from model % opts) before)))
         [(from model (db/choose slug id) opts)]))))

(defn descendents
  "pull up all the descendents of the item given by id
  in the nested model given by slug."
  ([slug id] (descendents slug id {}))
  ([slug id opts]
     (let [model (models (keyword slug))]
       (if (model :nested)
         (let [field-names (table-columns slug)
               base-where (db/clause "id = %1" [id])
               recur-where (db/clause "%1_tree.id = %1.parent_id" [slug])
               before (db/recursive-query slug field-names base-where recur-where)]
           (doall (map #(from model % opts) before)))
         [(from model (db/choose slug id) opts)]))))

(defn reconstruct
  "mapping is between parent_ids and collections which share a parent_id.
  node is the item whose descendent tree is to be reconstructed."
  [mapping node]
  (assoc node :children (map #(reconstruct mapping %) (mapping (node :id)))))

(defn arrange-tree
  "given a set of nested items, arrange them into a tree
  based on id/parent_id relationships."
  [items]
  (let [by-parent (group-by #(% :parent_id) items)
        roots (by-parent nil)]
    (doall (map #(reconstruct by-parent %) roots))))

(defn load-model-hooks
  []
  (try
    (io/resource (@config/app :hooks-dir))
    (catch Exception e (println "no model hooks"))))

(gen-class
 :name caribou.model.Model
 :prefix model-
 :state state
 :init init
 :constructors {[String] []}
 :methods [[slug [] String]
           [create [clojure.lang.APersistentMap] clojure.lang.APersistentMap]])

(defn model-init [slug]
  [[] slug])

(defn model-create [this spec]
  (sql/with-connection @config/db
    (create (.state this) spec)))

(defn model-slug [this]
  (.state this))

(defn init
  []
  (config/init)
  (if (nil? (@config/app :use-database))
    (throw (Exception. "You must set :use-database in the app config")))

  (sql/with-connection @config/db
    (invoke-models)))
