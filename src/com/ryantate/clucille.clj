(ns com.ryantate.clucille
  (:require
   [clojure.string :as str])
  (:import
   (java.io File StringReader)
   (java.net URI URL)
   (java.nio.file Path)
   (java.util HashMap)
   (org.apache.lucene.analysis Analyzer TokenStream)
   (org.apache.lucene.analysis.miscellaneous PerFieldAnalyzerWrapper)
   (org.apache.lucene.analysis.standard StandardAnalyzer)
   (org.apache.lucene.document Document Field FieldType)
   (org.apache.lucene.index DirectoryReader IndexableFieldType IndexOptions
                            IndexReader IndexWriter IndexWriterConfig StoredFields
                            Term)
   (org.apache.lucene.queryparser.classic QueryParser)
   (org.apache.lucene.search BooleanClause BooleanClause$Occur BooleanQuery
                             BooleanQuery$Builder IndexSearcher Query ScoreDoc
                             TermQuery TopDocs TotalHits)
   (org.apache.lucene.search.highlight Highlighter QueryScorer SimpleHTMLFormatter)
   (org.apache.lucene.store ByteBuffersDirectory Directory NIOFSDirectory)
   (org.apache.lucene.util Version)))

(set! *warn-on-reflection* true)

(def ^{:dynamic true} *version* Version/LUCENE_CURRENT)
(def ^{:dynamic true} *analyzer* (StandardAnalyzer.))

(defn as-str
  ^String
  [x]
  (if (keyword? x)
    (name x)
    (str x)))

;; flag to indicate a default "_content" field should be maintained
(def ^{:dynamic true} *content* true)

(defn fields-analyzer
  "Make a PerFieldAnalyzerWrapper based on map of
  field-keyword->analyzer. Unspecified fields are analyzed with
  `default-analyzer` or *analyzer*."
  ([field-analyzers]
   (fields-analyzer field-analyzers *analyzer*))
  (^Analyzer [field-analyzers default-analyzer]
   (let [^HashMap jmap (reduce-kv (fn [^HashMap jmap fkey ^Analyzer fanalyzer]
                                    (.put jmap (name fkey) fanalyzer)
                                    jmap)
                                  (HashMap.)
                                  field-analyzers)]
     (if (or (not jmap) (.isEmpty jmap))
       default-analyzer
       (PerFieldAnalyzerWrapper. default-analyzer jmap)))))

(defn memory-index
  "Create a new index in RAM."
  []
  (ByteBuffersDirectory.))

(defn disk-index
  "Create a new index in a directory on disk.
  Takes string, java.nio.file.Path, java.io.File, java.net.URI, or java.net.URL."
  [dir-path]
  (NIOFSDirectory. (condp instance? dir-path
                     Path dir-path
                     String (Path/of ^String dir-path (into-array String []))
                     URL (Path/of ^URI (.toURI ^URL dir-path))
                     URI (Path/of ^URI dir-path)
                     File (.toPath ^File dir-path))))

(defn- index?
  [x]
  (instance? Directory x))

(defn index-writer
  "Create an IndexWriter."
  ^IndexWriter
  [index]
  (IndexWriter. index (IndexWriterConfig. *analyzer*)))

(defn index-reader
  "Create an IndexReader."
  ^IndexReader
  [index]
  (DirectoryReader/open ^Directory index))

(defn- add-field
  "Add a Field to a Document.
  Following options are allowed for meta-map:

  :stored - when false, then do not store the field value in the
  index.

  :indexed - when false, then do not index the field. when an instance
  of IndexOptions, set as index options rather than the
  default (IndexOptions/DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS)

  :analyzed - when :indexed is enabled use this option to
  disable/enable Analyzer for current field.

  :norms - when :indexed is enabled use this option to disable/enable
  the storing of norms."
  ([document k v]
   (add-field document k v {}))
  ([^Document document k v meta-map]
   (.add document
         (let [indexed (:indexed meta-map)
               indexed? (not (false? indexed))
               stored? (not (false? (:stored meta-map)))
               analyzed? (and indexed? (not (false? (:analyzed meta-map))))
               norms? (and indexed? (not (false? (:norms meta-map))))
               ft (doto (FieldType.)
                    (.setStored stored?)
                    (.setIndexOptions (cond
                                        (false? indexed)
                                        IndexOptions/NONE
                                        
                                        (instance? IndexOptions indexed)
                                        indexed
                                        
                                        :else
                                        IndexOptions/DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS))
                    (.setTokenized analyzed?)
                    (.setOmitNorms (not norms?)))]
           (Field. (as-str k) (as-str v) ^FieldType ft)))))

(defn- default-field-value
  [m default-meta]
  (let [mmeta (meta m) ;often nil
        include (:include default-meta) ;often nil
        includef (if include
                       (comp include key)
                       (fn [[k _]]
                         (not (false? (get-in mmeta [k :stored])))))]
    (str/join " "
              (into []
                    (comp
                     (filter includef)
                     (map val))
                    m))))

(defn- map->document
  "Create a Document from a map."
  [m]
  (let [document (Document.)
        mmeta (meta m)]
    (doseq [[k v] m]
      (add-field document k v (k mmeta)))
    (when *content*
      (let [default-meta (:_content mmeta)]
        (add-field document :_content (default-field-value m default-meta) default-meta)))
    document))

(defn- add-maps
  [^IndexWriter writer maps]
  (doseq [m maps]
    (.addDocument writer
                  (map->document m))))

(defn add
  "Add hash-maps to the search index."
  [index-or-writer & maps]
  (if (index? index-or-writer)
    (with-open [writer (index-writer index-or-writer)]
      (add-maps writer maps))
    (add-maps index-or-writer maps)))

(defn- delete-maps
  [^IndexWriter writer maps]
  (doseq [m maps]
    (let [^BooleanQuery$Builder qbuilder (BooleanQuery$Builder.)]
      (doseq [[k v] m]
        (.add qbuilder
              (BooleanClause.
               (TermQuery. (Term. (.toLowerCase (as-str k))
                                  (.toLowerCase (as-str v))))
               BooleanClause$Occur/MUST)))
      (.deleteDocuments writer ^Query/1 (into-array BooleanQuery [(.build qbuilder)])))))

(defn delete
  "Deletes hash-maps from the search index."
  [index-or-writer & maps]
  (if (index? index-or-writer)
    (with-open [writer (index-writer index-or-writer)]
      (delete-maps writer maps))
    (delete-maps index-or-writer maps)))

(defn- document->map
  "Turn a Document object into a map."
  ([document score]
   (document->map document score (constantly nil)))
  ([^Document document score highlighter]
   (let [m (into {}
                 (map (fn [^Field f]
                        [(keyword (.name f)) (.stringValue f)]))
                 (.getFields document))
         fragments (highlighter m) ; so that we can highlight :_content
         m (dissoc m :_content)]
     (with-meta
       m
       (-> (into {}
                 (map (fn [^Field f]
                        (let [^IndexableFieldType field-type (.fieldType f)]
                          [(keyword (.name f))
                           {:indexed (not= IndexOptions/NONE
                                           (.indexOptions field-type))
                            :stored (.stored field-type)
                            :tokenized (.tokenized field-type)}])))
                 (.getFields document))
           (assoc :_fragments fragments
                  :_score score)
           (dissoc :_content))))))

(defn- make-highlighter
  "Create a highlighter function which will take a map and return highlighted
  fragments."
  [^Query query ^IndexSearcher searcher config]
  (if config
    (let [scorer (QueryScorer. (.rewrite query searcher))
          config (merge {:field :_content
                         :max-fragments 5
                         :separator "..."
                         :pre "<b>"
                         :post "</b>"}
                        config)
          {:keys [field max-fragments separator pre post]} config
          highlighter (Highlighter. (SimpleHTMLFormatter. pre post) scorer)]
      (fn [m]
        (let [str (field m)
              token-stream (.tokenStream ^Analyzer *analyzer*
                                         (name field)
                                         (StringReader. str))]
          (.getBestFragments ^Highlighter highlighter
                             ^TokenStream token-stream
                             ^String str
                             (int max-fragments)
                             ^String separator))))
    (constantly nil)))

(defn search-with
  [^IndexReader reader query max-results {:keys [default-field
                                                 default-operator
                                                 highlight
                                                 page
                                                 results-per-page]
                                          :or {default-field :_content
                                               default-operator :or
                                               page 0
                                               results-per-page max-results}}]
  (let [^IndexSearcher searcher (IndexSearcher. reader)
        ^QueryParser parser (doto (QueryParser. (as-str default-field)
                                                *analyzer*)
                              (.setDefaultOperator (case default-operator 
                                                     :and QueryParser/AND_OPERATOR
                                                     :or  QueryParser/OR_OPERATOR)))
        query (.parse parser query)
        ^TopDocs hits (.search searcher query (int max-results))
        highlighter (make-highlighter query searcher highlight)
        start (* page results-per-page)
        end (min (+ start results-per-page) (.value ^TotalHits (.totalHits hits)))
        ^ScoreDoc/1 score-docs (.scoreDocs hits)
        ^StoredFields stored-fields (.storedFields searcher)]
    (with-meta (mapv (fn [i]
                       (let [^ScoreDoc hit (aget score-docs i)]
                         (document->map (.document stored-fields (.doc hit))
                                        (.score hit)
                                        highlighter)))
                     (range start end))
      {:_total-hits (.value ^TotalHits (.totalHits hits))
       :_max-score (let [^ScoreDoc/1 score-docs (.scoreDocs hits)]
                     (if (zero? (alength score-docs))
                       Float/NaN
                       (.score ^ScoreDoc (aget score-docs 0))))})))

(def no-default-field-ex (ex-info "No default search field specified"
                                  {:type ::no-defalt-field}))

(defn search
  "Search the supplied index with a query string."
  {:arglists '(  [index-or-reader query max-results
                  & {:keys [highlight default-field default-operator page results-per-page]
                     :or {page 0 results-per-page max-results}}])}
  [index-or-reader query max-results & {:keys [default-field] :as opts}]
  (if (every? false? [default-field *content*])
    (throw no-default-field-ex)
    (if (index? index-or-reader)
      (with-open [reader (index-reader index-or-reader)]
        (search-with reader query max-results opts))
      (search-with index-or-reader query max-results opts))))

(defn search-and-delete-with
  [^IndexWriter writer query default-field]
  (let [parser ^QueryParser (QueryParser. (as-str default-field) *analyzer*)
        query  (.parse parser query)]
    (.deleteDocuments writer ^Query/1 (into-array Query [query]))))

(defn search-and-delete
  "Search the supplied index with a query string and then delete all
  of the results."
  ([index-or-writer query]
   (if *content*
     (search-and-delete index-or-writer query :_content)
     (throw no-default-field-ex)))
  ([index-or-writer query default-field]
   (if (index? index-or-writer)
     (with-open [writer (index-writer index-or-writer)]
       (search-and-delete-with writer query default-field))
     (search-and-delete-with index-or-writer query default-field))))


;;;;VESTIGIAL:
;;below this point functions are no longer used but here for backward
;;compat (in case someone still calls them)

(defn- map-stored
  "Returns a hash-map containing all of the values in the map that
  will be stored in the search index."
  [m]
  (if-let [mmeta  (meta m)]
    (into {}
          (filter (fn [[k _]]
                    (not (false? (get-in mmeta [k :stored])))))
          m)
    m))

(defn- concat-values
  "Concatenate all the maps values being stored into a single string."
  [m]
  (str/join " "
            (if-let [mmeta (meta m)]
              (into []
                    (comp
                     (filter (fn [[k _]]
                               (not (false? (get-in mmeta [k :stored])))))
                     (map val))
                    m)
              (vals m))))

