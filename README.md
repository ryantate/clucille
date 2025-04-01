Clucille
=====

Clucille is a Clojure interface to [Lucene](https://lucene.apache.org/)
forked from [Clucy](https://github.com/weavejester/clucy).

Installation
------------

To install Clucille, add the following dependency to your `deps.edn`
file:

	 com.ryantate/clucille {:git/url "https://github.com/ryantate/clucille.git"
				:git/tag "v0.5.3"
				:git/sha "0ddd367"}

Clojure version
---------------

Clucille requires Clojure 1.12.0 or higher.

Usage
-----

To use Clucille, first require it:

    (ns example				
      (:require [com.ryantate.clucille :as clucy]))

Then create an index. You can use `(memory-index)`, which stores the search
index in RAM, or `(disk-index "/path/to/a-folder")`, which stores the index in
a folder on disk.

    (def index (clucy/memory-index))

Next, add Clojure maps to the index:

    (clucy/add index
       {:name "Bob", :job "Builder"}
       {:name "Donald", :job "Computer Scientist"})

You can remove maps just as easily:

    (clucy/delete index
       {:name "Bob", :job "Builder"})

Once maps have been added, the index can be searched:

    user=> (clucy/search index "bob" 10)
    ({:name "Bob", :job "Builder"})

    user=> (clucy/search index "scientist" 10)
    ({:name "Donald", :job "Computer Scientist"})

You can search and remove all in one step. To remove all of the
scientists...

    (clucy/search-and-delete index "job:scientist")

Field options
-------------

By default all fields in a map are stored and indexed. If you would
like more fine-grained control over which fields are stored and index,
add this to the meta-data for your map.

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
       :phone {:analyzer false})

When the map above is saved to the index, the `bio` field will be
available for searching but will not be part of map in the search
results since the `:stored` option is set to `false`. This makes sense
when you are indexing something large (like the full text of a long
article) and you don't want to pay the price of storing the entire
text in the index.

The `bio` field is also indexed using custom `IndexOptions` in the
`:indexed` option, replacing the default
`DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS`.

The `catchphrase` field will be available for searching and available in
the results but will not be factored in to the relevance scoring of
the document since the `:norms` options is set to `false`.

The `summary` field will be stored for display with the search results
but will not be indexed for searching, since it is redundant with
other fields being indexed and thus the `:indexed` option is set to `false`.

The `phone` field will not be tokenized since the `:analyzer` option is
set to `false`.

Note: the `:analyzer` and `:norms` options do not matter when
`:indexed` is set to `false` since they are indexing options.


Default search field
--------------------

A field called "\_content" that contains all of the map's values is
stored in the index for each map (excluding fields with `{:stored false}`
in the map's metadata). This provides a default field to run all
searches against. Anytime you call the search function without
providing a default search field "\_content" is used.

This behavior can be disabled by binding `*content*` to false, you must
then specify the default search field with every search invocation.
