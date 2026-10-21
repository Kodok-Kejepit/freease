# NewPipeExtractor memakai refleksi (Rhino JS engine untuk decipher, nanojson).
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class com.grack.nanojson.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn java.beans.**
-dontwarn org.jsoup.**
-dontwarn com.google.re2j.**
-dontwarn javax.annotation.**

# Room entities/DAO aman secara default; jaga nama kolom untuk migrasi.
-keep class com.freease.music.data.db.** { *; }
