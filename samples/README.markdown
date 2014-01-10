# msm sample apps

Provides sample webapps and allows to run them using the [tomcat maven plugin](http://tomcat.apache.org/maven-plugin.html).

## Prerequisites
1. [Maven](http://maven.apache.org): you should have installed maven to be able to run the samples.
2. [memcached](http://memcached.org): you should have installed memcached so that you can run the webapp with sessions replicated to memcached
3. I don't mention java here :-)

## Usage

Before running the samples you should start 2 memcached instances:

    $ memcached -p 11211 -u memcached -m 64 -M -vv &
    $ memcached -p 11212 -u memcached -m 64 -M -vv &

Then you can run a tomcat with a sample webapp via maven, e.g. with the simpleservlet sample app:

    $ mvn tomcat7:run -pl :simpleservlet -am

Tomcat + webapp is then available at http://localhost:9090.

(For more information how to use the sample app please check out the README in the relevant sample)

The `context.xml` file uses some properties that can be set via these system properties:

    msm.sticky
    msm.memcachedNodes
    msm.failoverNodes

There are several predefined maven profiles:

    sticky (active by default)
    single-memcached (sticky)
    non-sticky

To activate the non-sticky profile it's also necessary to overwrite the msm.failoverNodes:

    $ mvn tomcat7:run -pl :simpleservlet -am -Pnon-sticky -Dmsm.failoverNodes=" "

To increase the log level specify an appropriate logging properties, like this one:

    $ mvn tomcat7:run -pl :simpleservlet -am -Djava.util.logging.config.file=src/main/resources/logging.properties
