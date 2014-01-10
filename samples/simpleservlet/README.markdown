# msm simpleservlet sample

A sample webapp for testing [memcached-session-manager](http://code.google.com/p/memcached-session-manager/) (msm).

It provides this API:

```
GET /list
GET /put?{key}={value}
```

Of course the `/put` is not RESTful at all, but that's really not important here. It shall just be convenient to be able to use this stuff in the browser.