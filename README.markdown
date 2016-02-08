# memcached session manager

[![Build Status](https://jenkins.inoio.de/job/memcached-session-manager%20master/badge/icon)](https://jenkins.inoio.de/job/memcached-session-manager%20master/)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/de.javakaffee.msm/memcached-session-manager/badge.svg)](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22de.javakaffee.msm%22)

memcached-session-manager is a tomcat session manager that keeps sessions in memcached, for highly available, scalable and fault tolerant web applications.
It supports both sticky and non-sticky configurations, and is currently working with tomcat 6.x, 7.x and 8.x. For sticky sessions session failover (tomcat crash)
is supported, for non-sticky sessions this is the default (a session is served by default by different tomcats for different requests).
Also memcashed failover (memcached crash) is supported via migration of sessions. There shall also be no single point of failure, so when a memcached fails
the session will not be lost (but either be available in tomcat or in another memcached).

## Installation and Configuration
Basically you must put the spymemcached jar and the memcached-session-manager jars into tomcat's lib folder.
Additionally you must set the Manager class and add some configuration attributes. This is described in detail in the
[SetupAndConfiguration wiki page](https://github.com/magro/memcached-session-manager/wiki/SetupAndConfiguration).

## Where to get help
Checkout the [wiki](https://github.com/magro/memcached-session-manager/wiki) for documentation, contact the
[mailing list](http://groups.google.com/group/memcached-session-manager) or [submit an issue](https://github.com/magro/memcached-session-manager/issues).

## How to contribute
If you want to contribute to this project you can fork this project, make your changes and submit a [pull request](https://help.github.com/articles/using-pull-requests/).
Or you start on the [mailing list](http://groups.google.com/group/memcached-session-manager) and we'll see how we can work together.

## Samples
There's [sample webapp](https://github.com/magro/memcached-session-manager/tree/master/samples) that allows to run tomcat+msm in different configurations,
both sticky and non-sticky etc, just checkout the [README](https://github.com/magro/memcached-session-manager/tree/master/samples).

## License
The license is Apache 2.0, see LICENSE.txt.