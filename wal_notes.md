## the write-ahead log
- reliability foundation for DBs & distributed systems 

## the challenge: reliability
- disSys, DBs, message brokers must manage state reliably
- must ensure a system can:
    - recover from crashes
    - maintain durability
    - replicate changes efficiently w/o compromising performance  

- state management in such systems is filled w/ complexities
    - updates can fail midway 
    - nodes can crash unexpectedly
    - networks can introduce delays or partitioning  

- to tackle such challenges, most reliable systems rely on _Write-Ahead Logs_ __(WALs)__ 

## main idea
- never make a change directly, instead --> first append the change to a __durable log__ 
- guarantees no matter what happens, the system can always recover or replicate its state 
- before applying any changes to the main data store, the system writes the changes to an append-only log
    - log serves as a sequential, persistent record of every operation 
- if the system crashes midway through applying a change, the WAL can be replayed to restore the system 
  to a consistent state 



## a situation
- a row w/in a DB table is being updated 
- w/o a WAL, the DB might overwrite the row in the main storage 
- if server crashes halfway through writing the new data during this op, old & new data could be corrupted, 
  leaving the system in an inconsistent state 
- the update is neither "committed" nor safely recoverable 


## how a WAL fixes this

1. __Log First:__ 
    - system writes the change (e.g., "update row X with new value") to the WAL 
    - this log is sequentially appended & written for durable storage, such as disks
    - hence - "Write Ahead Log" (every entry is written ahead of the prior) 
2. __Apply Later:__ 
    - system applies the change to the actual data structures (e.g., tables or indexes) 
      only after the 
      WAL entry has been safely persisted (committed to disk) 
    - this step can be async & may happen with some delay since the log already guarantees
      the change is not lost 
3. __Crash Recovery:__ 
    - if system crashes after writing the log but before applying the change, the WAL can be
      replayed on restart   
    - this ensures the op is eventually applied 
    - log serves as a source of truth, allowing the system to apply any uncommitted changes 
      & restore a consistent state 

- This mechanism guarantees __two critical things:__ 
    - __Durability:__ 
        - changes aren't __lost__ once they're logged 
    - __Consistency:__ 
        - if a crash occurs, the system can always replay the WAL to apply incomplete 
          ops & recover to a consistent state  


