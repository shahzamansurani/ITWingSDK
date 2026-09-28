# ITWingConfig is serialized into the SDK's encrypted on-device cache with
# Gson. Preserve model field names for host apps that enable R8/minification.
-keepclassmembers class com.itwingtech.itwingsdk.core.** {
    <fields>;
}
