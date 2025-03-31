(ns com.ryantate.clucille-test
  (:require
   [clojure.set :as set]
   [clojure.test :refer :all]
   [com.ryantate.clucille :as clucille]))

(def people [{:name "Miles" :age 36}
             {:name "Emily" :age 0.3}
             {:name "Joanna" :age 34}
             {:name "Melinda" :age 34}
             {:name "Mary" :age 48}
             {:name "Mary Lou" :age 39}])

(deftest core
  (testing "clucille/memory-index fn"
    (let [index (clucille/memory-index)]
      (is (not (nil? index)))))

  (testing "disk-index fn"
    (let [index (clucille/disk-index "/tmp/test-index")]
      (is (not (nil? index)))))

  (testing "add fn"
    (let [index (clucille/memory-index)]
      (doseq [person people] (clucille/add index person))
      (is (== 1 (count (clucille/search index "name:miles" 10))))))

  (testing "delete fn"
    (let [index (clucille/memory-index)]
      (doseq [person people] (clucille/add index person))
      (clucille/delete index (first people))
      (is (== 0 (count (clucille/search index "name:miles" 10))))))

  (testing "search fn"
    (let [index (clucille/memory-index)]
      (doseq [person people] (clucille/add index person))
      (is (== 1 (count (clucille/search index "name:miles" 10))))
      (is (== 1 (count (clucille/search index "name:miles age:100" 10))))
      (is (== 0 (count (clucille/search index "name:miles AND age:100" 10))))
      (is (== 0 (count (clucille/search index "name:miles age:100" 10 :default-operator :and))))))

  (testing "search-and-delete fn"
    (let [index (clucille/memory-index)]
      (doseq [person people] (clucille/add index person))
      (clucille/search-and-delete index "name:mary")
      (is (== 0 (count (clucille/search index "name:mary" 10))))))

  (testing "search fn with highlighting"
    (let [index (clucille/memory-index)
          config {:field :name}]
      (doseq [person people] (clucille/add index person))
      (is (= (map #(-> % meta :_fragments)
                  (clucille/search index "name:mary" 10 :highlight config))
             ["<b>Mary</b>" "<b>Mary</b> Lou"]))))

  (testing "search fn returns scores in metadata"
    (let [index (clucille/memory-index)
          _ (doseq [person people] (clucille/add index person))
          results (clucille/search index "name:mary" 10)]
      (is (true? (every? pos? (map (comp :_score meta) results))))
      (is (= 2 (:_total-hits (meta results))))
      (is (pos? (:_max-score (meta results))))
      (is (= (count people) (:_total-hits (meta (clucille/search index "*:*" 2)))))))

  (testing "pagination"
    (let [index (clucille/memory-index)]
      (doseq [person people] (clucille/add index person))
      (is (== 3 (count (clucille/search index "m*" 10 :page 0 :results-per-page 3))))
      (is (== 1 (count (clucille/search index "m*" 10 :page 1 :results-per-page 3))))
      (is (empty? (set/intersection
                   (set (clucille/search index "m*" 10 :page 0 :results-per-page 3))
                   (set (clucille/search index "m*" 10 :page 1 :results-per-page 3))))))))
