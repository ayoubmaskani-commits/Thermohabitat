# Règles R8 de ThermoHabitat Pro.

# kotlinx.serialization : conserver les sérialiseurs générés (sauvegardes JSON, routes de navigation).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class fr.thermohabitat.**$$serializer { *; }
-keepclassmembers class fr.thermohabitat.** { *** Companion; }
-keepclasseswithmembers class fr.thermohabitat.** { kotlinx.serialization.KSerializer serializer(...); }

# TensorFlow Lite (chargement optionnel d'un modèle embarqué).
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
