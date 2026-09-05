package com.minddeck.nativeapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Tokens never go into plaintext preferences, logs, backups, or card exports. */
class SessionVault(context: Context) {
    private val prefs = context.getSharedPreferences("native-session", Context.MODE_PRIVATE)
    private val alias = "minddeck-native-session-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(session: UserSession) {
        val data = JSONObject().put("id", session.id).put("name", session.name).put("access", session.accessToken).put("refresh", session.refreshToken).toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) { "Could not save secure login." }
    }
    fun read(): UserSession? = try {
        val raw = prefs.getString("data", null)
        if(raw == null) null else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP))) }
            val data = JSONObject(String(cipher.doFinal(Base64.decode(raw, Base64.NO_WRAP)), Charsets.UTF_8))
            UserSession(data.getString("id"), data.getString("name"), data.getString("access"), data.getString("refresh"))
        }
    } catch (_: Exception) { clear(); null }
    fun clear() { prefs.edit().clear().commit() }
}

class StudyStore(context: Context): SQLiteOpenHelper(context, "minddeck-native.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cards (id TEXT PRIMARY KEY, owner TEXT NOT NULL, deck TEXT NOT NULL, subject TEXT NOT NULL, front TEXT NOT NULL, back TEXT NOT NULL, due INTEGER NOT NULL, interval INTEGER NOT NULL, reviews INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX cards_owner_due ON cards(owner,due)")
        db.execSQL("CREATE TABLE focus (id TEXT PRIMARY KEY, owner TEXT NOT NULL, seconds INTEGER NOT NULL, finished INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) { error("Unsupported database migration; existing data has not been changed.") }
    fun cards(owner: String): List<StudyCard> = readableDatabase.query("cards", null, "owner=?", arrayOf(owner), null, null, "due ASC, rowid ASC").use { c ->
        buildList { while(c.moveToNext()) add(StudyCard(c.getString(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("deck")), c.getString(c.getColumnIndexOrThrow("subject")), c.getString(c.getColumnIndexOrThrow("front")), c.getString(c.getColumnIndexOrThrow("back")), c.getLong(c.getColumnIndexOrThrow("due")), c.getInt(c.getColumnIndexOrThrow("interval")), c.getInt(c.getColumnIndexOrThrow("reviews")))) }
    }
    fun save(owner: String, cards: List<StudyCard>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            cards.forEach { card ->
                val values = ContentValues().apply { put("id",card.id);put("owner",owner);put("deck",card.deck);put("subject",card.subject);put("front",card.front);put("back",card.back);put("due",card.due);put("interval",card.interval);put("reviews",card.reviews) }
                check(db.insertWithOnConflict("cards", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun deleteDeck(owner: String, title: String) { writableDatabase.delete("cards", "owner=? AND deck=?", arrayOf(owner,title)) }
    fun finishFocus(owner: String, id: String, seconds: Int, now: Long) {
        if(id.isBlank()) return
        writableDatabase.insertWithOnConflict("focus", null, ContentValues().apply { put("id",id);put("owner",owner);put("seconds",seconds);put("finished",now) }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun focusSeconds(owner: String): Int = readableDatabase.rawQuery("SELECT COALESCE(SUM(seconds),0) FROM focus WHERE owner=?", arrayOf(owner)).use { it.moveToFirst(); it.getInt(0) }
}
