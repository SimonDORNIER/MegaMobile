package com.healthcoach.app
import android.app.DownloadManager
import android.content.*
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
class UpdateDownloadReceiver:BroadcastReceiver(){
 override fun onReceive(c:Context,i:Intent?){
  if(i?.action!=DownloadManager.ACTION_DOWNLOAD_COMPLETE)return
  val id=i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1)
  val expected=c.getSharedPreferences("healthcoach",Context.MODE_PRIVATE).getLong("updateDownloadId",-2)
  if(id!=expected)return
  val dm=c.getSystemService(DownloadManager::class.java)
  val cur=dm.query(DownloadManager.Query().setFilterById(id))
  cur.use{if(!it.moveToFirst()||it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))!=DownloadManager.STATUS_SUCCESSFUL)return}
  val local=dm.getUriForDownloadedFile(id)?:return
  val install=Intent(Intent.ACTION_VIEW).setDataAndType(local,"application/vnd.android.package-archive").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
  c.startActivity(install)
 }
}