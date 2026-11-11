# Keep exception class names readable so troubleshooting mode shows e.g.
# "SocketTimeoutException" instead of an obfuscated "a.b".
-keepnames class * extends java.lang.Throwable
