(ns caribou.test.model
  (:use [caribou.debug]
        [caribou.model]
        [clojure.test])
  (:require [clojure.java.jdbc :as sql]
            [caribou.db :as db]
            [caribou.util :as util]
            [caribou.config :as config]))

(def test-db (config/read-database-config "config/test.clj"))

(deftest invoke-model-test
  (sql/with-connection test-db
    (let [model (db/query "select * from model where id = 1")
          invoked (invoke-model (first model))]
      (is (= "name" (-> invoked :fields :name :row :slug))))))

(deftest model-lifecycle-test
  (sql/with-connection test-db
    (invoke-models)
    (let [model (create :model
                        {:name "Yellow"
                         :description "yellowness yellow yellow"
                         :position 3
                         :fields [{:name "Gogon" :type "string"}
                                  {:name "Wibib" :type "boolean"}]})
          yellow (create :yellow {:gogon "obobo" :wibib true})]

      (is (<= 8 (count (model :fields))))
      (is (= (model :name) "Yellow"))
      (is ((models :yellow) :name "Yellow"))
      (is (db/table? :yellow))
      (is (yellow :wibib))
      (is (= 1 (count (db/query "select * from yellow"))))
      
      (destroy :model (model :id))

      (is (not (db/table? :yellow)))
      (is (not (models :yellow))))))

(deftest model-interaction-test
  (sql/with-connection (debug test-db)
    (invoke-models)
    (try
      (let [yellow-row (create :model
                               {:name "Yellow"
                                :description "yellowness yellow yellow"
                                :position 3
                                :fields [{:name "Gogon" :type "string"}
                                         {:name "Wibib" :type "boolean"}]})

            zap-row (create :model
                            {:name "Zap"
                             :description "zap zappity zapzap"
                             :position 3
                             :fields [{:name "Ibibib" :type "string"}
                                      {:name "Yobob" :type "slug" :link_slug "ibibib"}
                                      {:name "Yellows" :type "collection" :dependent true :target_id (yellow-row :id)}]})

            yellow (models :yellow)
            zap (models :zap)

            zzzap (create :zap {:ibibib "kkkkkkk"})
            yyy (create :yellow {:gogon "obobo" :wibib true :zap_id (zzzap :id)})
            yyyz (create :yellow {:gogon "igigi" :wibib false :zap_id (zzzap :id)})
            yy (create :yellow {:gogon "lalal" :wibib true :zap_id (zzzap :id)})]
        (update :yellow (yyy :id) {:gogon "binbin"})
        (update :zap (zzzap :id)
                {:ibibib "OOOOOO mmmmm   ZZZZZZZZZZ"
                 :yellows [{:id (yyyz :id) :gogon "IIbbiiIIIbbibib"}
                           {:gogon "nonononononon"}]})
        
        (let [zap-reload (db/choose :zap (zzzap :id))]
          (is (= ((db/choose :yellow (yyyz :id)) :gogon) "IIbbiiIIIbbibib"))
          (is (= ((db/choose :yellow (yyy :id)) :gogon) "binbin"))
          (is (= (zap-reload :yobob) "oooooo_mmmmm_zzzzzzzzzz"))
          (is (= "OOOOOO mmmmm   ZZZZZZZZZZ" ((from zap zap-reload {:include {}}) :ibibib)))
          (is (= 4 (count ((from zap zap-reload {:include {:yellows {}}}) :yellows))))

          (update :model (zap :id) {:fields [{:id (-> zap :fields :ibibib :row :id)
                                              :name "Okokok"}]})

          (update :model (yellow :id) {:name "Purple"
                                       :fields [{:id (-> yellow :fields :zap :row :id)
                                                 :name "Green"}]})

          (let [zappo (db/choose :zap (zzzap :id))
                purple (db/choose :purple (yyy :id))]
            (is (= (zappo :okokok) "OOOOOO mmmmm   ZZZZZZZZZZ"))
            (is (= (purple :green_id) (zappo :id))))

          (destroy :zap (zap-reload :id))
          (let [purples (db/query "select * from purple")]
            (is (empty? purples))))

        (destroy :model (zap :id))

        (is (empty? (-> @models :purple :fields :green_id)))

        (destroy :model (debug (-> @models :purple :id)))

        (is (and (not (db/table? :purple))
                 (not (db/table? :yellow))
                 (not (db/table? :zap)))))

      (catch Exception e (util/render-exception e))
      (finally      
       (if (db/table? :yellow) (destroy :model (-> @models :yellow :id)))
       (if (db/table? :purple) (destroy :model (-> @models :purple :id)))
       (if (db/table? :zap) (destroy :model (-> @models :zap :id)))))))

(deftest model-link-test
  (sql/with-connection test-db
    (invoke-models)
    (try
      (let [chartreuse-row
            (create :model
                    {:name "Chartreuse"
                     :description "chartreusey reuse chartreuse"
                     :position 3
                     :fields [{:name "Ondondon" :type "string"}
                              {:name "Kokok" :type "boolean"}]})

            fuchsia-row
            (create :model
                    {:name "Fuchsia"
                     :description "fuchfuchsia siasiasia fuchsia"
                     :position 3
                     :fields [{:name "Zozoz" :type "string"}
                              {:name "Chartreusii" :type "link" :dependent true :target_id (chartreuse-row :id)}]})

            chartreuse (models :chartreuse)
            fuchsia (models :fuchsia)
            charfuch (models :chartreusii_fuchsia)

            ccc (create :chartreuse {:ondondon "obobob"})
            cdc (create :chartreuse {:ondondon "ikkik"})
            cbc (create :chartreuse {:ondondon "zozoozozoz"})

            fff (create :fuchsia {:zozoz "glowing"})
            fgf (create :fuchsia {:zozoz "torpid"})
            fef (create :fuchsia {:zozoz "bluish"})

            ]
        (println (str charfuch)))
      (catch Exception e (util/render-exception e))      )))
      ;; (finally
      ;;  (if (db/table? :chartreuse) (destroy :model (-> @models :chartreuse :id)))
      ;;  (if (db/table? :fuchsia) (destroy :model (-> @models :fuchsia :id)))))))

(deftest nested-model-test
  (sql/with-connection test-db
    (invoke-models)
    (try
      (let [white (create :model {:name "White" :nested true :fields [{:name "Grey" :type "string"}]})
            aaa (create :white {:grey "obobob"})
            bbb (create :white {:grey "ininin" :parent_id (aaa :id)})
            ccc (create :white {:grey "kkukku" :parent_id (aaa :id)})
            ddd (create :white {:grey "zezeze" :parent_id (bbb :id)})
            eee (create :white {:grey "omomom" :parent_id (ddd :id)})
            fff (create :white {:grey "mnomno" :parent_id (ddd :id)})
            ggg (create :white {:grey "jjijji" :parent_id (ccc :id)})
            fff_path (progenitors :white (fff :id))
            bbb_children (descendents :white (bbb :id))]
        (is (= 4 (count fff_path)))
        (is (= 4 (count bbb_children))))
      (catch Exception e (util/render-exception e))
      (finally (if (db/table? :white) (destroy :model (-> @models :white :id)))))))

;; (deftest migration-test
;;   (sql/with-connection test-db
;;     (invoke-models)))