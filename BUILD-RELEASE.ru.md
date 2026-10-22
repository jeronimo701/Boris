# APK 1.0.13

JDK 17, Android SDK 35, Gradle Wrapper 8.11.1.

`gradlew.bat --no-daemon :app:assembleDebug :app:lintDebug`

APK: app/build/outputs/apk/debug/app-debug.apk. В релизе Boris-1.0.13-debug.apk; подпись отладочная, versionCode 14, minSdk 26, targetSdk 35. Проверка подписи apksigner успешна, lint: 0 ошибок, 11 предупреждений. На телефоне и пылесосе не проверялся. Установка поверх сборки с другой подписью может быть недоступна; предварительно сохраните нужные данные.
