# Nothing in this app is reached by name.
#
# There is no reflection, no serialisation library, no Class.forName and no
# JNI: the whole of core/ is plain Kotlin over primitive arrays, and every
# Android entry point - the three activities and the service - is named in the
# manifest, which AGP keeps for us. So R8 needs no keep rules of its own, and
# this file exists to say that rather than to be empty by accident.
#
# The one thing worth preserving is the ability to read a stack trace out of a
# capture that went wrong on somebody else's phone.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
