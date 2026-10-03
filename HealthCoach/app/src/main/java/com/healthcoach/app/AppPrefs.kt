package com.healthcoach.app
import android.content.Context
class AppPrefs(c:Context){private val p=c.getSharedPreferences("healthcoach",Context.MODE_PRIVATE)
var folderUri:String? get()=p.getString("folder",null) set(v){p.edit().putString("folder",v).apply()}
var frequencyMinutes:Long get()=p.getLong("frequency",60) set(v){p.edit().putLong("frequency",v).apply()}
var fixedTimes:String get()=p.getString("times","08:20, 22:30")?:"" set(v){p.edit().putString("times",v).apply()}
var lastSync:Long get()=p.getLong("lastSync",0) set(v){p.edit().putLong("lastSync",v).apply()}
var lastError:String? get()=p.getString("lastError",null) set(v){p.edit().putString("lastError",v).apply()}}