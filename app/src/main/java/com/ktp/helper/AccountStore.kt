package com.ktp.helper

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class StudentAccount(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val token: String,
    var enabled: Boolean = true,
    var isValid: Boolean = true
)

class AccountStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ktp_secure_vault",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getAccounts(): MutableList<StudentAccount> {
        val raw = prefs.getString("accounts_json", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<StudentAccount>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                StudentAccount(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    name = obj.getString("name"),
                    token = obj.getString("token"),
                    enabled = obj.optBoolean("enabled", true),
                    isValid = obj.optBoolean("isValid", true)
                )
            )
        }
        return list
    }

    fun saveAccounts(list: List<StudentAccount>) {
        val arr = JSONArray()
        list.forEach { acc ->
            arr.put(JSONObject().apply {
                put("id", acc.id)
                put("name", acc.name)
                put("token", acc.token)
                put("enabled", acc.enabled)
                put("isValid", acc.isValid)
            })
        }
        prefs.edit().putString("accounts_json", arr.toString()).apply()
    }

    fun addOrUpdateAccount(name: String, token: String) {
        val list = getAccounts()
        val existingIndex = list.indexOfFirst { it.name == name }
        if (existingIndex >= 0) {
            list[existingIndex] = list[existingIndex].copy(token = token, isValid = true, enabled = true)
        } else {
            list.add(StudentAccount(name = name, token = token))
        }
        saveAccounts(list)
    }

    fun exportJson(): String = prefs.getString("accounts_json", "[]") ?: "[]"

    fun importJson(jsonStr: String): Boolean {
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<StudentAccount>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    StudentAccount(
                        name = obj.getString("name"),
                        token = obj.getString("token"),
                        enabled = obj.optBoolean("enabled", true)
                    )
                )
            }
            saveAccounts(list)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}