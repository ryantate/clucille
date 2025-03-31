(ns clucy.core-test
  (:require
   [clojure.set :as set]
   [clojure.test :refer :all]
   [clucy.core :as clucy]))

(def people [{:name "Miles" :age 36}
             {:name "Emily" :age 0.3}
             {:name "Joanna" :age 34}
             {:name "Melinda" :age 34}
             {:name "Mary" :age 48}
             {:name "Mary Lou" :age 39}])

(deftest core
  (testing "clucy/memory-index fn"
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

  (testing "search-and-delete fn"
    (let [index (clucy/memory-index)]
      (doseq [person people] (clucy/add index person))
      (clucy/search-and-delete index "name:mary")
      (is (== 0 (count (clucy/search index "name:mary" 10))))))

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
