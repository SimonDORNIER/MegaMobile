plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.healthcoach.app"; compileSdk = 35
 defaultConfig { applicationId = "com.healthcoach.app"; minSdk = 28; targetSdk = 35; versionCode = 200; versionName = "2.0.0" }
 buildFeatures { viewBinding = true; buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 signingConfigs {
  create("release") {
   val kp=System.getenv("HEALTHCOACH_KEYSTORE_PATH")
   if(!kp.isNullOrBlank()){storeFile=file(kp);storePassword=System.getenv("HEALTHCOACH_KEYSTORE_PASSWORD");keyAlias=System.getenv("HEALTHCOACH_KEY_ALIAS");keyPassword=System.getenv("HEALTHCOACH_KEY_PASSWORD")}
  }
 }
 buildTypes { getByName("release"){isMinifyEnabled=false;if(!System.getenv("HEALTHCOACH_KEYSTORE_PATH").isNullOrBlank())signingConfig=signingConfigs.getByName("release")} }
}
dependencies {
 implementation("androidx.core:core-ktx:1.15.0"); implementation("androidx.appcompat:appcompat:1.7.0")
 implementation("com.google.android.material:material:1.12.0"); implementation("androidx.activity:activity-ktx:1.10.0")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7"); implementation("androidx.work:work-runtime-ktx:2.10.0")
 implementation("androidx.documentfile:documentfile:1.0.1"); implementation("androidx.health.connect:connect-client:1.1.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}