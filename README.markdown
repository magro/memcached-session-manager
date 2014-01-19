# memcached session manager

[![Build Status](https://jenkins.inoio.de/job/memcached-session-manager%20master/badge/icon)](https://jenkins.inoio.de/job/memcached-session-manager%20master/)

memcached-session-manager is a tomcat session manager that keeps sessions in memcached, for highly available, scalable and fault tolerant web applications. It supports both sticky and non-sticky configurations, and is currently working with tomcat 6.x and 7.x. For sticky sessions session failover (tomcat crash) is supported, for non-sticky sessions this is the default (a session is served by default by different tomcats for different requests). Also memcashed failover (memcached crash) is supported via migration of sessions. There shall also be no single point of failure, so when a memcached fails the session will not be lost (but either be available in tomcat or in another memcached).

The project home page is located at [googlecode.com](http://code.google.com/p/memcached-session-manager/).

## Installation and Configuration
Basically you must put the spymemcached jar and the memcached-session-manager jar into tomcat's lib folder.
Additionally you must set the Manager class and add some configuration attributes. This is described in detail in the [SetupAndConfiguration wiki page](http://code.google.com/p/memcached-session-manager/wiki/SetupAndConfiguration).

## Where to get help
Checkout the [wiki](http://code.google.com/p/memcached-session-manager/w/list) for documentation, contact the [mailing list](http://groups.google.com/group/memcached-session-manager) or [submit an issue](http://code.google.com/p/memcached-session-manager/issues/list).

## How to contribute
If you want to contribute to this project you can fork the [sources on github](https://github.com/magro/memcached-session-manager), make your changes and submit a pull request. Alternatively you can [submit an issue](http://code.google.com/p/memcached-session-manager/issues/list) with a patch attached. Or you start on the [mailing list](http://groups.google.com/group/memcached-session-manager) and we'll see how we can work together.

## Samples
There's a [github project](https://github.com/magro/msm-sample-webapp) that has various memcached-session-manager example configurations, both sticky and non-sticky, with tomcat 6 and tomcat7, with wicket or openwebbeans and more. Just checkout the different branches and see if there's s.th. interesting for you.
