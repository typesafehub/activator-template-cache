# Activator Templates library

This project contains the library/cache code to be able to consume and create Activator templates.



# How to build

This project uses [SBT 0.12](http://scala-sbt.org).   Make sure you have an SBT launcher, and run it in the checked out directory.


## Unit Tests

To run unit tests, simply:
    sbt> test

To run the tests of a particular project, simply:

    sbt> <project>/test

To run a specific test, simply:

    sbt> test-only TestName


## Publishing the Library

First, create a version tag:

    git tag -u <pgp key id> v<version-number>

Then publish:

    sbt> publish-signed

or just

    sbt> publish

# Licensing

This software is licensed under the [Apache 2 license](http://www.apache.org/licenses/LICENSE-2.0).
