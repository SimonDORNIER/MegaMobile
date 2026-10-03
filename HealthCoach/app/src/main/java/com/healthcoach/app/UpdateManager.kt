package com.healthcoach.app
import android.app.DownloadManager
import android.content.*
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
object UpdateManager{
 const val MANIFEST="https://raw.githubusercontent.com/SimonDORNIER/MegaMobile/main/healthcoach/update.json"
 data class Info(val code:Int,val name:String,val apk:String,val notes:String)
 suspend fun check():Info?=withContext(Dispatchers.IO){runCatching{val j=JSONObject(URL(MANIFEST).readText());Info(j.getInt("versionCode"),j.getString("versionName"),j.getString("apkUrl"),j.optString("notes")).takeIf{it.code>BuildConfig.VERSION_CODE}}.getOrNull()}
 fun download(c:Context,i:Info){if(!c.packageManager.canRequestPackageInstalls()){c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+c.packageName)));return};val r=DownloadManager.Request(Uri.parse(i.apk)).setTitle("HealthCoach "+i.name).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalFilesDir(c,Environment.DIRECTORY_DOWNLOADS,"HealthCoach-"+i.name+".apk");c.getSystemService(DownloadManager::class.java).enqueue(r)}
}