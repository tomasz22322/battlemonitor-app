package com.example.battlemonitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

        val et = findViewById<EditText>(R.id.etPlayerKey)
        val btnAdd = findViewById<Button>(R.id.btnAdd)
        val rv = findViewById<RecyclerView>(R.id.rvPlayers)

        val adapter = PlayerAdapter(
            onRemove = { player ->
                vm.removePlayer(player)
            },
            onMoveGroup = { player ->
                showGroupDialog(
                    title = "Zmień grupę",
                    defaultValue = player.group.ifBlank { "DEFAULT" },
                    onChosen = { group ->
                        vm.movePlayerToGroup(player, group)
                    }
                )
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
            onToggleNotifications = { player ->
                if (ensureNotificationPermission()) {
                    vm.toggleNotifications(player)
                }
            }
        )

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        btnAdd.setOnClickListener {
            val key = et.text.toString().trim()
            if (key.isBlank()) return@setOnClickListener

            showGroupDialog(
                title = "Grupa gracza",
                defaultValue = "DEFAULT",
                onChosen = { group ->
                    vm.addPlayer(key, group)
                    et.setText("")
                }
            )
        }

        vm.items.observe(this) { list ->
            adapter.submitList(list)
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
                val group = input.text.toString().trim().ifBlank { "DEFAULT" }
                onChosen(group)
            }
            .show()
    }
}
