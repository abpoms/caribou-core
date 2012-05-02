(defproject antler/caribou-core "0.5.2"
  :description "caribou: type structure interaction api"
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [org.clojure/java.jdbc "0.0.6"]
                 [postgresql/postgresql "8.4-702.jdbc4"]
                 [clj-time "0.3.6"]
                 [clj-yaml "0.3.1"]
                 [geocoder-clj "0.0.3"]
                 [org.freemarker/freemarker "2.3.18"]
                 [org.clojure/tools.logging "0.2.3"]]
                 ;; [antler/clojure-solr "0.3.0-SNAPSHOT"]
  :jvm-opts ["-agentlib:jdwp=transport=dt_socket,server=y,suspend=n"]
  :aot [caribou.model]
  :repositories {"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"
                 "snapshots" {:url "http://battlecat:8080/nexus/content/repositories/snapshots" 
                              :username "deployment" :password "deployment"}
                 "releases"  {:url "http://battlecat:8080/nexus/content/repositories/releases" 
                              :username "deployment" :password "deployment"}})
