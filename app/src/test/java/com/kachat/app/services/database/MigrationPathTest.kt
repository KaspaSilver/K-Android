package com.kachat.app.services.database

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The path Room takes from each old database version (release audit 2026-11-27 M1, L7). There is
 * no Room testing setup in this project, so this checks the path Room's own [RoomDatabase.MigrationContainer]
 * picks, and the SQL each step runs, against a recording stand-in for the database.
 */
class MigrationPathTest {
    private val current = 46
    private val keptTables = listOf(
        "portfolios", "portfolio_transactions", "broadcast_channels", "broadcast_messages", "broadcast_hidden_senders",
    )

    private fun path(from: Int): List<Migration>? = RoomDatabase.MigrationContainer().apply {
        addMigrations(*KaChatDatabase.ALL_MIGRATIONS)
    }.findMigrationPath(from, current)

    /** The SQL [migrations] run, in order. Only `execSQL` is expected; anything else fails. */
    private fun sqlOf(migrations: List<Migration>): List<String> {
        val sql = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> { sql += args!![0] as String; null }
                else -> error("unexpected call ${method.name}")
            }
        } as SupportSQLiteDatabase
        migrations.forEach { it.migrate(db) }
        return sql
    }

    @Test
    fun everyVersionFrom15ReachesTheCurrentOne() {
        for (v in 15 until current) assertNotNull("no path from v$v", path(v))
    }

    @Test
    fun v42TakesTheDirectJumpAndKeepsPortfoliosAndRooms() {
        val p = path(42)!!
        assertEquals(listOf(42 to 45, 45 to 46), p.map { it.startVersion to it.endVersion })
        val sql = sqlOf(p)
        assertTrue(sql.any { it.contains("DROP TABLE IF EXISTS `swap_transactions`") })
        keptTables.forEach { t -> assertFalse("v42 drops $t", sql.any { it.startsWith("DROP") && it.contains("`$t`") }) }
    }

    @Test
    fun v43KeepsPortfoliosAndBringsBackRooms() {
        val p = path(43)!!
        assertEquals(listOf(43 to 45, 45 to 46), p.map { it.startVersion to it.endVersion })
        val sql = sqlOf(p)
        keptTables.forEach { t -> assertFalse("v43 drops $t", sql.any { it.startsWith("DROP") && it.contains("`$t`") }) }
        listOf("broadcast_channels", "broadcast_messages", "broadcast_hidden_senders").forEach { t ->
            assertTrue("v43 doesn't recreate $t", sql.any { it.startsWith("CREATE TABLE IF NOT EXISTS `$t`") })
        }
    }

    @Test
    fun olderVersionsPassThroughTheDirectJump() {
        // A 5.1 phone (v41) goes 41 -> 42 -> 45 -> 46, never through the dropping steps.
        val p = path(41)!!.map { it.startVersion to it.endVersion }
        assertFalse(p.contains(42 to 43))
        assertFalse(p.contains(43 to 44))
        assertEquals(46, p.last().second)
    }

    @Test
    fun v44StillRecreatesTheTables() {
        val p = path(44)!!
        assertEquals(listOf(44 to 45, 45 to 46), p.map { it.startVersion to it.endVersion })
    }
}
