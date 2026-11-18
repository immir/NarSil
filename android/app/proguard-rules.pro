# The JNI entry points are looked up by name (Java_org_narsil_android_NarSilNative_*): keep the class and its
# native methods as they are.  The default rules keep native method names but may still rename the class.
-keep class org.narsil.android.NarSilNative { native <methods>; }
