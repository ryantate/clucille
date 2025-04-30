Clucille
========

Clucille is a Clojure interface to
[Lucene](https://lucene.apache.org/) forked from
[Clucy](https://github.com/weavejester/clucy). It aims to provide
backward API compatibility with Clucy while working with modern
(10.1.0) versions of Lucene and adding minor enhancements.

Installation
------------

To install Clucille, add the following dependency to your `deps.edn`
file:

```clojure
	 com.ryantate/clucille {:git/url "https://github.com/ryantate/clucille.git"
				:git/tag "v0.5.6"
				:git/sha "86c4bad"}
```

Clojure version
---------------

Clucille requires Clojure 1.12.0 or higher.

Usage
-----

To use Clucille, first require it:

```clojure
    (ns example				
      (:require
        [com.ryantate.clucille :as clucy]))
```

Then create an index. You can use `(memory-index)`, which stores the search
index in RAM, or `(disk-index "/path/to/a-folder")`, which stores the index in
a folder on disk.

```clojure
    (def index (clucy/memory-index))
```
Next, add Clojure maps to the index:

```clojure
    (clucy/add index
       {:name "Bob", :job "Builder"}
       {:name "Donald", :job "Computer Scientist"})
```

You can remove maps just as easily:

```clojure
    (clucy/delete index
       {:name "Bob", :job "Builder"})
```

Once maps have been added, the index can be searched:

```clojure
    user=> (clucy/search index "bob" 10)
    ({:name "Bob", :job "Builder"})

    user=> (clucy/search index "scientist" 10)
    ({:name "Donald", :job "Computer Scientist"})
```

You can search and remove all in one step. To remove all of the
scientists...

```clojure
    (clucy/search-and-delete index "job:scientist")
```

Query options
-------------

By default, clucille selects documents containing any terms (also
known as an "OR" search):

```clojure
    (ns example				
      (:require
        [com.ryantate.clucille :as clucy]))

    (def index (clucy/memory-index))

    (clucy/add index {:name "Nora" :job "Builder"})

    user=> (clucy/search index "ryan builder" 10)
    ({:name "Nora", :job "Builder"})
```

This can be changed programatically with the `:default-operator`
option to `search` or in the query with the "AND" keyword:

```clojure
    user=> (clucy/search index "ryan builder" 10 :default-operator :and)
    ()

    user=> (clucy/search index "ryan AND builder" 10)
    []
```

Showing highlights
------------------

Search engine users better understand results when shown the context
in which search terms appear. In Lucene this context is provided
through "higlighters."

You can activate highlighting with the `:highlight` option to
`search`, which should either be `true` or a map of options. This will
add a string of highlights to the metadata each result map under the
`:_fragments` key.

```clojure
    (ns example				
      (:require
        [com.ryantate.clucille :as clucy]))

    (def index (cluc/memory))

    (clucy/add index {:body "automotive subsidies will result in reciprocal tariffs, opponents of subsidies say"
                      :summary "car subsidies can have blowback"
                      :author "jhowland"})

    user=> (-> (clucy/search index "result subsidies" 10 {:highlight true})
               first
               meta
               :_fragments)
    "automotive <b>subsidies</b> will <b>result</b> in reciprocal tariffs, opponents of <b>subsidies</b> say car <b>subsidies</b> can have blowback jhowland"

    user=> (-> (clucy/search index
                             "result subsidies"
                             10
                             :highlight {:field :summary
                                         :pre "<i>"
                                         :post "</i>"
                                         :separator " ... "
                                         :max-fragments 2})
               first
               meta
               :_fragments)
    "car <i>subsidies</i> can have blowback"	  
```

Explanation of `:highlight` map options:

| option           | description               | default                     |
|------------------|---------------------------|-----------------------------|
| `:field`         | field to highlight        | `:_content `  
| `:pre`           | string before highlight   | "<b>" 
| `:post`          | string after highlight    | "</b>"
| `:separator`     | string to join highlights | "..."
| `:max-fragments` | max highlights            | 5


Optimizing writes and reads
---------------------------

For efficiency, the above functions can take a writer or reader in
place of the index. Specifically, `search` can take a reader, and the
other functions can take a writer.

Writers can be made with `index-writer` and readers with `index-reader`.

You'll want to make sure to close readers and writers that you
open. Clojure's `with-open` macro can help with this:

```clojure
    (ns example				
      (:require
        [com.ryantate.clucille :as clucy]))
      
    (def index (clucy/memory-index))
    
    (with-open [writer (clucy/index-writer index)]
      (clucy/add writer
        {:name "Larry", :job "Producer"}
        {:name "Bob", :job "Builder"}
        {:name "Donald", :job "Computer Scientist"})
      (clucy/delete writer
        {:name "Bob", :job "Builder"})
      (clucy/search-and-delete writer "job:scientist"))
      
      (with-open [reader (clucy/index-reader index)]
        {:bob (clucy/search reader "bob" 10)
	 :scientist (clucy/search reader "scientist" 10)})
```

**Note**: Writers affect how readers and other writers access an index, in
part because their changes are not seen by readers until they are
closed. A reader held open and used repeatedly can return different
results than when a reader is created for each use (for example when
there are writes between the reads). Consult the Lucene
documentation (particularly on `IndexWriter` and `IndexReader`) for
details.


Changing analyzer
-----------------

The default analyzer is
`org.apache.lucene.analysis.standard.StandardAnalyzer`. You may change
this by rebinding the dynamic var `*analyzer*` when indexing and searching.

```clojure
    (ns example				
      (:require
        [com.ryantate.clucille :as clucy])
      (:import
        (org.apache.lucene.analysis.en EnglishAnalyzer)))
      
    (def index (clucy/memory-index))

     ;;English stemming lets you find "cats" with "cat"
    (binding [clucy/*analyzer* (EnglishAnalyzer.)]
      (clucy/add index {:body "working caffeinated cats" :id 42})
      (clucy/search index "cat" 10))
```

Per-field analyzer
------------------

The `fields-analyzer` function provides a convenient way to create a
`PerFieldAnalyzerWrapper`, allowing custom analyzers for certain fields:

```clojure
    (ns example				
      (:require
        [com.ryantate.clucille :as clucy])
      (:import
        (org.apache.lucene.analysis.core SimpleAnalyzer)
        (org.apache.lucene.analysis.en EnglishAnalyzer)))

    (def index (clucy/memory-index))

    ;;tokenize "tags" field but don't stem or filter stopwords
    (binding [*analyzer* (clucy/fields-analyzer {:tags (SimpleAnalyzer.)}
     	                                         (EnglishAnalyzer.))]
      (clucy/add index {:body "working caffeinated"
                        :tags "cats coffee pastry"
                        :id 42})
      (clucy/search index "tags:cats" 10)) ;"tags:cat" would fail
```

The above applies a `SimpleAnalyzer` to the "tags" field and
`EnglishAnalyzer` to all other fields. You can omit the second,
fallback analyzer argument and `*analyzer*` will be used as the
fallback.


Field options
-------------

By default all fields in a map are stored and indexed, and indexed in
detail, with scoring and the frequency, position, and offsets of
terms. If you would like more fine-grained control over which fields
are stored and indexed, and how they are indexed, add one or more of
the fields below to the metadata for your map before using `add` to
incorporate it into an index.

```clojure
    (with-meta {:name "Larryd",
                :job "Writer",
                :phone "555-212-0202"
                :bio "When Larry and his friend Jerry began working on a pilot..."
                :catchphrase "pretty, pretty good"
                :summary "Larryd, Writer"}
      {:bio {:stored false
             :indexed org.apache.lucene.index.IndexOptions/DOCS_AND_FREQS}
       :catchphrase {:norms false}
       :summary {:indexed false}
       :phone {:analyzed false}
       :_content {:stored false
                  :include #{:name :job :bio :catchphrase}})
```

When the map above is saved to the index, the `:bio` field will be
available for searching but will not be part of map in the search
results since the `:stored` option is set to `false`. This makes sense
when you are indexing something large (like the full text of a long
article) and you don't want to pay the price of storing the entire
text in the index.

The `:bio` field is also indexed using custom `IndexOptions` in the
`:indexed` option, replacing the default
`DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS`.

The `:catchphrase` field will be available for searching and available in
the results but will not be factored in to the relevance scoring of
the document since the `:norms` options is set to `false`.

The `:summary` field will be stored for display with the search results
but will not be indexed for searching, since it is redundant with
other fields being indexed and thus the `:indexed` option is set to `false`.

The `:phone` field will not be tokenized since the `:analyzed` option is
set to `false`.

The `:_content` field is the default search field. For more on what
the default search field is, and the `:include` option, see [the next
section](#default-search-field).

**Note**: the `:analyzed` and `:norms` options do not matter when
`:indexed` is set to `false` since they are indexing options.

**Note also**: Lucene requires that field options be consistent within a
"segment," meaning all the records written from a particular writer
must have the same field options. In practical terms for clucille
users, this means you need to make sure field options are consistent
when you pass multiple maps to `add` or when you use the same writer
across multiple `add` operations by passing a writer instead of an
index (see "[Optimizing writes and reads](#optimizing-writes-and-reads)" section above).


Default search field
--------------------

The `search` function must select a default field to run queries
against. For example, while the search "name:bob" is clearly run
against the `:name` field, what about the search "bob"? This would be
run against the default field.

Normally, clucille will handle this behind the scenes on your
behalf. It will automatically store a field called `:_content` in the
index for each map when you call `add`. This field will contain all of
the map's values, excluding those for fields with `{:stored false}` in
the map's metadata. Then when you call `search`, it will automatically
set `:_content` as the default field.

There are two things you can do to alter this behavior.

  1. You can opt out of having `:_content` created on your behalf and
  selected as the default search field. To do this, rebind `*content*`
  to `false`. You must then, when calling `search`, specify the
  default search field, like so: `(clucy/search index "bob" 10 :default-field :body)`.
  
  2. You can continue allowing the `:_content` field but alter what
  other fields are included in it and/or how it is stored and
  indexed. You can use metadata to set options on `:_content` just
  like any other field. But `:_content` has an extra option:
  `:include`. The value of this option should be a set containing
  keys of fields that should go into `:_content`. This overrides the
  default selection criterion noted above (fields that are stored).

