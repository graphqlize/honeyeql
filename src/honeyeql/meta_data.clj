(ns honeyeql.meta-data
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [inflections.core :as inf]))

(defn- meta-data-result [db-spec result-set]
  (rs/datafiable-result-set result-set db-spec {:builder-fn rs/as-unqualified-lower-maps}))

(defn- coarce-boolean [bool-str]
  (case bool-str
    "YES" true
    "NO" false))

(defn- entity-ident [db-config {:keys [table_schem table_name]}]
  (if (= (get-in db-config [:schema :default]) table_schem)
    (keyword (inf/hyphenate table_name))
    (keyword (inf/hyphenate table_schem) (inf/hyphenate table_name))))

(defn- attribute-ident [db-config {:keys [table_schem table_name column_name]}]
  (if (= (get-in db-config [:schema :default]) table_schem)
    (keyword (inf/hyphenate table_name) (inf/hyphenate column_name))
    (keyword (str (inf/hyphenate table_schem) "." (inf/hyphenate table_name)) (inf/hyphenate column_name))))

(defn- column-ident [db-config {:keys [table_schem table_name column_name]}]
  (if (= (get-in db-config [:schema :default]) table_schem)
    (keyword (str table_name "." column_name))
    (keyword (str table_schem "." table_name "." column_name))))

(defn- to-entity-meta-data [db-config {:keys [remarks table_type table_schem table_name]
                                       :as   table-meta-data}]
  (let [ident (entity-ident db-config table-meta-data)]
    [ident
     {:db/doc         remarks
      :db/ident       ident
      :db.entity/type (case table_type
                        "TABLE" :table
                        "VIEW" :view)
      :db/schema      table_schem
      :db/relation    table_name}]))

(defn- entities-meta-data [db-spec jdbc-meta-data db-config]
  (->> (into-array String ["TABLE" "VIEW"])
       (.getTables jdbc-meta-data nil "%" nil)
       (meta-data-result db-spec)
       (map #(to-entity-meta-data db-config %))
       (into {})))

(defn- filter-columns [db-config columns]
  (remove #(contains? (get-in db-config [:schema :ignore]) (:table_schem %)) columns))

(defn- add-attribute-meta-data [db-config heql-meta-data
                                {:keys [table_schem table_name column_name
                                        remarks is_nullable is_autoincrement]
                                 :as   column-meta-data}]
  (let [attr-ident            (attribute-ident db-config column-meta-data)
        entity-ident          (entity-ident db-config column-meta-data)
        is-nullable           (coarce-boolean is_nullable)
        entity-attr-qualifier (if is-nullable
                                :attribute/optional
                                :attribute/required)]
    (update-in
     (assoc-in heql-meta-data [:attributes attr-ident]
               {:db/ident                     attr-ident
                :db/doc                       remarks
                :db.column/name               column_name
                :db.column/schema             table_schem
                :db.column/relation           table_name
                :db.column/auto-incrementable (coarce-boolean is_autoincrement)
                :db.column/nullable           is-nullable
                :db.column/ident              (column-ident db-config column-meta-data)})
     [:entities entity-ident entity-attr-qualifier]
     conj attr-ident)))

(defn- add-attributes-meta-data [db-spec jdbc-meta-data db-config heql-meta-data]
  (->> (.getColumns jdbc-meta-data nil "%" "%" nil)
       (meta-data-result db-spec)
       (filter-columns db-config)
       (reduce (partial add-attribute-meta-data db-config) heql-meta-data)))

(defmulti get-db-config identity)

(defn fetch [db-spec]
  (with-open [conn (jdbc/get-connection db-spec)]
    (let [jdbc-meta-data     (.getMetaData conn)
          db-product-name    (.getDatabaseProductName jdbc-meta-data)
          db-config          (get-db-config db-product-name)
          entities-meta-data (entities-meta-data db-spec jdbc-meta-data db-config)
          heql-meta-data     {:entities entities-meta-data}]
      (add-attributes-meta-data db-spec jdbc-meta-data db-config heql-meta-data))))