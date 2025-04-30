(ns com.ryantate.clucille-test
  (:require
   [clojure.set :as set]
   [clojure.test :refer :all]
   [com.ryantate.clucille :as clucy])
  (:import
   (org.apache.lucene.analysis.en EnglishAnalyzer)))

(def people [{:name "Miles" :age 36}
             {:name "Emily" :age 0.3}
             {:name "Joanna" :age 34}
             {:name "Melinda" :age 34}
             {:name "Mary" :age 48}
             {:name "Mary Lou" :age 39}])

(deftest core
  (testing "memory-index fn"
    (let [index (clucy/memory-index)]
      (is (not (nil? index)))))

  (testing "disk-index fn"
    (let [index (clucy/disk-index "/tmp/test-index")]
      (is (not (nil? index)))))

  (testing "add fn"
    (let [index (clucy/memory-index)]
      (doseq [person people] (clucy/add index person))
      (is (== 1 (count (clucy/search index "name:miles" 10))))))

  (testing "delete fn"
    (let [index (clucy/memory-index)]
      (doseq [person people] (clucy/add index person))
      (clucy/delete index (first people))
      (is (== 0 (count (clucy/search index "name:miles" 10))))))

  (testing "search fn"
    (let [index (clucy/memory-index)]
      (doseq [person people] (clucy/add index person))
      (is (== 1 (count (clucy/search index "name:miles" 10))))
      (is (== 1 (count (clucy/search index "name:miles age:100" 10))))
      (is (== 0 (count (clucy/search index "name:miles AND age:100" 10))))
      (is (== 0 (count (clucy/search index "name:miles age:100" 10 :default-operator :and))))))

  (testing "fields metadata"
    (let [index-no-meta (clucy/memory-index)
          index-age-meta (clucy/memory-index)
          index-default-meta (clucy/memory-index)]
      (apply clucy/add index-no-meta people)
      (is (= 2 (count (clucy/search index-no-meta "34" 10))))
      (apply clucy/add
             index-age-meta
             (map (fn [m] (with-meta m {:age {:indexed false}})) people))
      (is (= 0 (count (clucy/search index-age-meta "age:34" 10))))
      (apply clucy/add
             index-default-meta
             (map (fn [m] (with-meta m {:_content {:include #{:name}}})) people))
      (is (= 0 (count (clucy/search index-default-meta "34" 10)))))
    (let [person {:name "Larryd",
                  :job "Writer",
                  :phone "555.212.0202"
                  :bio "When Larry and his friend Jerry began working on a pilot..."
                  :catchphrase "pretty, pretty good"
                  :summary "Larryd, Writer"}
          field-map {:phone {:analyzed false}
                     :bio {:stored false}
                     :catchphrase {:norms false}
                     :summary {:indexed false}
                     :_content {:stored false
                                :include #{:name :job :bio :catchphrase}}}
          index (clucy/memory-index)]
      (clucy/add index (with-meta person field-map))
      (let [bio-hits (clucy/search index "bio:working" 10)
            bio-hit (first bio-hits)]
        (is (= 1 (count bio-hits)))
        (is (set/subset? #{:name :job :phone :catchphrase} (set (keys bio-hit))))
        (is (every? (complement bio-hit) #{:bio :_content})))
      (is (= 0 (count (clucy/search index "summary:larryd" 10))))
      (is (= 1 (count (clucy/search index "working" 10))))
      (is (= 0 (count (clucy/search index "phone:555" 10))))
      (is (= 1 (count (clucy/search index "phone:555.212.0202" 10))))))
  
  (testing "search-and-delete fn"
    (let [index (clucy/memory-index)]
      (doseq [person people] (clucy/add index person))
      (clucy/search-and-delete index "name:mary")
      (is (== 0 (count (clucy/search index "name:mary" 10))))))

    (testing "passing writer and reader"
    (let [index (clucy/memory-index)]
      (with-open [writer (clucy/index-writer index)]
        (doseq [person people] (clucy/add writer person))
        (clucy/search-and-delete writer "name:miles"))
      (with-open [reader (clucy/index-reader index)]
        (is (== 0 (count (clucy/search reader "name:miles" 10))))
        (is (== 1 (count (clucy/search reader "name:melinda" 10)))))))

  (testing "search fn with highlighting"
    (let [index (clucy/memory-index)
          config {:field :name}]
      (doseq [person people] (clucy/add index person))
      (is (= (map #(-> % meta :_fragments)
                  (clucy/search index "name:mary" 10 :highlight config))
             ["<b>Mary</b>" "<b>Mary</b> Lou"]))))

  (testing "search fn returns scores in metadata"
    (let [index (clucy/memory-index)
          _ (doseq [person people] (clucy/add index person))
          results (clucy/search index "name:mary" 10)]
      (is (true? (every? pos? (map (comp :_score meta) results))))
      (is (= 2 (:_total-hits (meta results))))
      (is (pos? (:_max-score (meta results))))
      (is (= (count people) (:_total-hits (meta (clucy/search index "*:*" 2)))))))

  (testing "pagination"
    (let [index (clucy/memory-index)]
      (doseq [person people] (clucy/add index person))
      (is (== 3 (count (clucy/search index "m*" 10 :page 0 :results-per-page 3))))
      (is (== 1 (count (clucy/search index "m*" 10 :page 1 :results-per-page 3))))
      (is (empty? (set/intersection
                   (set (clucy/search index "m*" 10 :page 0 :results-per-page 3))
                   (set (clucy/search index "m*" 10 :page 1 :results-per-page 3))))))))

(deftest deletes-maps-with-stemmed-terms
  (let [index (clucy/memory-index)
        m {:title "Cats on a hot tin roof"}]
    (binding [clucy/*analyzer* (EnglishAnalyzer.)]
      (clucy/add index m)
      (is (= 1 (count (clucy/search index "cat on a hot" 10))))
      (clucy/delete index m)
      (is (= 0 (count (clucy/search index "cat on a hot" 10)))))))
