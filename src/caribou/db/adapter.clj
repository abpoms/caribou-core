(ns caribou.db.adapter
  (:use caribou.db.adapter.postgres
        caribou.db.adapter.mysql
        caribou.db.adapter.h2)
  (:import [caribou.db.adapter.postgres PostgresAdapter]
           [caribou.db.adapter.mysql MysqlAdapter]
           [caribou.db.adapter.h2 H2Adapter]))

(defn adapter-for
  "Find the right adapter for the given database configuration."
  [config]
  (condp = (config :subprotocol)
    "postgresql" (PostgresAdapter. config)
    "mysql" (MysqlAdapter. config)
    "h2" (H2Adapter. config)))
