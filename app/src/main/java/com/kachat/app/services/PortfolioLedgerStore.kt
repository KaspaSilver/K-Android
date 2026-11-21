package com.kachat.app.services

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.google.gson.Gson
import com.kachat.app.models.PortfolioEntity
import com.kachat.app.models.PortfolioFeeRecord
import com.kachat.app.models.PortfolioTransactionEntity
import com.kachat.app.services.database.KaChatDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a wallet's portfolios are saved (iOS `PortfolioLedgerStore`). Every portfolio and
 * ledger-row write goes through here: a portfolio or row that is new, or changed in anything but
 * its stamp, is stamped `updatedAtMillis` now - centrally, at save, as iOS's
 * `PortfolioLedgerStore.stamped`. The seed "Portfolio 1" is written unstamped ([insertSeed]).
 *
 * Portfolios stay on the phone: KaChat's Nextcloud backs up contacts only (iOS 148a5331), so
 * nothing here marks a backup or records deletions for one, as KaPay's copy did.
 *
 * Also holds the per-wallet fee list (network fees an imported address paid, iOS 61eff0f): one
 * JSON list per wallet in SharedPreferences, the keys unchanged from KaPay's.
 */
@Singleton
class PortfolioLedgerStore @Inject constructor(
    @ApplicationContext context: Context,
    private val database: KaChatDatabase,
) {
    private val gson = Gson()

    /** Every portfolio and row write, one at a time, so two saves never interleave. */
    private val writeMutex = Mutex()

    // -------------------------------------------------------------------------
    // Portfolios
    // -------------------------------------------------------------------------

    /** Saves [rows]: each one new or changed (anything but its stamp) is stamped now. */
    suspend fun savePortfolios(rows: List<PortfolioEntity>) = writeMutex.withLock {
        if (rows.isEmpty()) return@withLock
        val dao = database.portfolioDefinitionDao()
        val now = System.currentTimeMillis()
        val changed = rows.mapNotNull { row ->
            val stored = dao.getById(row.id)
            if (stored != null && stored.copy(updatedAtMillis = null) == row.copy(updatedAtMillis = null)) null
            else row.copy(updatedAtMillis = now)
        }
        if (changed.isEmpty()) return@withLock
        dao.insertAll(changed)
        noteChanged()
    }

    /** A wallet's first "Portfolio 1": never stamped, so it stays a seed until it is touched. */
    suspend fun insertSeed(seed: PortfolioEntity) = writeMutex.withLock {
        database.portfolioDefinitionDao().insert(seed.copy(updatedAtMillis = null))
    }

    /**
     * Deletes portfolio [id] of [walletAddress] with its rows and fees (iOS deletePortfolio +
     * forgetPortfolio).
     */
    suspend fun deletePortfolio(walletAddress: String, id: String) = writeMutex.withLock {
        database.portfolioDefinitionDao().delete(id)
        database.portfolioDao().deleteAllForPortfolio(id)
        synchronized(feeLock) {
            val fees = loadFees(walletAddress)
            val kept = fees.filter { it.portfolioId != id }
            if (kept.size != fees.size) saveFeesRaw(walletAddress, kept)
        }
        noteChanged()
    }

    // -------------------------------------------------------------------------
    // Ledger rows
    // -------------------------------------------------------------------------

    suspend fun saveTransaction(row: PortfolioTransactionEntity) = saveTransactions(listOf(row))

    /** Saves [rows]: each one new or changed (anything but its stamp) is stamped now. */
    suspend fun saveTransactions(rows: List<PortfolioTransactionEntity>) = writeMutex.withLock {
        if (rows.isEmpty()) return@withLock
        val dao = database.portfolioDao()
        val now = System.currentTimeMillis()
        val changed = rows.mapNotNull { row ->
            val stored = dao.getById(row.id)
            if (stored != null && stored.copy(updatedAtMillis = null) == row.copy(updatedAtMillis = null)) null
            else row.copy(updatedAtMillis = now)
        }
        if (changed.isEmpty()) return@withLock
        dao.insertAll(changed)
        noteChanged()
    }

    /** Deletes [ids] of [walletAddress]'s ledger. */
    suspend fun deleteTransactions(walletAddress: String, ids: Collection<String>) = writeMutex.withLock {
        if (ids.isEmpty()) return@withLock
        val dao = database.portfolioDao()
        ids.toList().chunked(DELETE_CHUNK).forEach { dao.deleteByIds(it) }
        noteChanged()
    }

    // -------------------------------------------------------------------------
    // Fees
    // -------------------------------------------------------------------------

    private val feePrefs = context.getSharedPreferences("kachat_portfolio_fees", Context.MODE_PRIVATE)
    private val feeLock = Any()
    private val _feeVersion = MutableStateFlow(0)
    /** Bumped on every fee save, so a reader of [loadFees] re-reads. */
    val feeVersion: StateFlow<Int> = _feeVersion.asStateFlow()

    private fun feesKey(walletAddress: String) = "fees_" + walletAddress.replace(":", "_")

    fun loadFees(walletAddress: String): List<PortfolioFeeRecord> {
        if (walletAddress.isEmpty()) return emptyList()
        val json = feePrefs.getString(feesKey(walletAddress), null) ?: return emptyList()
        return try {
            gson.fromJson(json, Array<PortfolioFeeRecord>::class.java)?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveFeesRaw(walletAddress: String, fees: List<PortfolioFeeRecord>) {
        if (walletAddress.isEmpty()) return
        feePrefs.edit().putString(feesKey(walletAddress), gson.toJson(fees)).apply()
        _feeVersion.value = _feeVersion.value + 1
    }

    /** Rewrites [walletAddress]'s fees (iOS persistFees). */
    fun updateFees(walletAddress: String, transform: (List<PortfolioFeeRecord>) -> List<PortfolioFeeRecord>) {
        val changed = synchronized(feeLock) {
            val before = loadFees(walletAddress)
            val after = transform(before)
            if (after == before) false else { saveFeesRaw(walletAddress, after); true }
        }
        if (changed) noteChanged()
    }

    /**
     * Delete Account: [walletAddress]'s portfolios, rows and fees, from this device
     * (iOS `clearAllLocalData`).
     */
    suspend fun removeAllData(walletAddress: String) = writeMutex.withLock {
        database.withTransaction {
            database.portfolioDao().deleteAllForWallet(walletAddress)
            database.portfolioDefinitionDao().deleteAllForWallet(walletAddress)
        }
        synchronized(feeLock) { feePrefs.edit().remove(feesKey(walletAddress)).apply() }
        _feeVersion.value = _feeVersion.value + 1
    }

    /** A portfolio edit. KaPay marked its Nextcloud backup to upload here; KaChat's Nextcloud
     *  backs up contacts only, so portfolios stay on the phone (iOS 148a5331). */
    private fun noteChanged() = Unit

    companion object {
        private const val TAG = "PortfolioLedgerStore"
        /** Ids per DELETE ... IN (...): well under SQLite's bound-variable limit. */
        private const val DELETE_CHUNK = 500
    }
}
