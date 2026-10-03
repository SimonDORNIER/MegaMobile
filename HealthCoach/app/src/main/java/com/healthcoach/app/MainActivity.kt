package com.healthcoach.app
import android.app.AlarmManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.healthcoach.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
class MainActivity:AppCompatActivity(){
 private lateinit var b:ActivityMainBinding
 private lateinit var p:AppPrefs
 private val reader by lazy{HealthReader(this)}
 private val hp=registerForActivityResult(PermissionController.createRequestPermissionResultContract()){refresh()}
 private val fp=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()){u->if(u!=null){contentResolver.takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);p.folderUri=u.toString();Scheduler.now(this);refresh()}}
 private val labels=listOf("Désactivée","15 minutes","30 minutes","1 heure","2 heures","4 heures")
 private val vals=listOf(0L,15L,30L,60L,120L,240L)
 override fun onCreate(s:Bundle?){super.onCreate(s);b=ActivityMainBinding.inflate(layoutInflater);setContentView(b.root);p=AppPrefs(this);b.frequency.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,labels);b.frequency.setSelection(vals.indexOf(p.frequencyMinutes).coerceAtLeast(0));b.fixedTimes.setText(p.fixedTimes);b.permissions.setOnClickListener{hp.launch(reader.permissions)};b.folder.setOnClickListener{fp.launch(null)};b.syncNow.setOnClickListener{Scheduler.now(this);toast("Synchronisation lancée")};b.saveSchedule.setOnClickListener{p.frequencyMinutes=vals[b.frequency.selectedItemPosition];p.fixedTimes=b.fixedTimes.text.toString();Scheduler.apply(this);val am=getSystemService(AlarmManager::class.java);if(!am.canScheduleExactAlarms())runCatching{startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))};toast("Planification enregistrée");refresh()};b.checkUpdate.setOnClickListener{update(true)};Scheduler.apply(this);Scheduler.now(this);update(false);refresh()}
 override fun onResume(){super.onResume();refresh()}
 private fun refresh(){lifecycleScope.launch{val ok=runCatching{reader.allowed()}.getOrDefault(false);val sync=if(p.lastSync>0)DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(p.lastSync))else"jamais";b.status.text="Santé Connect : "+(if(ok)"✓ autorisé"else"⚠ autorisation requise")+"\nDossier Drive : "+(if(p.folderUri!=null)"✓ choisi"else"⚠ à choisir")+"\nDernière synchro : "+sync+(p.lastError?.let{"\nDernière erreur : "+it}?:"");b.version.text="Version "+BuildConfig.VERSION_NAME}}
 private fun update(show:Boolean){lifecycleScope.launch{val i=UpdateManager.check();if(i==null){if(show)toast("HealthCoach est à jour")}else AlertDialog.Builder(this@MainActivity).setTitle("Mise à jour "+i.name).setMessage(i.notes).setNegativeButton("Plus tard",null).setPositiveButton("Mettre à jour"){_,_->UpdateManager.download(this@MainActivity,i)}.show()}}
 private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
}