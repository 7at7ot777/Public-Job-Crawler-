# OkHttp and Jsoup ship their own consumer rules. Jsoup references re2j
# optionally; it is not on the classpath and never loaded.
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.**
