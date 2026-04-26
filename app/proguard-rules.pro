# Add project specific ProGuard rules here.
-keep class com.teslablelab.data.proto.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
