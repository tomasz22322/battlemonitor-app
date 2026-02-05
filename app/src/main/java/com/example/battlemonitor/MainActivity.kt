package com.example.battlemonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.battlemonitor.service.PlayerMonitorService
import com.example.battlemonitor.ui.GroupBackgroundDecoration
import com.example.battlemonitor.ui.PlayerAdapter
import com.example.battlemonitor.viewmodel.PlayerMonitorViewModel

class MainActivity : AppCompatActivity() {

    private val vm: PlayerMonitorViewModel by viewModels()
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestNotificationPermissionIfNeeded()
        startBackgroundMonitoring()

        val et = findViewById<EditText>(R.id.etPlayerKey)
        val btnAdd = findViewById<Button>(R.id.btnAdd)
        val rv = findViewById<RecyclerView>(R.id.rvPlayers)
        val tvOnlineCount = findViewById<TextView>(R.id.tvOnlineCount)

        lateinit var itemTouchHelper: ItemTouchHelper

        val adapter = PlayerAdapter(
            onRemove = { player ->
                vm.removePlayer(player)
            },
            onRenameGroup = { group ->
                showGroupDialog(
                    title = "Zmień nazwę grupy",
                    defaultValue = group,
                    onChosen = { newName ->
                        vm.renameGroup(group, newName)
                    }
                )
            },
            onToggleGroupNotifications = { group ->
                if (ensureNotificationPermission()) {
                    vm.toggleGroupNotifications(group)
                }
            },
            onDeleteGroup = { group ->
                AlertDialog.Builder(this)
                    .setTitle("Usuń grupę")
                    .setMessage("Czy na pewno chcesz usunąć grupę i jej członków?")
                    .setNegativeButton("Anuluj", null)
                    .setPositiveButton("Usuń") { _, _ ->
                        vm.deleteGroup(group)
                    }
                    .show()
            },
            onToggleNotifications = { player ->
                if (ensureNotificationPermission()) {
                    vm.toggleNotifications(player)
                }
            },
            onStartDrag = { viewHolder ->
                itemTouchHelper.startDrag(viewHolder)
            },
            onReorder = { items ->
                vm.reorderPlayers(items)
            }
        )

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        rv.addItemDecoration(GroupBackgroundDecoration(this))

        val touchCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return adapter.onItemMove(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                adapter.onDragFinished()
            }
        }

        itemTouchHelper = ItemTouchHelper(touchCallback)
        itemTouchHelper.attachToRecyclerView(rv)

        btnAdd.setOnClickListener {
            val key = et.text.toString().trim()
            if (key.isBlank()) return@setOnClickListener

            showGroupDialog(
                title = "Grupa gracza",
                defaultValue = "",
                onChosen = { group ->
                    vm.addPlayer(key, group)
                    et.setText("")
                }
            )
        }

        vm.items.observe(this) { list ->
            adapter.submitList(list)
        }

        vm.onlinePlayersCount.observe(this) { count ->
            tvOnlineCount.text = count?.toString() ?: "--"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startBackgroundMonitoring() {
        val intent = Intent(this, PlayerMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun ensureNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        return granted
    }

    private fun showGroupDialog(
        title: String,
        defaultValue: String,
        onChosen: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            hint = "np. Friends / Solo / PL"
            setText(defaultValue)
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("Anuluj", null)
            .setPositiveButton("OK") { _, _ ->
                val group = input.text.toString().trim()
                onChosen(group)
            }
            .show()
    }
}
