# Android Auto SDK: Android Auto talks to it by reflection and over binder, so keep it whole.
-keep class com.google.android.apps.auto.** { *; }
-keep class com.google.android.gms.car.** { *; }
-keep class android.support.car.** { *; }
-keep class com.google.android.a.** { *; }
-keep class com.google.a.a.a.** { *; }
-dontwarn com.google.android.apps.auto.**
-dontwarn com.google.android.gms.**
-dontwarn android.support.car.**

# The SDK creates the car screen from its class (CarService.getCarActivity) with reflection.
-keep class com.tvdeck.app.car.** { *; }
