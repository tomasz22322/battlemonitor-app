package com.example.battlemonitor

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.battlemonitor.ui.PlayerAdapter
import com.example.battlemonitor.viewmodel.PlayerMonitorViewModel

class MainActivity : AppCompatActivity() {

    private val vm: PlayerMonitorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val et = findViewById<EditText>(R.id.etPlayerKey)
        val btnAdd = findViewById<Button>(R.id.btnAdd)
        val rv = findViewById<RecyclerView>(R.id.rvPlayers)

        val adapter = PlayerAdapter { player ->
            vm.removePlayer(player)
        }

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        btnAdd.setOnClickListener {
            val key = et.text.toString().trim()
            if (key.isBlank()) return@setOnClickListener

            showGroupDialog(
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

    private fun showGroupDialog(onChosen: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = "np. Friends / Solo / PL"
            setText("DEFAULT")
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("Grupa gracza")
            .setView(input)
            .setNegativeButton("Anuluj", null)
            .setPositiveButton("OK") { _, _ ->
                val group = input.text.toString().trim().ifBlank { "DEFAULT" }
                onChosen(group)
            }
            .show()
    }
}
