(ns com.ryantate.clucille
  (:import
   (java.io StringReader File)
   (org.apache.lucene.analysis Analyzer TokenStream)
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

(defn memory-index
  "Create a new index in RAM."
  []
  (ByteBuffersDirectory.))

(defn disk-index
  "Create a new index in a directory on disk."
  [^String dir-path]
  (NIOFSDirectory. (.toPath (File. dir-path))))

(defn- index-writer
  "Create an IndexWriter."
  ^IndexWriter
  [index]
  (IndexWriter. index
                (IndexWriterConfig. *analyzer*)))

(defn- index-reader
  "Create an IndexReader."
  ^IndexReader
  [index]
  (DirectoryReader/open ^Directory index))

(defn- add-field
  "Add a Field to a Document.
  Following options are allowed for meta-map:
  :stored - when false, then do not store the field value in the index.
  :indexed - when false, then do not index the field.
  :analyzed - when :indexed is enabled use this option to disable/enable Analyzer for current field.
  :norms - when :indexed is enabled use this option to disable/enable the storing of norms."
  ([document key value]
   (add-field document key value {}))
  ([^Document document key value meta-map]
   (.add document
         (let [stored? (not (false? (:stored meta-map)))
               indexed? (not (false? (:indexed meta-map)))
               analyzed? (and indexed? (not (false? (:analyzed meta-map))))
               norms? (and indexed? (not (false? (:norms meta-map))))
               skey (as-str key)
               sval (as-str value)
               ft (doto (FieldType.)
                    (.setStored stored?)
                    (.setIndexOptions (if indexed?
                                        (if (instance? IndexOptions (:indexed meta-map))
                                          (:indexed meta-map)
                                          IndexOptions/DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS)
                                        IndexOptions/NONE))
                    (.setTokenized analyzed?)
                    (.setOmitNorms (not norms?)))]
           (Field. skey sval ^FieldType ft)))))

(defn- map-stored
  "Returns a hash-map containing all of the values in the map that
  will be stored in the search index."
  [map-in]
  (if (meta map-in)
    (into {}
          (filter (fn [[k _]]
                    (not (false? (get-in (meta map-in) [k :stored])))))
          map-in)
    map-in))

(defn- concat-values
  "Concatenate all the maps values being stored into a single string."
  [map-in]
  (apply str (interpose " " (vals (map-stored map-in)))))

(defn- map->document
  "Create a Document from a map."
  [map]
  (let [document (Document.)]
    (doseq [[key value] map]
      (add-field document key value (key (meta map))))
    (when *content*
      (add-field document :_content (concat-values map)))
    document))

(defn add
  "Add hash-maps to the search index."
  [index & maps]
  (with-open [writer (index-writer index)]
    (doseq [m maps]
      (.addDocument writer
                    (map->document m)))))

(defn delete
  "Deletes hash-maps from the search index."
  [index & maps]
  (with-open [writer (index-writer index)]
    (doseq [m maps]
      (let [^BooleanQuery$Builder qbuilder (BooleanQuery$Builder.)]
        (doseq [[key value] m]
          (.add qbuilder
                (BooleanClause.
                 (TermQuery. (Term. (.toLowerCase (as-str key))
                                    (.toLowerCase (as-str value))))
                 BooleanClause$Occur/MUST)))
        (.deleteDocuments writer ^Query/1 (into-array BooleanQuery [(.build qbuilder)]))))))

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
                           {:indexed (not (= IndexOptions/NONE (.indexOptions field-type)))
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

(defn search
  "Search the supplied index with a query string."
  [index query max-results
   & {:keys [highlight default-field default-operator page results-per-page]
      :or {page 0 results-per-page max-results}}]
  (if (every? false? [default-field *content*])
    (throw (Exception. "No default search field specified"))
    (with-open [reader (index-reader index)]
      (let [default-field (or default-field :_content)
            ^IndexSearcher searcher (IndexSearcher. reader)
            ^QueryParser parser (doto (QueryParser. (as-str default-field)
                                                    *analyzer*)
                                  (.setDefaultOperator (case (or default-operator :or)
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
                           (.score ^ScoreDoc (aget score-docs 0))))})))))

(defn search-and-delete
  "Search the supplied index with a query string and then delete all
  of the results."
  ([index query]
   (if *content*
     (search-and-delete index query :_content)
     (throw (Exception. "No default search field specified"))))
  ([index query default-field]
   (with-open [writer (index-writer index)]
     (let [parser ^QueryParser (QueryParser. (as-str default-field) *analyzer*)
           query  (.parse parser query)]
       (.deleteDocuments writer ^Query/1 (into-array Query [query]))))))
