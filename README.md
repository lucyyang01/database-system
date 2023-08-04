# Database System

## Description

This is my implementation of a minimalist database system that is capable of executing concurrent transactions. This project was split into four phases. 

The `btrees` directory contains an implementation that supports B+ tree indexing and bulk loading. 

The `joins` directory contains an implementation of some common join algorithms: block nested loop join, sort merge, and grace hash join, and a limited version of the Selinger optimizer. 

The `concurrency` directory contains an implementation of a lock manager and a queuing system for locks, as well as a lock context where all multigranularity operations are implemented.

The `recovery` directory contains an implementation of write-ahead logging and support for savepoints, rollbacks, and ACID compliant restart recovery (ARIES recovery protocol).

## Collaborators

Parts 2-4 of this project were completed with my partner, Esther Sue. This project was completed for UC Berkeley's Database Systems class.

Full project specifications can be found here: https://cs186.gitbook.io/project/