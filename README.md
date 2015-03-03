# compressible-map

## Design

## compressible-map
Implements a persistent map which can compresses its contents.  Functionally, this type is equivalent to any other persistent map.  It's differences lie in how it internally manages its data.

### Design sketch

* Top-level map wraps each value in an agent
* Each value consists of a front and back store
* Top-level map has a fixed length queue as metadata called :compress-queue, wrapped in an atom
* value reads will
  1. Deref the value.
  1. If value is not decompressed, send decompress to agent and wait for result.
  1. return the merged get results from the front and back store.
* decompress will do nothing if the back store is already decompressed, else it will
  1. check if :compress-queue is full.  If so it will send compress to the agent at the head of the queue
  2. decompress the back store
  3. put the agent ref on the :compress-queue
* compress will do nothing if the back store is already compressed, else it will
  1. compress the value (merging front and back store and compressing)
  1. remove the ref from :compress-queue

Putting each value in an agent has the following properties:
* compress and decompress are asynchronous operations
* compression and decompression of each value is independent of other values
* compression and decompression operations for an individual value are serialized, so there can be no conflict
* The get client can deref the value and if it is not compressed, it can use the value immediately
* concurrent gets to the same value may send decompress, but only the first will actually decompress

:compress-queue is shared state between all threads that are decompressing (i.e. all get calls).  Before decompressing each thread needs to decide whether to trigger a compress for another value (because the queue is full), and pick which value to compress.  There are two possible approaches:

* We can wrap the decision and the send in a transaction.  The queue would need to be in a ref.  That way each thread makes a decision based on the initial value of the ref, and if any other thread touches the ref it will retry the decision.  The send is only issued when the transaction closes.

* We can make the decision without a transaction.  The queue would be in an atom.  In this case two threads could send a compress for the same value at the head of the queue.  We could use swap! to update the queue.  In this case atom retry semantics will cause the loser of the race to retry and compress the next entry on the queue.  Or, we could use compare-and-set! so that the client can manually retry on the next head if its update fails.


## Compression

### Data compression

The compression function is pluggable.  The implementation uses Nippy to produce a compressed byte array, and so the compression algorithm can be configured through Nippy if desired.

#### Compression libraries
* LZ4
* Snappy
* Check nippy implementation


## Caveats
The current design is a leaky abstraction.  I have not figured out how to totally hide compression as an internal state.  The fact is that when a value is compressed, it has different semantics than the same value in a decompressed state, because writes go "blindly" to the front buffer, and reads require merging the front and back buffers.  I'm not sure that this model can ever be totally hidden from the client.

The current design automatically derefs and merges reads, making it act like a regular map on reads.  We provide a special replacement for `assoc-in`, `assoc-in-compressed` that allows us to add data to a compressed value without first decompressing and merging, as is the case when using normal `assoc-in`.

The automatic management of compression has not yet been implemented.

## TODO ideas

* front/back terminology
* PersistentPriorityMap is persistent b/c it contains persistent data structures.  Mutating operations return a new instance referring to "new" persistent structures.
* compress and archive are pretty much the same -- reduce down to just compress
* compress is an identity fn from the POV of the data model and should not be exposed externally
* compress breaks structural sharing, in that mutation before/after compression will not share structure.  If this happens a lot, then there will be a lot 
of redundant data in memory, but if there are no refs to previous versions, this will be fine.
* compression can be triggered when front archive gets too big or when a decompressed back archive is evicted from the ring buffer
* tuple-db is *not* a persistent data structure -- it wraps a shared reference.  update fn should be update! because it changes state in a way that is visible across threads.
* Each compressed map is shared state and the ring buffer is shared state
* Decompression of a compressed map and the ring buffer need to be coordinated
* ring eviction/compression needs to be coordinated

#### TODO concurrency cases:
* update!-triggered compression & eviction-triggered compression
* two update!s at compression threshold
* read-triggered decompression & any-triggered compression

* As long as the whole compressed map is in an atom, should be OK


## License

Copyright © 2015 Michael Richards

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
