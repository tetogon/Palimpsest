# JNA binds native functions, structure fields and callbacks by name at run time.
-dontwarn java.awt.**
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }

# The UniFFI bindings: the library interface's methods and @Structure.FieldOrder fields
# must keep the names the Rust core exports.
-keep class app.palimpsest.ffi.** { *; }

# zxcvbn reads its dictionaries and messages from resources next to its classes.
-keep class com.nulabinc.zxcvbn.** { *; }

# JLaTeXMath calls its macros by method name and loads fonts and symbol tables by name.
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
-dontwarn org.scilab.forge.jlatexmath.**
