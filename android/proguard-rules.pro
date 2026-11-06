# Stack traces lisibles.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# libGDX : le code natif (JNI) rappelle des classes et des champs Java par leur
# nom. Les renommer ou les retirer casse le chargement des images et des polices.
-keep class com.badlogic.gdx.graphics.g2d.Gdx2DPixmap { *; }
-keep class com.badlogic.gdx.graphics.g2d.freetype.** { *; }
-keep class com.badlogic.gdx.utils.BufferUtils { *; }
-keep class com.badlogic.gdx.math.Matrix4 { *; }
-keepclassmembers class com.badlogic.gdx.backends.android.AndroidInput* {
    <init>(com.badlogic.gdx.Application, android.content.Context, java.lang.Object, com.badlogic.gdx.backends.android.AndroidApplicationConfiguration);
}
-dontwarn com.badlogic.gdx.backends.android.invoke.**
-dontwarn com.badlogic.gdx.backends.android.AndroidFragmentApplication
-dontwarn com.badlogic.gdx.utils.GdxBuild
-dontwarn com.badlogic.gdx.jnigen.**
