# msm sample apps

Provides sample webapps and allows to run them using the [tomcat maven plugin](http://tomcat.apache.org/maven-plugin.html).

Run the simpleservlet sample run:

    $ mvn tomcat7:run -pl :simpleservlet -am

The `context.xml` file uses some properties that can be set via these system properties:

    msm.sticky
    msm.memcachedNodes
    msm.failoverNodes

There are several predefined maven profiles:

    sticky (active by default)
    single-memcached (sticky)
    non-sticky

The activate e.g. the non-sticky profile it's also necessary to overwrite the msm.failoverNodes:

    $ mvn -Pnon-sticky -Dmsm.failoverNodes=" " tomcat7:run -pl :simpleservlet -am

To increase the log level specify an appropriate logging properties:

    $ mvn tomcat7:run -pl :simpleservlet -am -Djava.util.logging.config.file=src/main/resources/logging.properties
