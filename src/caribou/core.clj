(ns caribou.core
  (:require [caribou.config :as config]
            [caribou.db :as db]
            [caribou.model :as model]
            [caribou.logger :as log]))

(def gather model/gather)
(def pick model/pick)
(def create model/create)
(def update model/update)
(def destroy model/destroy)

(defn init
  [init-config]
  (let [full-config (config/process-config init-config)]
    (db/with-db full-config
      (model/invoke-models)
      (log/init (config/draw :logging :loggers))
      (config/draw))))

(defmacro with-caribou
  [config & body]
  `(model/with-models ~config
     ~@body))

(defmacro run
  [& body]
  `(with-caribou (config/draw)
     ~@body))