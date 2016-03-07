# msm simpleservlet sample

A sample webapp for testing [memcached-session-manager](https://github.com/magro/memcached-session-manager) (msm).

It provides this API:

```
GET /list
GET /put?{key}={value}
GET /counter
GET /autocounter?to={number}
```

Of course `/put`, `/counter` and `/autocounter` are not RESTful at all, but that's really not important here. It shall just be convenient to be able to use this stuff in the browser.