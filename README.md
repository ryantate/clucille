Clucille
=====

Clucille is a Clojure interface to [Lucene](https://lucene.apache.org/)
forked from [Clucy](https://github.com/weavejester/clucy).

Installation
------------

To install Clucille, add the following dependency to your `project.clj`
file:
    com.ryantate/clucille {:git/url "https://github.com/ryantate/clucille.git"
                           :git/tag "v0.5.0"
                           :git/sha "0a87f1c"}

Usage
-----

To use Clucille, first require it:

    (ns example
      (:require [com.ryantate.clucille :as clucille]))

Then create an index. You can use `(memory-index)`, which stores the search
index in RAM, or `(disk-index "/path/to/a-folder")`, which stores the index in
a folder on disk.

    (def index (clucille/memory-index))

Next, add Clojure maps to the index:

    (clucille/add index
       {:name "Bob", :job "Builder"}
       {:name "Donald", :job "Computer Scientist"})

You can remove maps just as easily:

    (clucille/delete index
       {:name "Bob", :job "Builder"})

Once maps have been added, the index can be searched:

    user=> (clucille/search index "bob" 10)
    ({:name "Bob", :job "Builder"})

    user=> (clucille/search index "scientist" 10)
    ({:name "Donald", :job "Computer Scientist"})

You can search and remove all in one step. To remove all of the
scientists...

    (clucille/search-and-delete index "job:scientist")

Storing Fields
--------------

By default all fields in a map are stored and indexed. If you would
like more fine-grained control over which fields are stored and index,
add this to the meta-data for your map.

    (with-meta {:name "Stever", :job "Writer", :phone "555-212-0202"}
      {:phone {:stored false}})

When the map above is saved to the index, the phone field will be
available for searching but will not be part of map in the search
results. This example is pretty contrived, this makes more sense when
you are indexing something large (like the full text of a long
article) and you don't want to pay the price of storing the entire
text in the index.

Default Search Field
--------------------

A field called "\_content" that contains all of the map's values is
stored in the index for each map (excluding fields with {:stored false}
in the map's metadata). This provides a default field to run all
searches against. Anytime you call the search function without
providing a default search field "\_content" is used.

This behavior can be disabled by binding *content* to false, you must
then specify the default search field with every search invocation.
