(ns caribou.test.model
  (:use [caribou.debug]
        [caribou.model]
        [clojure.test])
  (:require [clojure.java.jdbc :as sql]
            [clojure.java.io :as io]
            [caribou.db :as db]
            [caribou.util :as util]
            [caribou.config :as config]))

(def supported-dbs [:postgres :mysql])
(def db-configs (doall (map #(config/read-config (io/resource (str "config/test-" (name %) ".clj"))) supported-dbs)))

(defn test-init
  []
  (invoke-models))

(deftest invoke-model-test
  (doseq [config db-configs]
    (config/configure config)
    (sql/with-connection @config/db
      (let [model (util/query "select * from model where id = 1")
            invoked (invoke-model (first model))]
        (is (= "name" (-> invoked :fields :name :row :slug)))))))

(deftest model-lifecycle-test
  (doseq [config db-configs]
    (config/configure config)
    (sql/with-connection @config/db
      (test-init)
      (let [model (create :model
                          {:name "Yellow"
                           :description "yellowness yellow yellow"
                           :position 3
                           :fields [{:name "Gogon" :type "string"}
                                    {:name "Wibib" :type "boolean"}]})
            yellow (create :yellow {:gogon "obobo" :wibib true})]

        (is (<= 8 (count (-> @models :yellow :fields))))
        (is (= (model :name) "Yellow"))
        (is ((models :yellow) :name "Yellow"))
        (is (db/table? :yellow))
        (is (yellow :wibib))
        (is (= 1 (count (util/query "select * from yellow"))))
        
        (destroy :model (model :id))

        (is (not (db/table? :yellow)))
        (is (not (models :yellow)))))))

(deftest model-interaction-test
  (doseq [config db-configs]
    (config/configure config)
    (sql/with-connection @config/db
      (test-init)
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
            (let [purples (util/query "select * from purple")]
              (is (empty? purples))))

          (destroy :model (zap :id))

          (is (empty? (-> @models :purple :fields :green_id)))

          (destroy :model (-> @models :purple :id))

          (is (and (not (db/table? :purple))
                   (not (db/table? :yellow))
                   (not (db/table? :zap)))))

        (catch Exception e (util/render-exception e))
        (finally      
          (if (db/table? :yellow) (destroy :model (-> @models :yellow :id)))
          (if (db/table? :purple) (destroy :model (-> @models :purple :id)))
          (if (db/table? :zap) (destroy :model (-> @models :zap :id))))))))

(deftest model-link-test
  (doseq [config db-configs]
    (config/configure config)
    (sql/with-connection @config/db
      (test-init)
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

              cf-link (-> chartreuse :fields :fuchsia)
              fc-link (-> fuchsia :fields :chartreusii)

              ccc (create :chartreuse {:ondondon "obobob" :kokok true})
              cdc (create :chartreuse {:ondondon "ikkik" :kokok false})
              cbc (create :chartreuse {:ondondon "zozoozozoz" :kokok false})

              fff (create :fuchsia {:zozoz "glowing"})
              fgf (create :fuchsia {:zozoz "torpid"})
              fef (create :fuchsia {:zozoz "bluish"})

              ]
          ;; make some links
          (link cf-link ccc fff)
          (link cf-link cdc fff)
          (link fc-link fgf cbc)

          ;; create links through update rather than directly
          (update :fuchsia (fef :id) {:chartreusii [cbc ccc {:ondondon "ikikik" :kokok false :fuchsia [{:zozoz "granular"}]}]})

          (is (= 2 (count (retrieve-links cf-link ccc))))
          (let [fff-X (from (models :fuchsia) fff {:include {:chartreusii {}}})
                cec (pick :chartreuse {:where {:ondondon "ikikik"} :include {:fuchsia {}}})]
            (is (= 2 (count (fff-X :chartreusii))))
            (is (some #(= % "granular") (map :zozoz (:fuchsia cec))))

            (update :model (:id chartreuse)
                    {:fields [{:id (-> cf-link :row :id) :name "Nightpurple" :slug "nightpurple"}]})

            (let [chartreuse (models :chartreuse)
                  coc (gather
                       :chartreuse
                       {:where {:nightpurple {:zozoz "granular"}}
                        :order {:nightpurple {:id :desc}}
                        :include {:nightpurple {}}
                        :limit 5
                        :offset 0})]
              (is (= 1 (count coc)))
              (is (= 2 (count (:nightpurple (first coc)))))
              (is (present? (models :chartreusii_nightpurple)))
              (let [falses (gather :chartreuse {:where {:kokok false}})]
                (is (= 3 (count falses)))))))

        (catch Exception e (util/render-exception e))

        (finally
          (if (db/table? :chartreuse) (destroy :model (-> @models :chartreuse :id)))
          (if (db/table? :fuchsia) (destroy :model (-> @models :fuchsia :id))))))))

(deftest localized-model-test
  (doseq [config db-configs]
    (config/configure config)
    (sql/with-connection @config/db
      (test-init)
      (try
        (let [place (create :locale {:language "Ibeo" :region "Glass" :code "ib_or"})
              everywhere (create
                          :model
                          {:name "Everywhere" :localized true
                           :fields [{:name "Up" :type "string"}
                                    {:name "Grass" :type "text"}
                                    {:name "Through" :type "boolean"}
                                    {:name "Form Structure" :type "asset"}
                                    {:name "Colocate" :type "address"}
                                    ;; {:name "Whentime" :type "timestamp"}
                                    {:name "Under" :type "decimal"}]})
              other (create :locale {:language "Gornon" :region "Ipipip" :code "go_xb"})
              nowhere (create
                       :model
                       {:name "Nowhere" :localized true
                        :fields [{:name "Down" :type "string"}
                                 {:name "Everywhere" :type "link" :dependent true :target_id (everywhere :id)}]})
              a (create :everywhere {:up "Hey" :grass "On" :through true :under 10.1})
              b (create :everywhere {:up "What" :grass "Bead" :through true :under 33.333})
              c (create :everywhere {:up "Is" :grass "Growth" :through false :under 22222})
              xxx (create :nowhere {:down "Ylel" :everywhere [{:id (:id a)} {:id (:id c)}]})
              outer (create :locale {:language "Obooe" :region "Xorxox" :code "xo_ub"})
              other-other (update :locale (:id other) {:code "bx_pa"})

              xxx-other (update :nowhere (:id xxx) {:down "IiiiiiIIIIIII"} {:locale "xo_ub"})
              xxx-other (update :nowhere (:id xxx) {:down "Prortrobr"
                                                    :everywhere [{:id (:id b)} {:id (:id c)}]} {:locale "bx_pa"})
              xxx-other (update :nowhere (:id xxx) {:down "Grungruublor"
                                                    :everywhere [{:id (:id a)} {:id (:id b)}]} {:locale "ib_or"})

              xo-ub-eees
              (gather
               :everywhere
               {:include {:nowhere {}}
                :where {:nowhere {:down "IiiiiiIIIIIII"}}
                :order {:nowhere {:down :desc}}
                :limit 3
                :locale "xo_ub"})
              
              bx-pa-eees
              (gather
               :everywhere
               {:include {:nowhere {}}
                :where {:nowhere {:down "Prortrobr"}}
                :order {:nowhere {:down :desc}}
                :limit 3
                :locale "bx_pa"})

              ib-or-eees
              (gather
               :everywhere
               {:include {:nowhere {}}
                :where {:nowhere {:down "Grungruublor"}}
                :order {:nowhere {:down :desc}}
                :limit 1
                :locale "ib_or"})

              ordered-everywhere
              (gather
               :everywhere
               {:order {:grass :desc}
                :limit 2
                :offset 1})

              joins
              (gather
               :everywhere_nowhere
               {:include {:everywhere {}}
                :where {:everywhere {:through true}}
                :order {:everywhere {:under :asc}}})

              nowhat
              (pick
               :nowhere
               {:where {:id (:id xxx)}
                :locale "bx_pa"})
              
              everywhat
              (pick
               :everywhere
               {:where {:id (:id c)}
                :locale "xo_ub"})
              ]

          (is (= 0 (count xo-ub-eees)))
          (is (= 2 (count bx-pa-eees)))
          (is (= 1 (count ib-or-eees)))
          (is (= '("Growth" "Bead") (map :grass ordered-everywhere)))
          (is (= 1 (count joins)))
          (is (= "Hey" (-> joins first :everywhere :up)))
          (is (= "Prortrobr" (:down nowhat)))
          (is (= "Is" (:up everywhat)))

          (println (form-uberquery
                    (@models :everywhere)
                    {:include {:nowhere {}}
                     :where {:nowhere {:down "Ylel"}}
                     :order {:nowhere {:down :desc}}
                     :limit 3
                     :locale "xo_ub"}))

          )
        (catch Exception e (util/render-exception e))
;; ))))
        (finally
         (if (db/table? :everywhere)
           (do
             (db/do-sql "delete from locale")
             (destroy :model (-> @models :nowhere :id))
             (destroy :model (-> @models :everywhere :id)))))))))

(deftest nested-model-test
  (doseq [config db-configs]
    (config/configure config)
    (sql/with-connection @config/db
      (test-init)
      (try
        (let [white (create :model {:name "White" :nested true :fields [{:name "Grey" :type "string"}]})
              aaa (create :white {:grey "obobob"})
              bbb (create :white {:grey "ininin" :parent_id (aaa :id)})
              ccc (create :white {:grey "kkukku" :parent_id (aaa :id)})
              ddd (create :white {:grey "zezeze" :parent_id (bbb :id)})
              eee (create :white {:grey "omomom" :parent_id (ddd :id)})
              fff (create :white {:grey "mnomno" :parent_id (ddd :id)})
              ggg (create :white {:grey "jjijji" :parent_id (ccc :id)})
              tree (arrange-tree [aaa bbb ccc ddd eee fff ggg])]
          ;; fff_path (progenitors :white (fff :id))
          ;; bbb_children (descendents :white (bbb :id))]
          ;; (is (= 4 (count fff_path)))
          ;; (is (= 4 (count bbb_children))))
          (doseq [branch tree]
            (println (doall (str tree))))
          (is (= 1 (count tree))))
        (catch Exception e (util/render-exception e))
        (finally (if (db/table? :white) (destroy :model (-> @models :white :id))))))))

;; (deftest migration-test
;;   (sql/with-connection @config/db
;;     (init)))